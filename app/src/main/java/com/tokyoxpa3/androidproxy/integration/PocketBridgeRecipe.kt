package com.tokyoxpa3.androidproxy.integration

/**
 * Event sources that may start a PocketBridge recipe. Runtime code must still
 * apply user/session authorization before executing any action.
 */
enum class PocketBridgeRecipeTrigger {
    MANUAL,
    SESSION_STARTED,
    SHARE_RECEIVED,
    FILE_UPLOADED,
    DEVICE_PAIRED,
    TRANSFER_COMPLETED
}

data class PocketBridgeRecipeFilter(
    val mimeTypes: Set<String> = emptySet(),
    val fileExtensions: Set<String> = emptySet(),
    val sourceAdapterIds: Set<String> = emptySet()
)

/**
 * Deliberately contains no arbitrary shell-command action.
 * Developer automation references an allow-listed recipe ID owned by an
 * adapter (for example Termux), keeping executable policy outside user input.
 */
sealed class PocketBridgeRecipeAction {
    data object PublishToShared : PocketBridgeRecipeAction()
    data class MirrorToAdapter(val adapterId: String) : PocketBridgeRecipeAction()
    data class HandoffToPackage(val packageName: String) : PocketBridgeRecipeAction()
    data class RunAllowListedRecipe(
        val adapterId: String,
        val recipeId: String,
        val parameters: Map<String, String> = emptyMap()
    ) : PocketBridgeRecipeAction()
    data class NotifyOwner(val message: String) : PocketBridgeRecipeAction()
}

data class PocketBridgeRecipe(
    val id: String,
    val displayName: String,
    val trigger: PocketBridgeRecipeTrigger,
    val filter: PocketBridgeRecipeFilter = PocketBridgeRecipeFilter(),
    val actions: List<PocketBridgeRecipeAction>,
    val enabled: Boolean = true
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9._-]*"))) { "Invalid recipe id: $id" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(actions.isNotEmpty()) { "Recipe $id must contain at least one action" }
    }
}
