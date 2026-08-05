package com.cattailsw.nanidroid.durable

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import com.cattailsw.nanidroid.di.MonotonicClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class DurableOperationAttentionInstrumentationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @After fun resetSharedSupervisor() {
        SharedDurableOperationSupervisor.resetForTesting()
    }

    @Test fun receiverIsPrivateAndNotificationPermissionIsDeclared() {
        val receiver = context.packageManager.getReceiverInfo(
            ComponentName(context, DurableOperationAttentionReceiver::class.java),
            0,
        )
        assertFalse(receiver.exported)

        val requested = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        ).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in requested)
    }

    @Test fun immutableActionPendingIntentsHaveExactHandleAndActionIdentity() {
        val collidingA = OperationHandle(OperationId("Aa"), AttemptId(1L))
        val collidingB = OperationHandle(OperationId("BB"), AttemptId(1L))
        val keepA = DurableAttentionPendingIntents.action(
            context,
            collidingA,
            DurableAttentionAction.KEEP_WAITING,
        )
        val keepB = DurableAttentionPendingIntents.action(
            context,
            collidingB,
            DurableAttentionAction.KEEP_WAITING,
        )
        val stopA = DurableAttentionPendingIntents.action(
            context,
            collidingA,
            DurableAttentionAction.STOP,
        )

        assertNotEquals(keepA, keepB)
        assertNotEquals(keepA, stopA)
        keepA.cancel()
        keepB.cancel()
        stopA.cancel()
    }

    @Test fun receiverRoutesOnlyCurrentPromptAction() {
        val clock = MutableClock()
        val store = SharedPreferencesDurableOperationStore(
            SharedPreferencesDurableOperationStore.MemoryStorage(),
        )
        val supervisor = DurableOperationSupervisor(store, clock) { _, _, _ -> }
        val handle = OperationHandle(OperationId("receiver-exact"), AttemptId(1L))
        val binding = ExternalJobBinding.DownloadManager(9L)
        assertTrue(
            supervisor.start(handle, OperationKind.REMOTE_NAR, "Downloading archive", 0L, binding),
        )
        clock.value = 30_000L
        assertTrue(supervisor.snapshot().single().showStallPrompt)
        val restored = DurableOperationSupervisor(store, clock) { _, _, _ -> }
        assertFalse(restored.snapshot().single().showStallPrompt)
        SharedDurableOperationSupervisor.replaceForTesting(restored)

        DurableOperationAttentionReceiver().onReceive(
            context,
            Intent(context, DurableOperationAttentionReceiver::class.java).apply {
                action = DurableAttentionIntentCodec.ACTION_KEEP_WAITING
                data = DurableAttentionIntentCodec.uri(handle)
            },
        )
        assertFalse(store.read().single().showStallPrompt)

        DurableOperationAttentionReceiver().onReceive(
            context,
            Intent(context, DurableOperationAttentionReceiver::class.java).apply {
                action = DurableAttentionIntentCodec.ACTION_STOP
                data = DurableAttentionIntentCodec.uri(handle)
            },
        )
        assertEquals(OperationStatus.RUNNING, store.read().single().status)
    }

    @Test fun runningAndStoppingPresentationUsesRequestedLocale() {
        val running = record(OperationStatus.RUNNING, diagnostics = null)
        val stopping = record(
            OperationStatus.CANCEL_REQUESTED,
            diagnostics = STOPPING_DELAY_DIAGNOSTIC,
        )
        val japanese = localizedContext(Locale.JAPANESE)
        val traditionalChinese = localizedContext(Locale.forLanguageTag("zh-TW"))

        assertEquals(
            "アーカイブをダウンロードしています…",
            japanese.getString(DurableOperationPresentation.phaseResource(running)),
        )
        assertEquals(
            "停止しています…",
            japanese.getString(DurableOperationPresentation.phaseResource(stopping)),
        )
        assertEquals(
            "停止依頼から30秒以上経過しています。",
            DurableOperationPresentation.diagnosticText(japanese, stopping),
        )
        assertEquals(
            "正在停止…",
            traditionalChinese.getString(DurableOperationPresentation.phaseResource(stopping)),
        )
    }

    private fun localizedContext(locale: Locale) = context.createConfigurationContext(
        Configuration(context.resources.configuration).apply {
            setLocales(LocaleList(locale))
        },
    )

    private fun record(status: OperationStatus, diagnostics: String?) = DurableOperationRecord(
        id = OperationId("localized"),
        attemptId = AttemptId(1L),
        kind = OperationKind.REMOTE_NAR,
        externalJob = ExternalJobBinding.DownloadManager(1L),
        progress = OperationProgress("internal English phase", 0L),
        status = status,
        showStallPrompt = true,
        diagnostics = diagnostics,
    )

    private class MutableClock(var value: Long = 0L) : MonotonicClock {
        override fun nowMillis() = value
    }
}
