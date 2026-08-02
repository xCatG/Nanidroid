"""Structural contract for the Kotlin Activity migration.

This deliberately verifies seams rather than attempting to unit-test framework
lifecycles with default-return Android stubs.  The device lane owns runtime
rendering; these assertions lock down the compatibility obligations of this PR.
"""

import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class KotlinNanidroidActivityContractTest(unittest.TestCase):
    def setUp(self):
        self.source = (
            ROOT / "src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.kt"
        ).read_text(encoding="utf-8")

    def test_gradle_build_uses_kotlin_while_ant_keeps_the_frozen_java_activity(self):
        self.assertFalse((ROOT / "src/main/kotlin/com/cattailsw/nanidroid/Nanidroid.java").exists())
        self.assertFalse((ROOT / "legacy").exists())
        self.assertIn("class Nanidroid : ComponentActivity()", self.source)
        self.assertIn("setContent {", self.source)
        self.assertIn("NanidroidComposeShell(", self.source)

    def test_compose_stage_and_dialog_callback_names_remain_public(self):
        for callback in (
            "fun onNextSurface()",
            "fun onAnimate()",
            "fun onShowCollision()",
            "fun runClick()",
            "fun narTest()",
            "fun onUpdate()",
            "fun onListGhost()",
            "fun onHelp()",
            "fun onSetupClick()",
            "fun frameClick()",
            "override fun showUserInputBox(id: String)",
            "override fun showUserSelection(textlabel: Array<String>, ids: Array<String>)",
        ):
            self.assertIn(callback, self.source)
        self.assertIn("private val composeStage = ComposeGhostStageHost(", self.source)
        self.assertIn("ghostStage = { composeStage.Stage(onSurfaceTap = ::frameClick) },", self.source)
        self.assertIn("runner!!.setPresentationRenderer(composeStage.renderer)", self.source)
        self.assertIn("runner!!.setUICallback(this@Nanidroid)", self.source)
        self.assertNotIn("SakuraView(this)", self.source)
        self.assertNotIn("KeroView(this)", self.source)
        self.assertNotIn("Balloon(this)", self.source)
        self.assertNotIn("FrameLayout(this)", self.source)

    def test_compose_toolbar_stays_below_system_status_bars(self):
        shell = (
            ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidComposeShell.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("import androidx.compose.foundation.layout.statusBarsPadding", shell)
        self.assertIn("Column(modifier = Modifier.statusBarsPadding())", shell)

    def test_archive_inputs_use_the_durable_queue_not_the_exported_https_route(self):
        self.assertIn("ArchiveIntentAdapter.contentUri(incoming,", self.source)
        self.assertIn("narDownloads.enqueueRemote(value)", self.source)
        self.assertIn("narDownloads.enqueueLocal(result.location, result.location)", self.source)
        self.assertNotIn("IncomingNarIntent", self.source)

    def test_picker_import_uses_one_shot_content_uri_staging_with_support_activity_dispatch(self):
        self.assertIn("class Nanidroid : ComponentActivity()", self.source)
        self.assertIn("Intent.ACTION_OPEN_DOCUMENT", self.source)
        self.assertIn("Intent.CATEGORY_OPENABLE", self.source)
        self.assertIn('type = "*/*"', self.source)
        self.assertNotIn("Intent.EXTRA_MIME_TYPES", self.source)
        self.assertIn("startActivityForResult(", self.source)
        self.assertIn("override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?)", self.source)
        self.assertIn("NarLocalArchiveStager.stage", self.source)
        self.assertIn("takePersistableUriPermission", self.source)
        self.assertIn("outState.putBoolean(NAR_PICK_PENDING, awaitingNarDocument)", self.source)
        self.assertIn("private fun importPickedNar(uri: Uri, replacementId: String?)", self.source)
        self.assertIn("val replacing = replacementId ?: pendingCopyId", self.source)
        self.assertIn("val replacementId = replacingNarDownloadId", self.source)
        self.assertNotIn("registerForActivityResult", self.source)

    def test_compose_dialog_state_is_saved_and_restored_across_recreation(self):
        self.assertIn("override fun onSaveInstanceState(outState: Bundle)", self.source)
        self.assertIn("saveSimpleDialog(outState)", self.source)
        self.assertIn("restoreSimpleDialog(savedInstanceState)", self.source)
        self.assertIn("DIALOG_HELP_MENU -> createHelpMenuDialog()", self.source)
        self.assertIn("DIALOG_GENERAL_HELP -> createGeneralHelpDialog()", self.source)
        self.assertIn("DIALOG_MORE_GHOST -> createMoreGhostDialog()", self.source)
        self.assertIn("DIALOG_URL_ENTRY -> createUrlEntryDialog(", self.source)
        self.assertIn("DIALOG_USER_INPUT -> createUserInputDialog(", self.source)
        self.assertIn("DIALOG_USER_CHOICE -> createUserChoiceDialog(", self.source)
        self.assertIn("DIALOG_GHOST_LIST -> createGhostListDialog(", self.source)
        self.assertIn("is NanidroidSimpleDialog.DebugMessage -> Unit", self.source)
        self.assertNotIn("DIALOG_DEBUG", self.source)

    def test_operational_dialogs_are_compose_state_not_fragments_or_array_adapters(self):
        dialog_source = (
            ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/NanidroidSimpleDialogs.kt"
        ).read_text(encoding="utf-8")
        for name in (
            "EnterUrlDlg.kt",
            "UserInputDlg.kt",
            "UserSelectDlg.kt",
            "GhostListDialogFragment.kt",
            "DbgMsgDlg.kt",
            "ErrMsgDlg.kt",
            "HelpFuncDlg.kt",
            "MoreGhostFuncDlg.kt",
            "NarPickDlg.kt",
            "NotImplementedDlg.kt",
        ):
            self.assertFalse((ROOT / "src/main/kotlin/com/cattailsw/nanidroid/dlgs" / name).exists())
        self.assertIn("data class UrlEntry", dialog_source)
        self.assertIn("KeyboardType.Uri", dialog_source)
        self.assertIn("ImeAction.Done", dialog_source)
        self.assertIn("data class UserInput", dialog_source)
        self.assertIn("data class UserChoice", dialog_source)
        self.assertIn("data class GhostList", dialog_source)
        self.assertIn("url-validation-error", dialog_source)
        self.assertNotIn("ArrayAdapter", self.source)
        self.assertNotIn("registerForContextMenu", self.source)
        self.assertNotIn("onCreateContextMenu", self.source)
        self.assertNotIn("onContextItemSelected", self.source)
        self.assertFalse((ROOT / "src/main/res/layout/dbgdlg.xml").exists())
        self.assertFalse((ROOT / "src/main/res/menu/main_help_menu.xml").exists())
        self.assertFalse((ROOT / "src/main/res/values/arrays.xml").exists())

    def test_compose_stage_preserves_legacy_interaction_and_animation_lifecycle(self):
        stage = (
            ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt"
        ).read_text(encoding="utf-8")
        runner = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/SScriptRunner.kt").read_text(
            encoding="utf-8"
        )
        # SakuraView dispatched every physical tap as OnMouseDoubleClick; the
        # Compose boundary must keep that desktop-ghost-shell compatibility.
        self.assertIn("onTap = { position ->", stage)
        self.assertIn("SurfacePointerInteractionDispatcher(interactionPort).dispatch(resolution)", stage)
        # The runtime—not a short-lived Activity host—owns its shared talk gate.
        self.assertIn("transition.state.talkingAnimationEnabled", stage)
        # Asset decoding and scheduler work must stop when the stage is paused.
        self.assertIn("activeRenderedImages", stage)
        self.assertIn("renderedFrames[key]?.let", stage)
        self.assertIn("MAX_CACHED_FRAME_PIXELS", stage)
        self.assertIn("LifecycleEventObserver", stage)
        self.assertIn("if (!stageStarted) return@LaunchedEffect", stage)
        # Legacy Kero input clears queued script before sending the mouse event.
        self.assertIn("if (!sakura) clearMsgQueue()", runner)


if __name__ == "__main__":
    unittest.main()
