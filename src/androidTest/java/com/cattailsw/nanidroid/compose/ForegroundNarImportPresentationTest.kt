package com.cattailsw.nanidroid.compose

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.cattailsw.nanidroid.ForegroundCatalogRecovery
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.install.ArchiveInstallFailure
import com.cattailsw.nanidroid.install.ForegroundNarImportState
import com.cattailsw.nanidroid.install.NarImportAttemptToken
import com.cattailsw.nanidroid.install.NarImportPrimaryOutcome
import com.cattailsw.nanidroid.runtime.CatalogPublicationToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ForegroundNarImportPresentationTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun progressStatesBlockPointersAndDescribeTheirCurrentPhase() {
        val token = NarImportAttemptToken("process", 7)
        var behindOverlayClicks = 0
        val states = listOf(
            ForegroundNarImportState.Copying(token) to "Copying selected archive…",
            ForegroundNarImportState.Installing(token, "unpacking", 32L) to "Installing selected ghost…",
            ForegroundNarImportState.Cleaning(token, NarImportPrimaryOutcome.Interrupted) to "Cleaning private staging…",
        )
        val currentState = mutableStateOf(states.first().first)

        rule.setContent {
            Box {
                Button(
                    onClick = { behindOverlayClicks++ },
                    modifier = Modifier.testTag("behind-import-overlay"),
                ) { Text("behind") }
                ForegroundNarImportPresentation(
                    state = currentState.value,
                    installedReadyToken = null,
                    onAcknowledge = {},
                    onSelectAnother = {},
                    onRetryCleanup = {},
                )
            }
        }

        states.forEach { (state, message) ->
            rule.runOnIdle { currentState.value = state }
            rule.onNodeWithTag("nar-import-progress-overlay").assertIsDisplayed()
            rule.onNodeWithText(message).assertIsDisplayed()
            val behindCenter = rule.onNodeWithTag("behind-import-overlay").fetchSemanticsNode().boundsInRoot.center
            rule.onRoot().performTouchInput { click(Offset(behindCenter.x, behindCenter.y)) }
            rule.runOnIdle { assertEquals(0, behindOverlayClicks) }
        }
    }

    @Test
    fun installedWaitsForMatchingReadyTokenAndNeverOffersAutoSwitch() {
        val token = NarImportAttemptToken("process", 7)
        val readyToken = mutableStateOf<NarImportAttemptToken?>(null)
        var acknowledged: NarImportAttemptToken? = null

        rule.setContent {
            ForegroundNarImportPresentation(
                state = ForegroundNarImportState.Installed(token, "/ghosts/example", "example"),
                installedReadyToken = readyToken.value,
                onAcknowledge = { acknowledged = it },
                onSelectAnother = {},
                onRetryCleanup = {},
            )
        }

        rule.onNodeWithText("Refreshing installed ghosts…").assertIsDisplayed()
        rule.onNodeWithText("Ghost installed").assertDoesNotExist()
        rule.onNodeWithTag("nar-import-acknowledge").assertDoesNotExist()

        rule.runOnIdle { readyToken.value = token }

        rule.onNodeWithText("Ghost installed").assertIsDisplayed()
        rule.onNodeWithText("The ghost was installed and is now available in the ghost list.").assertIsDisplayed()
        rule.onNodeWithTag("nar-import-select-another").assertDoesNotExist()
        rule.onNodeWithTag("nar-import-acknowledge").performClick()
        rule.runOnIdle { assertEquals(token, acknowledged) }
    }

    @Test
    fun installedCatalogRecoveryShowsExactRetryAboveRefreshingPresentation() {
        val token = NarImportAttemptToken("process", 17, 4)
        val recovery = ForegroundCatalogRecovery(
            token,
            CatalogPublicationToken("foreground-import", "process:17:4"),
            failedEpoch = 31L,
        )
        var retried: ForegroundCatalogRecovery? = null

        rule.setContent {
            ForegroundNarImportPresentation(
                state = ForegroundNarImportState.Installed(token, "/ghosts/example", "example"),
                installedReadyToken = null,
                catalogRecovery = recovery,
                onAcknowledge = {},
                onSelectAnother = {},
                onRetryCleanup = {},
                onRetryCatalog = { retried = it },
            )
        }

        rule.onNodeWithTag("nar-import-progress-overlay").assertDoesNotExist()
        rule.onNodeWithText("No ghost is currently available. Install a ghost archive to continue.").assertIsDisplayed()
        rule.onNodeWithTag("nar-import-retry-catalog").performClick()
        rule.runOnIdle { assertEquals(recovery, retried) }
    }

    @Test
    fun failedImportOffersMatchingTokenDismissAndReselect() {
        val token = NarImportAttemptToken("process", 7)
        var action: Pair<String, NarImportAttemptToken>? = null

        rule.setContent {
            ForegroundNarImportPresentation(
                state = ForegroundNarImportState.Failed(
                    token,
                    "This ghost archive is invalid.",
                    ArchiveInstallFailure.InvalidArchive,
                ),
                installedReadyToken = null,
                onAcknowledge = { action = "dismiss" to it },
                onSelectAnother = { action = "select" to it },
                onRetryCleanup = { action = "cleanup" to it },
            )
        }

        rule.onNodeWithText("Couldn’t install ghost").assertIsDisplayed()
        rule.onNodeWithText("This ghost archive is invalid.").assertIsDisplayed()
        rule.onNodeWithTag("nar-import-acknowledge").performClick()
        rule.runOnIdle { assertEquals("dismiss" to token, action) }
        rule.onNodeWithTag("nar-import-select-another").performClick()
        rule.runOnIdle { assertEquals("select" to token, action) }
    }

    @Test
    fun interruptedTerminalIsReplayableAndCapturesItsRenderedToken() {
        val firstToken = NarImportAttemptToken("process", 7)
        val secondToken = NarImportAttemptToken("process", 8)
        val state = mutableStateOf<ForegroundNarImportState?>(ForegroundNarImportState.Interrupted(firstToken))
        var acknowledged: NarImportAttemptToken? = null

        rule.setContent {
            state.value?.let {
                ForegroundNarImportPresentation(
                    state = it,
                    installedReadyToken = null,
                    onAcknowledge = { token -> acknowledged = token },
                    onSelectAnother = {},
                    onRetryCleanup = {},
                )
            }
        }

        rule.onNodeWithText("Import interrupted").assertIsDisplayed()
        rule.runOnIdle { state.value = ForegroundNarImportState.Interrupted(secondToken) }
        rule.onNodeWithTag("nar-import-acknowledge").performClick()
        rule.runOnIdle { assertEquals(secondToken, acknowledged) }

        rule.runOnIdle { state.value = null }
        rule.onNodeWithText("Import interrupted").assertDoesNotExist()
        rule.runOnIdle { state.value = ForegroundNarImportState.Interrupted(firstToken) }
        rule.onNodeWithText("Import interrupted").assertIsDisplayed()
    }

    @Test
    fun installedPrimaryRecoveryPreservesTruthAndOffersOnlyCleanupRetry() {
        val token = NarImportAttemptToken("process", 7)
        var retryToken: NarImportAttemptToken? = null

        rule.setContent {
            ForegroundNarImportPresentation(
                state = ForegroundNarImportState.RecoveryRequired(
                    token = token,
                    primary = NarImportPrimaryOutcome.Installed("/ghosts/example", "example"),
                    message = "Private staging could not be removed.",
                ),
                installedReadyToken = null,
                onAcknowledge = {},
                onSelectAnother = {},
                onRetryCleanup = { retryToken = it },
            )
        }

        rule.onNodeWithText("Import cleanup needs attention").assertIsDisplayed()
        rule.onNodeWithText("Private staging could not be removed.").assertIsDisplayed()
        rule.onNodeWithText("The ghost was installed and is preserved. Only cleanup will be retried.").assertIsDisplayed()
        rule.onNodeWithTag("nar-import-retry-cleanup").performClick()
        rule.onNodeWithTag("nar-import-acknowledge").assertDoesNotExist()
        rule.onNodeWithTag("nar-import-select-another").assertDoesNotExist()
        rule.runOnIdle {
            assertEquals(token, retryToken)
            assertFalse(retryToken == null)
        }
    }

    // Mutation caught: cleanup recovery obscures the independently actionable exact catalog retry.
    @Test
    fun installedPrimaryRecoveryExposesIndependentCleanupAndCatalogRetries() {
        val token = NarImportAttemptToken("process", 23L, 9)
        val recovery = ForegroundCatalogRecovery(
            token,
            CatalogPublicationToken("foreground-import", "process:23:9"),
            failedEpoch = 37L,
        )
        var cleanupRetry: NarImportAttemptToken? = null
        var catalogRetry: ForegroundCatalogRecovery? = null

        rule.setContent {
            ForegroundNarImportPresentation(
                state = ForegroundNarImportState.RecoveryRequired(
                    token = token,
                    primary = NarImportPrimaryOutcome.Installed("/ghosts/example", "example"),
                    message = "Private staging could not be removed.",
                ),
                installedReadyToken = null,
                catalogRecovery = recovery,
                onAcknowledge = {},
                onSelectAnother = {},
                onRetryCleanup = { cleanupRetry = it },
                onRetryCatalog = { catalogRetry = it },
            )
        }

        rule.onNodeWithTag("nar-import-retry-cleanup").assertIsDisplayed().performClick()
        rule.onNodeWithTag("nar-import-retry-catalog").assertIsDisplayed().performClick()
        rule.runOnIdle {
            assertEquals(token, cleanupRetry)
            assertEquals(recovery, catalogRetry)
        }
    }

    @Test
    fun installedPrimaryRecoveryUsesJapanesePreservationStatement() = assertLocalizedInstalledRecovery(
        locale = Locale.JAPANESE,
        expected = "ゴーストはインストール済みで保持されています。再試行されるのはプライベートステージングのクリーンアップのみです。",
    )

    @Test
    fun installedPrimaryRecoveryUsesTraditionalChinesePreservationStatement() = assertLocalizedInstalledRecovery(
        locale = Locale.TAIWAN,
        expected = "偽人格仍會保持已安裝狀態。只會重試私人暫存區的清理。",
    )

    private fun assertLocalizedInstalledRecovery(locale: Locale, expected: String) {
        val localized = rule.activity.createConfigurationContext(
            Configuration(rule.activity.resources.configuration).apply {
                setLocales(LocaleList(locale))
            },
        )

        rule.setContent {
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides localized.resources.configuration,
            ) {
                ForegroundNarImportPresentation(
                    state = ForegroundNarImportState.RecoveryRequired(
                        token = NarImportAttemptToken("process", 7),
                        primary = NarImportPrimaryOutcome.Installed("/ghosts/example", "example"),
                        message = "Private staging could not be removed.",
                    ),
                    installedReadyToken = null,
                    onAcknowledge = {},
                    onSelectAnother = {},
                    onRetryCleanup = {},
                )
            }
        }

        assertEquals(expected, localized.getString(R.string.nar_import_recovery_installed_message))
        assertNotEquals(RECOVERY_INSTALLED_MESSAGE_ENGLISH, expected)
        rule.onNodeWithText(expected).assertIsDisplayed()
        rule.onNodeWithText(RECOVERY_INSTALLED_MESSAGE_ENGLISH).assertDoesNotExist()
    }

    private companion object {
        const val RECOVERY_INSTALLED_MESSAGE_ENGLISH =
            "The ghost was installed and is preserved. Only cleanup will be retried."
    }
}
