package com.cattailsw.nanidroid.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/** Locks down the first Activity extraction seam without involving Android UI. */
public final class NanidroidCoordinatorTest {
    @Test
    public void initialization_reportsProgressThenCompletion() {
        RecordingGateway gateway = new RecordingGateway();
        RecordingObserver observer = new RecordingObserver();
        NanidroidCoordinator coordinator = new NanidroidCoordinator(gateway, observer);

        coordinator.startInitialization();
        coordinator.reportGhostLoading();
        coordinator.completeInitialization();

        assertEquals(NanidroidCoordinator.InitializationState.COMPLETE,
                coordinator.getInitializationState());
        assertNull(coordinator.getInitializationFailure());
        assertEquals(Arrays.asList(
                NanidroidCoordinator.InitializationState.STARTING,
                NanidroidCoordinator.InitializationState.LOADING_GHOST,
                NanidroidCoordinator.InitializationState.COMPLETE), observer.states);
    }

    @Test
    public void initialization_failureIsTerminalAndRetainsCause() {
        NanidroidCoordinator coordinator = new NanidroidCoordinator(
                new RecordingGateway(), new RecordingObserver());
        RuntimeException failure = new RuntimeException("ghost unavailable");

        coordinator.startInitialization();
        coordinator.failInitialization(failure);
        coordinator.completeInitialization();
        coordinator.reportGhostLoading();

        assertEquals(NanidroidCoordinator.InitializationState.FAILED,
                coordinator.getInitializationState());
        assertSame(failure, coordinator.getInitializationFailure());
    }

    @Test
    public void attachingAndDetachingUiDelegatesExactlyToScriptGateway() {
        RecordingGateway gateway = new RecordingGateway();
        NanidroidCoordinator coordinator = new NanidroidCoordinator(
                gateway, new RecordingObserver());
        ScriptInteractionCallback callback = new ScriptInteractionCallback() {
            @Override public void showUserInputBox(String id) { }
            @Override public void showUserSelection(String[] labels, String[] ids) { }
        };

        coordinator.attachUi(callback);
        coordinator.detachUi();
        coordinator.detachUi();

        assertSame(callback, gateway.attached);
        assertEquals(1, gateway.detachCalls);
        assertEquals(false, coordinator.isUiAttached());
    }

    private static final class RecordingGateway implements ScriptInteractionGateway {
        ScriptInteractionCallback attached;
        int detachCalls;

        @Override public void attach(ScriptInteractionCallback callback) {
            attached = callback;
        }

        @Override public void detach() {
            detachCalls++;
        }
    }

    private static final class RecordingObserver implements InitializationObserver {
        final List<NanidroidCoordinator.InitializationState> states = new ArrayList<>();

        @Override public void onInitializationStateChanged(
                NanidroidCoordinator.InitializationState state, Throwable failure) {
            states.add(state);
        }
    }
}
