package com.tokyoxpa3.androidproxy.integration

import android.content.Context
import android.util.Base64
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Small authenticated HTTP portal for local PocketBridge transfers.
 *
 * Security properties:
 * - listeners bind only to explicit LAN addresses supplied by HotspotManager;
 * - no wildcard 0.0.0.0 listener is created;
 * - file APIs require a high-entropy per-session token;
 * - remote readers can only browse `Shared`;
 * - remote writers can only create files in `Inbox`;
 * - one request per connection, bounded headers, bounded upload size.
 */
class PocketBridgeQuickDropServer(
    private val context: Context,
    private val bindAddresses: List<String>,
    val port: Int
) : Closeable {

    data class StartResult(
        val boundAddresses: List<String>,
        val connectUrls: List<String>
    )

    private data class Request(
        val method: String,
        val uri: URI,
        val headers: Map<String, String>,
        val input: InputStream
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listeners = CopyOnWriteArrayList<ServerSocket>()
    private val uploadLock = Any()
    private val resolver = context.contentResolver
    private val token = generateSessionToken()
    private lateinit var root: DocumentFile
    private lateinit var inbox: DocumentFile
    private lateinit var shared: DocumentFile

    @Volatile
    private var closed = false

    fun start(): StartResult {
        check(!closed) { "QuickDrop server is closed" }
        prepareFolders()

        val bound = mutableListOf<String>()
        bindAddresses.distinct().forEach { addressText ->
            try {
                val address = InetAddress.getByName(addressText)
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, port), LISTEN_BACKLOG)
                }
                listeners += server
                bound += addressText
                scope.launch { acceptLoop(server) }
            } catch (_: Exception) {
                // A different LAN interface may still be usable. Caller fails
                // the sidecar only if no address can bind this port.
            }
        }

        if (bound.isEmpty()) {
            close()
            throw IOException("QuickDrop could not bind to a LAN address on port $port")
        }

        val urls = bound.map { address -> "http://$address:$port/#$token" }
        return StartResult(boundAddresses = bound, connectUrls = urls)
    }

    private fun prepareFolders() {
        val treeUri = PocketBridgeFolderStore(context).getTreeUri()
            ?: throw IOException("PocketBridge folder is not configured")
        root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IOException("PocketBridge folder is unavailable")
        if (!root.exists() || !root.isDirectory || !root.canWrite()) {
            throw IOException("PocketBridge folder is not writable")
        }

        inbox = ensureDirectory(root, INBOX_DIRECTORY)
        shared = ensureDirectory(root, SHARED_DIRECTORY)
    }

    private fun ensureDirectory(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.let { existing ->
            if (existing.isDirectory && existing.canWrite()) return existing
            throw IOException("$name exists but is not a writable directory")
        }
        return parent.createDirectory(name)
            ?: throw IOException("Unable to create $name")
    }

    private suspend fun acceptLoop(server: ServerSocket) {
        while (!closed && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            scope.launch { handleClient(socket) }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            try {
                client.soTimeout = CLIENT_TIMEOUT_MS
                client.tcpNoDelay = true
                val input = BufferedInputStream(client.getInputStream(), IO_BUFFER_SIZE)
                val output = BufferedOutputStream(client.getOutputStream(), IO_BUFFER_SIZE)
                val request = readRequest(input)
                route(request, output)
                output.flush()
            } catch (_: Exception) {
                // Never expose stack traces or internal paths to a remote peer.
                try {
                    val output = BufferedOutputStream(client.getOutputStream())
                    writeTextResponse(output, 400, "Bad Request", "Request could not be processed.", "text/plain; charset=utf-8")
                    output.flush()
                } catch (_: Exception) {
                    // Connection may already be gone.
                }
            }
        }
    }

    private fun readRequest(input: BufferedInputStream): Request {
        val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES)
            ?: throw IOException("Missing request line")
        val parts = requestLine.split(' ', limit = 3)
        if (parts.size != 3) throw IOException("Malformed request line")

        val method = parts[0].uppercase(Locale.US)
        val uri = URI(parts[1])
        val headers = linkedMapOf<String, String>()
        var headerBytes = 0

        repeat(MAX_HEADER_COUNT) {
            val line = readAsciiLine(input, MAX_HEADER_LINE_BYTES)
                ?: throw IOException("Unexpected end of headers")
            headerBytes += line.length
            if (headerBytes > MAX_TOTAL_HEADER_BYTES) throw IOException("Headers too large")
            if (line.isEmpty()) {
                return Request(method = method, uri = uri, headers = headers, input = input)
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("Malformed header")
            val key = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim()
            headers[key] = value
        }

        throw IOException("Too many headers")
    }

    private fun route(request: Request, output: BufferedOutputStream) {
        val path = request.uri.path ?: "/"

        if (request.method == "GET" && path == "/") {
            writeTextResponse(output, 200, "OK", PORTAL_HTML, "text/html; charset=utf-8")
            return
        }
        if (request.method == "GET" && path == "/favicon.ico") {
            writeEmptyResponse(output, 204, "No Content")
            return
        }

        if (!isAuthorized(request)) {
            writeTextResponse(output, 401, "Unauthorized", "PocketBridge authorization required.", "text/plain; charset=utf-8")
            return
        }

        when {
            request.method == "GET" && path == "/api/status" -> serveStatus(output)
            request.method == "GET" && path == "/api/shared" -> serveSharedList(output)
            request.method == "GET" && path == "/api/download" -> serveDownload(request, output)
            request.method == "PUT" && path == "/api/inbox" -> receiveUpload(request, output)
            else -> writeTextResponse(output, 404, "Not Found", "Not found.", "text/plain; charset=utf-8")
        }
    }

    private fun isAuthorized(request: Request): Boolean {
        val headerToken = request.headers[AUTH_HEADER]
        if (constantTimeEquals(headerToken, token)) return true
        val queryToken = parseQuery(request.uri.rawQuery)["t"]
        return constantTimeEquals(queryToken, token)
    }

    private fun serveStatus(output: BufferedOutputStream) {
        val body = "{\"service\":\"PocketBridge QuickDrop\",\"mode\":\"drop-inbox/read-shared\"}"
        writeTextResponse(output, 200, "OK", body, "application/json; charset=utf-8")
    }

    private fun serveSharedList(output: BufferedOutputStream) {
        val files = shared.listFiles()
            .asSequence()
            .filter { it.isFile }
            .sortedBy { it.name.orEmpty().lowercase(Locale.US) }
            .joinToString(prefix = "[", postfix = "]") { file ->
                val name = jsonEscape(file.name ?: "unnamed")
                val type = jsonEscape(file.type ?: "application/octet-stream")
                "{\"name\":\"$name\",\"size\":${file.length()},\"type\":\"$type\"}"
            }
        writeTextResponse(output, 200, "OK", files, "application/json; charset=utf-8")
    }

    private fun serveDownload(request: Request, output: BufferedOutputStream) {
        val name = parseQuery(request.uri.rawQuery)["name"]
        if (name.isNullOrBlank()) {
            writeTextResponse(output, 400, "Bad Request", "Missing file name.", "text/plain; charset=utf-8")
            return
        }

        val file = shared.findFile(name)
        if (file == null || !file.isFile) {
            writeTextResponse(output, 404, "Not Found", "Shared file not found.", "text/plain; charset=utf-8")
            return
        }

        val input = resolver.openInputStream(file.uri)
        if (input == null) {
            writeTextResponse(output, 500, "Internal Server Error", "Unable to open shared file.", "text/plain; charset=utf-8")
            return
        }

        val contentType = file.type ?: "application/octet-stream"
        val encodedName = URLEncoder.encode(file.name ?: "download", StandardCharsets.UTF_8.name())
            .replace("+", "%20")
        val length = file.length()

        writeStatusLine(output, 200, "OK")
        writeHeader(output, "Content-Type", contentType)
        writeHeader(output, "Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
        if (length > 0L) writeHeader(output, "Content-Length", length.toString())
        writeHeader(output, "Cache-Control", "no-store")
        writeHeader(output, "Connection", "close")
        endHeaders(output)

        input.use { source -> source.copyTo(output, IO_BUFFER_SIZE) }
    }

    private fun receiveUpload(request: Request, output: BufferedOutputStream) {
        val transferEncoding = request.headers["transfer-encoding"]
        if (!transferEncoding.isNullOrBlank()) {
            writeTextResponse(output, 411, "Length Required", "Chunked uploads are not supported.", "text/plain; charset=utf-8")
            return
        }

        val contentLength = request.headers["content-length"]?.toLongOrNull()
        if (contentLength == null || contentLength < 0L) {
            writeTextResponse(output, 411, "Length Required", "Content-Length is required.", "text/plain; charset=utf-8")
            return
        }
        if (contentLength > MAX_UPLOAD_BYTES) {
            writeTextResponse(output, 413, "Payload Too Large", "Upload exceeds the PocketBridge limit.", "text/plain; charset=utf-8")
            return
        }

        val requestedName = parseQuery(request.uri.rawQuery)["name"]
        if (requestedName.isNullOrBlank()) {
            writeTextResponse(output, 400, "Bad Request", "Missing upload name.", "text/plain; charset=utf-8")
            return
        }
        val mime = request.headers["content-type"]
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: "application/octet-stream"

        val target = synchronized(uploadLock) {
            val safeName = PocketBridgeShareNames.nextAvailableName(requestedName) { candidate ->
                inbox.findFile(candidate) != null
            }
            inbox.createFile(mime, safeName)
                ?: throw IOException("Unable to create Inbox file")
        }

        try {
            val destination = resolver.openOutputStream(target.uri, "w")
                ?: throw IOException("Unable to open Inbox destination")
            destination.use { sink -> copyExactly(request.input, sink, contentLength) }
            val actualName = jsonEscape(target.name ?: requestedName)
            writeTextResponse(
                output,
                201,
                "Created",
                "{\"ok\":true,\"name\":\"$actualName\"}",
                "application/json; charset=utf-8"
            )
        } catch (error: Exception) {
            try {
                target.delete()
            } catch (_: Exception) {
                // Best-effort cleanup.
            }
            throw error
        }
    }

    private fun copyExactly(input: InputStream, output: OutputStream, count: Long) {
        var remaining = count
        val buffer = ByteArray(IO_BUFFER_SIZE)
        while (remaining > 0L) {
            val wanted = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, wanted)
            if (read < 0) throw IOException("Upload ended early")
            output.write(buffer, 0, read)
            remaining -= read.toLong()
        }
        output.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        listeners.forEach { server ->
            try {
                server.close()
            } catch (_: Exception) {
            }
        }
        listeners.clear()
        scope.cancel()
    }

    private fun writeTextResponse(
        output: BufferedOutputStream,
        code: Int,
        reason: String,
        body: String,
        contentType: String
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        writeStatusLine(output, code, reason)
        writeHeader(output, "Content-Type", contentType)
        writeHeader(output, "Content-Length", bytes.size.toString())
        writeHeader(output, "Cache-Control", "no-store")
        writeHeader(output, "X-Content-Type-Options", "nosniff")
        writeHeader(output, "Content-Security-Policy", "default-src 'self' 'unsafe-inline'; connect-src 'self'; img-src 'self' data:")
        writeHeader(output, "Connection", "close")
        endHeaders(output)
        output.write(bytes)
    }

    private fun writeEmptyResponse(output: BufferedOutputStream, code: Int, reason: String) {
        writeStatusLine(output, code, reason)
        writeHeader(output, "Content-Length", "0")
        writeHeader(output, "Connection", "close")
        endHeaders(output)
    }

    private fun writeStatusLine(output: OutputStream, code: Int, reason: String) {
        output.write("HTTP/1.1 $code $reason\r\n".toByteArray(StandardCharsets.US_ASCII))
    }

    private fun writeHeader(output: OutputStream, name: String, value: String) {
        output.write("$name: $value\r\n".toByteArray(StandardCharsets.UTF_8))
    }

    private fun endHeaders(output: OutputStream) {
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
    }

    companion object {
        private const val AUTH_HEADER = "x-pocketbridge-token"
        private const val INBOX_DIRECTORY = "Inbox"
        private const val SHARED_DIRECTORY = "Shared"
        private const val LISTEN_BACKLOG = 32
        private const val CLIENT_TIMEOUT_MS = 30_000
        private const val IO_BUFFER_SIZE = 64 * 1024
        private const val MAX_REQUEST_LINE_BYTES = 8 * 1024
        private const val MAX_HEADER_LINE_BYTES = 8 * 1024
        private const val MAX_TOTAL_HEADER_BYTES = 32 * 1024
        private const val MAX_HEADER_COUNT = 100
        private const val MAX_UPLOAD_BYTES = 2L * 1024 * 1024 * 1024

        private fun generateSessionToken(): String {
            val bytes = ByteArray(24)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        }

        internal fun parseQuery(rawQuery: String?): Map<String, String> {
            if (rawQuery.isNullOrBlank()) return emptyMap()
            val result = linkedMapOf<String, String>()
            rawQuery.split('&').forEach { part ->
                if (part.isBlank()) return@forEach
                val separator = part.indexOf('=')
                val rawKey = if (separator >= 0) part.substring(0, separator) else part
                val rawValue = if (separator >= 0) part.substring(separator + 1) else ""
                val key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
                val value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                result[key] = value
            }
            return result
        }

        internal fun jsonEscape(value: String): String {
            val out = StringBuilder(value.length + 16)
            value.forEach { char ->
                when (char) {
                    '\\' -> out.append("\\\\")
                    '"' -> out.append("\\\"")
                    '\b' -> out.append("\\b")
                    '\u000C' -> out.append("\\f")
                    '\n' -> out.append("\\n")
                    '\r' -> out.append("\\r")
                    '\t' -> out.append("\\t")
                    else -> if (char.code < 0x20) {
                        out.append(String.format(Locale.US, "\\u%04x", char.code))
                    } else {
                        out.append(char)
                    }
                }
            }
            return out.toString()
        }

        private fun constantTimeEquals(left: String?, right: String): Boolean {
            if (left == null) return false
            val a = left.toByteArray(StandardCharsets.UTF_8)
            val b = right.toByteArray(StandardCharsets.UTF_8)
            var diff = a.size xor b.size
            val max = maxOf(a.size, b.size)
            for (index in 0 until max) {
                val av = if (index < a.size) a[index].toInt() else 0
                val bv = if (index < b.size) b[index].toInt() else 0
                diff = diff or (av xor bv)
            }
            return diff == 0
        }

        private fun readAsciiLine(input: InputStream, maxBytes: Int): String? {
            val bytes = ArrayList<Byte>()
            var previousWasCr = false
            while (bytes.size <= maxBytes) {
                val value = input.read()
                if (value < 0) return if (bytes.isEmpty()) null else throw IOException("Unexpected EOF")
                if (previousWasCr) {
                    if (value == '\n'.code) {
                        return bytes.toByteArray().toString(StandardCharsets.US_ASCII)
                    }
                    bytes.add('\r'.code.toByte())
                    previousWasCr = false
                }
                if (value == '\r'.code) {
                    previousWasCr = true
                } else {
                    bytes.add(value.toByte())
                }
            }
            throw IOException("HTTP line too long")
        }

        private val PORTAL_HTML = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>PocketBridge</title>
              <style>
                :root{font-family:system-ui,-apple-system,sans-serif;color-scheme:light dark}
                body{max-width:760px;margin:0 auto;padding:28px 20px;background:#0b1020;color:#eef2ff}
                .card{background:#151c31;border:1px solid #2c3655;border-radius:18px;padding:20px;margin:16px 0}
                h1{margin:0 0 4px;font-size:2rem} h2{font-size:1rem;color:#a9b5d6}
                button,.pick{display:inline-block;border:0;border-radius:12px;padding:12px 16px;background:#5b7cfa;color:white;font-weight:700;cursor:pointer}
                input[type=file]{width:100%;margin:12px 0} .muted{color:#a9b5d6}.ok{color:#7ce3a1}.bad{color:#ff9a9a}
                .file{display:flex;justify-content:space-between;gap:12px;align-items:center;padding:10px 0;border-top:1px solid #2c3655}
                .file:first-child{border-top:0}.name{overflow-wrap:anywhere}.size{white-space:nowrap;color:#a9b5d6}
              </style>
            </head>
            <body>
              <h1>PocketBridge</h1>
              <div class="muted">Private local transfer over this phone's LAN connection.</div>
              <div id="auth" class="card">Checking session…</div>
              <div class="card">
                <h2>DROP TO PHONE · INBOX</h2>
                <input id="pick" type="file" multiple>
                <button onclick="uploadSelected()">Upload selected</button>
                <div id="uploadStatus" class="muted"></div>
              </div>
              <div class="card">
                <h2>SHARED FROM PHONE · READ ONLY</h2>
                <button onclick="refreshFiles()">Refresh</button>
                <div id="files" class="muted">Loading…</div>
              </div>
              <script>
                const token = location.hash.length > 1 ? location.hash.substring(1) : '';
                const auth = document.getElementById('auth');
                async function api(path, options) {
                  const opts = options || {};
                  const headers = new Headers(opts.headers || {});
                  headers.set('X-PocketBridge-Token', token);
                  opts.headers = headers;
                  const res = await fetch(path, opts);
                  if (!res.ok) throw new Error(await res.text());
                  return res;
                }
                function human(bytes) {
                  if (bytes < 1024) return bytes + ' B';
                  if (bytes < 1048576) return (bytes/1024).toFixed(1) + ' KB';
                  if (bytes < 1073741824) return (bytes/1048576).toFixed(1) + ' MB';
                  return (bytes/1073741824).toFixed(1) + ' GB';
                }
                async function refreshFiles() {
                  const box = document.getElementById('files');
                  try {
                    const files = await (await api('/api/shared')).json();
                    box.textContent = '';
                    if (!files.length) { box.textContent = 'Shared is empty.'; return; }
                    files.forEach(f => {
                      const row = document.createElement('div'); row.className='file';
                      const left = document.createElement('div'); left.className='name'; left.textContent=f.name;
                      const right = document.createElement('div');
                      const size = document.createElement('span'); size.className='size'; size.textContent=human(f.size) + '  ';
                      const button = document.createElement('button'); button.textContent='Download';
                      button.onclick=()=>{ location.href='/api/download?name='+encodeURIComponent(f.name)+'&t='+encodeURIComponent(token); };
                      right.append(size,button); row.append(left,right); box.append(row);
                    });
                  } catch(e) { box.textContent='Unable to load Shared.'; box.className='bad'; }
                }
                async function uploadSelected() {
                  const input = document.getElementById('pick');
                  const status = document.getElementById('uploadStatus');
                  if (!input.files.length) { status.textContent='Choose at least one file.'; return; }
                  let done=0;
                  for (const file of input.files) {
                    status.textContent='Uploading '+file.name+'…';
                    try {
                      await api('/api/inbox?name='+encodeURIComponent(file.name), {
                        method:'PUT', headers:{'Content-Type':file.type || 'application/octet-stream'}, body:file
                      });
                      done++;
                    } catch(e) { status.textContent='Upload failed: '+file.name; status.className='bad'; return; }
                  }
                  status.textContent='Uploaded '+done+' item(s) to Inbox.'; status.className='ok'; input.value='';
                }
                if (!token) {
                  auth.textContent='Open the PocketBridge connection link generated by the phone.'; auth.className='card bad';
                  document.getElementById('files').textContent='Session token required.';
                } else {
                  api('/api/status').then(()=>{auth.textContent='● Secure local session connected';auth.className='card ok';refreshFiles();})
                    .catch(()=>{auth.textContent='Session token is invalid or expired.';auth.className='card bad';});
                }
              </script>
            </body>
            </html>
        """.trimIndent()
    }
}
