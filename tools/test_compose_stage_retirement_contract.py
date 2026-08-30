import pathlib
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[1]


class ComposeStageRetirementContractTest(unittest.TestCase):
    def test_retained_stage_views_and_renderer_are_absent(self):
        for name in (
            "SakuraView.kt",
            "KeroView.kt",
            "Balloon.kt",
            "LayoutManager.kt",
            "LegacyGhostPresentationRenderer.kt",
        ):
            self.assertFalse((ROOT / "src/main/kotlin/com/cattailsw/nanidroid" / name).exists())

    def test_runner_and_parallel_presentation_authorities_are_absent(self):
        package = ROOT / "src/main/kotlin/com/cattailsw/nanidroid"
        for relative in (
            "SScriptRunner.kt",
            "GhostMgr.kt",
            "GhostPresentationRenderer.kt",
            "GhostPresentationFrame.kt",
            "BootDispatchState.kt",
            "runtime/GhostStageLayout.kt",
            "runtime/SakuraScriptInteractionEffects.kt",
            "runtime/SakuraScriptPresentationState.kt",
            "runtime/SakuraScriptPresentationInterpreter.kt",
            "runtime/GhostPresentationState.kt",
            "runtime/KotlinGhostPresentationRuntime.kt",
        ):
            with self.subTest(relative=relative):
                self.assertFalse((package / relative).exists())

    def test_compose_stage_stack_owns_geometry_images_pointer_routing_and_balloons(self):
        stage = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/GhostPresentationStage.kt").read_text(
            encoding="utf-8"
        )
        measured_layout = (
            ROOT
            / "src/main/kotlin/com/cattailsw/nanidroid/compose/stage/MeasuredGhostStageLayout.kt"
        ).read_text(encoding="utf-8")
        bubble = (
            ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/stage/GhostBubble.kt"
        ).read_text(encoding="utf-8")
        host = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/ComposeGhostStageHost.kt").read_text(
            encoding="utf-8"
        )
        scheduler = (ROOT / "src/main/kotlin/com/cattailsw/nanidroid/compose/SurfaceAnimationScheduler.kt").read_text(
            encoding="utf-8"
        )
        self.assertIn("MeasuredGhostStageLayout(", stage)
        self.assertIn("GhostBubble(", stage)
        self.assertIn("GhostStageLayoutPolicy.calculate(", measured_layout)
        self.assertIn("state.content.segments.forEachIndexed", bubble)
        self.assertIn("TextButton(", bubble)
        self.assertIn("BubbleInteractionTarget.ExternalUrl", bubble)
        self.assertIn("SurfaceCompositor(pixelAssets, SurfacePlanRegistry(plans))", host)
        self.assertIn("snapshot: RuntimeSnapshot", host)
        self.assertIn("hostLease: RuntimeHostLease", host)
        self.assertIn("RuntimeCommand.AcknowledgeCues", host)
        self.assertNotIn("runtimeState", host)
        self.assertNotIn("dialogueState", host)
        self.assertNotIn("KotlinGhostPresentationRuntime", host)
        self.assertIn("SurfaceAnimationScheduler", host)
        self.assertIn("talk begins on the first update and then every tenth update", scheduler)

    def test_obsolete_tests_have_exact_migrated_replacements(self):
        replacements = {
            "src/test/java/com/cattailsw/nanidroid/GhostRuntimePlaybackTest.kt": (
                "attachmentSelectsExactlyOneFirstBootGhostChangedOrBootEvent",
                "authoredPlaybackContinuesWhileClockOwnerIsAbsent",
                "blockedTimerResponseCannotEnterAfterClockEpochChanges",
                "switchPlaybackOwnsOutgoingResponseBeforeUnload",
                "equalAnimationIdsFromSeparateCommandsAreSeparateRenderCalls",
                "foregroundImportRefreshCannotPublishPreCommitCatalogScan",
            ),
            "src/test/java/com/cattailsw/nanidroid/GhostRuntimeDialogueTest.kt": (
                "pendingInputRestoresOnlyAgainstSameDialogueIncarnationAndGeneration",
            ),
            "src/test/java/com/cattailsw/nanidroid/GhostRuntimeHostTest.kt": (
                "repeatedBackJoinsOneExitOperation",
            ),
            "src/test/java/com/cattailsw/nanidroid/runtime/SakuraScriptPlayerTest.kt": (
                "speakerTextSurfaceAndAnimationHaveOrderedTransition",
                "newlineModifierAndClearHaveOrderedTextStates",
                "quickSessionEmitsOneWholeLineTransition",
                "distinctSurfaceTransitionsAndAnimationCuesAreOrdered",
                "choicesPublishThenLabelsContinueAsText",
                "unsupportedTagsAreConsumedNotRendered",
                "animationCueAppearsOnlyWhenPlayerSchedulesIt",
                "choicesBecomeLabelsAndOneOrderedAction",
                "inputBoxIsConsumedAndRetainsStableActionId",
                "scriptWithoutInteractionsRemainsUntouched",
                "inputBoxesParseIndividually",
                "inputAndChoiceActionsKeepSourceOrder",
                "scriptResetKeepsSurfacesAndClearsTransientPresentation",
                "synchronizationAndKeroTextPreserveBalloonPolicy",
                "reselectingCurrentSpeakerRetainsText",
                "animationBecomesOneLeaseScopedCue",
                "textSurfaceAnimationAndStopMatchOrderedTransitions",
                "repeatedSpeakerAndNewlineKeepVisibleText",
                "explicitAnimationSuppressesTalkingCueAndKeepsBalloonText",
                "missingSurfaceIdIsRejectedAtPlayerBoundary",
            ),
            "src/test/java/com/cattailsw/nanidroid/GhostRuntimeSnapshotTest.kt": (
                "runtimeInstancesCannotConsumeEachOthersPlayerQueues",
                "runtimeHasNoStaticMutableQueuePlayerHostOrCatalogState",
                "snapshotAndCuesPreserveLegacyEffectOrder",
            ),
            "src/test/java/com/cattailsw/nanidroid/runtime/RuntimeSnapshotTest.kt": (
                "dialogueActionCollectionsRejectMutation",
                "presentationPreservesSpeakerTextSurfaceCueAndBalloonPolicy",
                "emptyTextAndDisabledBalloonRemainHidden",
                "snapshotGraphContainsNoViewOrCallback",
            ),
        }
        for relative, method_ids in replacements.items():
            source = (ROOT / relative).read_text(encoding="utf-8")
            for method_id in method_ids:
                with self.subTest(relative=relative, method_id=method_id):
                    self.assertIn(f"fun {method_id}(", source)


if __name__ == "__main__":
    unittest.main()
