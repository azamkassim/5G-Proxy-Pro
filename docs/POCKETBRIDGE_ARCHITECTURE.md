# PocketBridge Integrated Architecture

Status: **canonical design for the independent 5G Proxy Pro evolution**

PocketBridge is an independent product layer built on top of the existing 5G Proxy Pro networking core. It must remain useful without NEXUS. A future NEXUS integration may consume the same stable adapter/API surface available to any other approved external consumer.

## Product promise

**One tap turns the phone into a private personal edge hub:**

- 5G/4G Internet gateway via the existing SOCKS5 engine
- browser-based QuickDrop for devices with no client installed
- user-selected Pocket Drive / network folder
- SFTP exposure for network-storage clients
- Android Share Sheet inbox and Open With handoff
- clipboard/text/link bridge
- trusted-device and QR pairing
- transfer/activity history
- optional cloud/file-provider adapters
- optional developer automation recipes
- optional privileged enhancement through Shizuku
- versioned external-consumer API for future integrations

The normal user sees one lifecycle: **Start PocketBridge / Stop PocketBridge**. Internal modules may start and stop under that single session, but they must not create competing ownership of networking, storage, authentication, or lifecycle state.

## Hard architecture rules

1. **Independent from NEXUS.** No core class, storage schema, permission, service, or user flow may depend on NEXUS.
2. **One session owner.** The foreground-service lifecycle remains the single owner of a running PocketBridge session.
3. **LAN-only listeners by default.** Proxy, portal, file server and discovery listeners must never bind to cellular/public interfaces.
4. **Cellular-only egress for the gateway.** Existing `Network.bindSocket()` cellular egress behavior remains the canonical Internet-gateway path.
5. **No blanket app-data scraping.** PocketBridge integrates through Android-approved boundaries: Share Sheet, Open With, Storage Access Framework, document providers, explicit app APIs, and explicit user actions.
6. **No broad storage permission unless proven necessary.** Prefer persisted Storage Access Framework grants to user-selected folders.
7. **Sensitive apps are handoff-only.** Banking, payment, identity, health and government apps are never screen-scraped, credential-captured or remotely controlled by PocketBridge.
8. **No generic unauthenticated remote shell.** Developer integration uses explicit allow-listed recipes/commands.
9. **Adapters add capability, not ownership.** Adapters cannot own canonical session state, global credentials, or another adapter's data.
10. **Stop means stop.** Stopping PocketBridge closes every listener, invalidates session tokens and terminates temporary access.

## Integrated system model

```text
                         APPROVED EXTERNAL CONSUMERS
                    (future NEXUS adapter is one consumer)
                                  |
                         Versioned Adapter API
                                  |
+--------------------------------------------------------------------+
|                         POCKETBRIDGE APP                            |
|                                                                    |
|  +--------------------- PocketBridge Session -------------------+  |
|  |                                                            |  |
|  |  Gateway          QuickDrop        Pocket Drive             |  |
|  |  SOCKS5 :1080     Web :8080        SFTP :8022               |  |
|  |      |                 |                |                    |  |
|  |      +-----------------+----------------+                    |  |
|  |                        |                                     |  |
|  |                 Integration Fabric                           |  |
|  |                        |                                     |  |
|  |   +---------+----------+---------+----------+                |  |
|  |   |         |                    |          |                |  |
|  | Share     SAF /               Clipboard   Recipe             |  |
|  | Sheet     Providers             / Links    Runner             |  |
|  |                                                            |  |
|  +------------------------------------------------------------+  |
|                                                                    |
|  Security: LAN binding + session auth + trusted devices + audit    |
+--------------------------------------------------------------------+
           |                |                  |
        Wi-Fi            Hotspot              USB
           |                |                  |
       Laptop            Tablet           Other phone

Gateway egress: PocketBridge -> Android cellular Network -> 5G/4G
```

## Universal integration fabric

PocketBridge should maximize integration by implementing a small number of universal Android boundaries instead of hard-coding dozens of apps.

### 1. Android Share Sheet adapter

Accept `ACTION_SEND` and `ACTION_SEND_MULTIPLE` for files, text and links. This immediately integrates with scanners, PDF readers, photo apps, browsers, editors and many other apps.

Examples from the current device inventory that can benefit when they expose standard Android sharing:

- PDFgear Scan
- Adobe Acrobat
- PDF Reader / Librera / LibreOffice Viewer / OpenDocument Reader
- Photos
- Voice Recorder
- Samsung Notes
- Acode
- Edge / Samsung Browser
- ChatGPT / Grok / DeepSeek / Perplexity through explicit user handoff

### 2. Android Open With adapter

Expose selected PocketBridge content through `ACTION_VIEW` / content URIs so the user can open a file in the best installed app without duplicating editor functionality inside PocketBridge.

### 3. Storage Access Framework adapter

The user chooses a folder once. PocketBridge persists the grant and treats that folder as the Pocket Drive root. This is the default storage model.

Possible document providers include local storage, Drive, Nextcloud and other providers exposed by Android.

### 4. Browser portal adapter

Any trusted device on the same LAN can use QuickDrop without installing a PocketBridge client. Capabilities:

- upload to Inbox
- download from Shared
- send/receive text and links
- view transfer status
- obtain connection information
- pair using a short-lived QR/session token

### 5. SFTP Pocket Drive adapter

Expose the user-selected Pocket Drive to network-storage clients. Start with SFTP rather than SMB because it is simpler to isolate, authenticate and operate on unrooted Android.

### 6. Clipboard / text board adapter

Use an explicit PocketBridge board rather than continuously spying on the Android clipboard. The user chooses `Send to PocketBridge`, and trusted clients may retrieve that item during the session.

### 7. Developer recipe adapter

Termux, Acode, ConsoleFlow, GitHub and GitLab workflows can participate through explicit recipes such as:

- project status
- run tests
- build project
- collect logs
- export diagnostics
- stage an artifact into PocketBridge Shared

Recipes are allow-listed, parameterized and auditable. PocketBridge must not expose an open shell.

### 8. Cloud/document-provider adapter

Cloud integration is asynchronous and optional. Local transfer never waits for cloud upload. Nextcloud is the first preferred explicit cloud/file adapter; Google Drive can participate primarily through Android document-provider boundaries unless a dedicated approved API integration is later justified.

### 9. Shizuku enhancement adapter

Optional only. It may enhance diagnostics or system integration where Android permits, but no essential PocketBridge feature may depend on Shizuku.

### 10. External consumer adapter

A stable, versioned API allows another approved application to request narrowly scoped PocketBridge actions. The core knows nothing about the consumer's business domain. A future NEXUS adapter can use this contract without coupling PocketBridge to NEXUS.

## Device/app integration tiers

### Tier A - automatic universal interoperability

No app-specific code required:

- Android Share Sheet
- Open With
- Storage Access Framework
- browser portal
- SFTP network storage
- QR/session links

### Tier B - explicit app adapters

Build only when they add a distinct capability that universal Android mechanisms cannot provide:

- Nextcloud: cloud mirror / remote file operations
- Termux: allow-listed local automation recipes
- Acode: developer-file/project handoff
- ConsoleFlow: controlled command/diagnostic recipes
- Shizuku: optional privileged enhancement

### Tier C - handoff-only applications

AI assistants, chat/messaging applications and productivity tools may receive explicitly selected files/text/links, but PocketBridge does not silently ingest their private data.

### Tier D - protected applications

Banking, payments, identity, health and sensitive government applications are excluded from autonomous automation. Examples in the current inventory include MAE, TNG eWallet, Samsung Wallet, MyDigital ID and MySejahtera. Explicit user-controlled Android sharing remains the only permitted path if the source application itself supports it.

## Single-session lifecycle

```text
STOPPED
  |
  | Start PocketBridge
  v
PREPARING
  |- validate LAN interface
  |- acquire cellular network for gateway
  |- load persisted user folder grants
  |- derive/obtain session credentials
  |- build enabled adapter set
  v
RUNNING
  |- SOCKS5 gateway
  |- QuickDrop portal
  |- optional SFTP Pocket Drive
  |- share inbox
  |- device/trust manager
  |- activity log
  v
DEGRADED
  |- one optional module may fail without falsely reporting total success
  |- gateway or security-boundary failure can fail the session closed
  v
STOPPING
  |- refuse new sessions
  |- close listeners
  |- revoke short-lived tokens
  |- drain/cancel transfers according to policy
  v
STOPPED
```

Every module reports actual state to the session owner. The UI never infers a service is running from a button press alone.

## Default ports

- SOCKS5 gateway: `1080`
- QuickDrop HTTP during early development: `8080`
- SFTP Pocket Drive: `8022`

Ports are configurable, cannot collide, and are validated before listeners start. Production browser access should move to authenticated TLS where practical; cleartext development access must remain LAN-only.

## Authentication and pairing

Default design:

- SOCKS5 credentials continue to use Android Keystore-protected persistence.
- PocketBridge generates a separate high-entropy session secret for web/file access.
- QR payload contains LAN address, service metadata and a short-lived pairing token, not a long-lived master secret.
- A trusted-device record stores a device public-key/fingerprint or equivalent durable identifier, not reusable plaintext credentials.
- Guest pairing expires automatically.
- New devices receive the minimum scope: download-only or upload-only unless the user grants more.

## File permission model

Use capability-scoped roots rather than one unrestricted filesystem view:

```text
PocketBridge root (user-selected SAF tree)
|- Inbox/        receiver writes; remote clients cannot browse other app data
|- Shared/       explicitly published files; read-only by default to clients
|- Exchange/     temporary two-way collaboration area
|- Projects/     opt-in developer integration
|- Archive/      optional local history / completed transfers
```

Recommended remote scopes:

- `DROP_ONLY`: may upload into Inbox, cannot list unrelated files
- `READ_SHARED`: may list/download Shared only
- `EXCHANGE`: limited read/write in Exchange
- `DEVELOPER`: explicit project/recipe scope
- `ADMIN`: reserved for the phone owner

## Integration event model

All modules communicate through typed internal events instead of direct cross-module calls where practical:

```text
SessionStarted
SessionStopped
DevicePaired
DeviceRevoked
ShareItemReceived
TransferQueued
TransferStarted
TransferCompleted
TransferFailed
SharedItemPublished
RecipeRequested
RecipeCompleted
AdapterStateChanged
SecurityBoundaryViolation
```

Events contain identifiers and metadata, not unnecessary file contents or credentials.

## Adapter contract

Each adapter declares:

- stable ID
- display name
- capabilities
- dependency type
- package names, when applicable
- trust level
- whether it is safe to enable by default

An adapter may expose operations only through the capability contract. It cannot reach into another adapter's private state.

## Anti-redundancy rules

PocketBridge must not become a collection of cloned apps:

- do not build a PDF editor; hand off to Acrobat/Librera/etc.
- do not build a code editor; hand off to Acode
- do not build a cloud drive; mirror through Nextcloud/provider adapters
- do not build a chat client; share to WhatsApp/Telegram/Slack
- do not build an AI model hub; explicitly hand selected content to AI apps/providers
- do not build a full terminal; execute allow-listed Termux recipes
- do not create a second proxy engine; preserve the existing canonical native SOCKS5 engine

## Delivery sequence

### Foundation (this branch)

- canonical architecture document
- typed capability model
- adapter descriptors and registry
- security-policy defaults
- unit tests for uniqueness, NEXUS independence and secure defaults

### v0.1 - One-tap local share

- PocketBridge session coordinator around the existing proxy lifecycle
- SAF-selected Pocket Drive root
- Android Share Sheet receiver
- QuickDrop LAN portal
- per-session auth
- stop-all semantics and unified diagnostics

### v0.2 - Pocket Drive

- SFTP listener bound only to LAN addresses
- QR pairing
- trusted devices
- upload-only / read-shared scopes
- transfer history

### v0.3 - Cross-device convenience

- PocketBoard for text/links
- Open With/content-provider handoff
- improved browser UX
- device revocation

### v0.4 - Automation adapters

- Nextcloud
- Termux allow-listed recipes
- Acode/ConsoleFlow project handoff
- optional Shizuku enhancements

### v1.0 - Stable integration platform

- versioned external-consumer API
- adapter compatibility contract
- hardened TLS/pairing
- permission/audit model
- documented future-consumer adapter path, including a possible NEXUS adapter without core dependency

## Definition of 'most integrated'

PocketBridge is considered maximally integrated when it can interoperate with the broadest useful set of apps and devices **through a small, stable set of secure universal interfaces**, rather than by acquiring invasive permissions or implementing brittle app-specific automation.
