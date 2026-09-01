package com.tokyoxpa3.androidproxy.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketBridgeRecipeTest {

    @Test
    fun `cross app recipe composes handoff mirror and allow listed automation`() {
        val recipe = PocketBridgeRecipe(
            id = "pdf.project-flow",
            displayName = "PDF project flow",
            trigger = PocketBridgeRecipeTrigger.SHARE_RECEIVED,
            filter = PocketBridgeRecipeFilter(mimeTypes = setOf("application/pdf")),
            actions = listOf(
                PocketBridgeRecipeAction.PublishToShared,
                PocketBridgeRecipeAction.MirrorToAdapter("app.nextcloud"),
                PocketBridgeRecipeAction.RunAllowListedRecipe(
                    adapterId = "app.termux",
                    recipeId = "project.index"
                )
            )
        )

        assertTrue(recipe.enabled)
        assertEquals(3, recipe.actions.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `empty action recipe is rejected`() {
        PocketBridgeRecipe(
            id = "invalid.empty",
            displayName = "Invalid",
            trigger = PocketBridgeRecipeTrigger.MANUAL,
            actions = emptyList()
        )
    }
}
