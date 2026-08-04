package com.cattailsw.nanidroid.durable

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.StringRes
import com.cattailsw.nanidroid.Nanidroid
import com.cattailsw.nanidroid.R
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object DurableAttentionIntentCodec {
    const val ACTION_KEEP_WAITING = "com.cattailsw.nanidroid.action.DURABLE_KEEP_WAITING"
    const val ACTION_STOP = "com.cattailsw.nanidroid.action.DURABLE_STOP"
    const val ACTION_RETRY_STOP = "com.cattailsw.nanidroid.action.DURABLE_RETRY_STOP"
    private const val SCHEME = "nanidroid"
    private const val AUTHORITY = "durable-operation"

    fun uri(handle: OperationHandle): Uri = Uri.parse(encode(handle))

    fun encode(handle: OperationHandle): String =
        "$SCHEME://$AUTHORITY/${encodeSegment(handle.operationId.value)}/${handle.attemptId.value}"

    fun parse(uri: Uri?): OperationHandle? = uri?.toString()?.let(::parse)

    fun parse(value: String): OperationHandle? {
        val parsed = runCatching { URI(value) }.getOrNull() ?: return null
        if (parsed.scheme != SCHEME || parsed.rawAuthority != AUTHORITY) return null
        if (parsed.rawQuery != null || parsed.rawFragment != null) return null
        val rawSegments = parsed.rawPath?.removePrefix("/")?.split('/') ?: return null
        if (rawSegments.size != 2) return null
        val operationId = runCatching {
            URLDecoder.decode(rawSegments[0], StandardCharsets.UTF_8.name())
        }.getOrNull() ?: return null
        if (operationId.isBlank() || encodeSegment(operationId) != rawSegments[0]) return null
        val attemptText = rawSegments[1]
        if (!attemptText.matches(Regex("[1-9][0-9]*"))) return null
        val attempt = attemptText.toLongOrNull()?.takeIf { it > 0L } ?: return null
        return OperationHandle(OperationId(operationId), AttemptId(attempt))
    }

    private fun encodeSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

    fun action(intentAction: String?): DurableAttentionAction? = when (intentAction) {
        ACTION_KEEP_WAITING -> DurableAttentionAction.KEEP_WAITING
        ACTION_STOP -> DurableAttentionAction.STOP
        ACTION_RETRY_STOP -> DurableAttentionAction.RETRY_STOP
        else -> null
    }
}

internal object DurableAttentionNotificationPolicy {
    fun actions(record: DurableOperationRecord): List<DurableAttentionAction> = when {
        record.status == OperationStatus.RUNNING -> listOf(
            DurableAttentionAction.KEEP_WAITING,
            DurableAttentionAction.STOP,
        )
        record.status == OperationStatus.CANCEL_REQUESTED &&
            record.isCancellationDispatchFailure() -> listOf(
                DurableAttentionAction.KEEP_WAITING,
                DurableAttentionAction.RETRY_STOP,
            )
        record.status == OperationStatus.CANCEL_REQUESTED ->
            listOf(DurableAttentionAction.KEEP_WAITING)
        else -> emptyList()
    }

    fun canPost(
        apiLevel: Int,
        permissionGranted: Boolean,
        notificationsEnabled: Boolean,
        channelEnabled: Boolean = true,
    ): Boolean = channelEnabled && notificationsEnabled && (apiLevel < 33 || permissionGranted)

    fun notificationTag(handle: OperationHandle) =
        "durable:${handle.operationId.value}::${handle.attemptId.value}"

    fun shouldRequestPermission(
        apiLevel: Int,
        permissionGranted: Boolean,
        userWorkAccepted: Boolean,
        activityResumed: Boolean,
    ): Boolean = apiLevel >= 33 && !permissionGranted && userWorkAccepted && activityResumed
}

internal object DurableOperationPresentation {
    @StringRes
    fun titleResource(kind: OperationKind) = when (kind) {
        OperationKind.REMOTE_NAR -> R.string.durable_operation_remote_nar
        OperationKind.LOCAL_NAR -> R.string.durable_operation_local_nar
        OperationKind.NAR_INSTALL -> R.string.durable_operation_nar_install
        OperationKind.GHOST_UPDATE -> R.string.durable_operation_ghost_update
    }

    @StringRes
    fun phaseResource(record: DurableOperationRecord) = when {
        record.status == OperationStatus.CANCEL_REQUESTED -> R.string.durable_phase_stopping
        record.kind == OperationKind.REMOTE_NAR -> R.string.durable_phase_downloading
        record.kind == OperationKind.LOCAL_NAR -> R.string.durable_phase_copying
        record.kind == OperationKind.NAR_INSTALL -> R.string.durable_phase_installing
        else -> R.string.durable_phase_updating
    }

    fun diagnosticText(context: Context, record: DurableOperationRecord): String? = when {
        record.isCancellationDispatchFailure() ->
            context.getString(R.string.durable_diagnostic_cancel_dispatch_failed)
        record.diagnostics == STOPPING_DELAY_DIAGNOSTIC ->
            context.getString(R.string.durable_diagnostic_stopping_delayed)
        record.diagnostics != null -> sanitizeDetail(record.diagnostics)
        else -> null
    }

    private fun sanitizeDetail(value: String): String = buildString {
        value.take(MAX_DIAGNOSTIC_CHARS).forEach { character ->
            append(if (character == '\n' || !character.isISOControl()) character else ' ')
        }
    }.trim()

    private const val MAX_DIAGNOSTIC_CHARS = 512
}

internal class DurableOperationAttentionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = DurableAttentionIntentCodec.action(intent.action) ?: return
        val handle = DurableAttentionIntentCodec.parse(intent.data) ?: return
        SharedDurableOperationSupervisor.get(context).performAttentionAction(handle, action)
    }
}

internal object DurableAttentionPendingIntents {
    fun action(
        context: Context,
        handle: OperationHandle,
        action: DurableAttentionAction,
    ): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, DurableOperationAttentionReceiver::class.java).apply {
            this.action = when (action) {
                DurableAttentionAction.KEEP_WAITING -> DurableAttentionIntentCodec.ACTION_KEEP_WAITING
                DurableAttentionAction.STOP -> DurableAttentionIntentCodec.ACTION_STOP
                DurableAttentionAction.RETRY_STOP -> DurableAttentionIntentCodec.ACTION_RETRY_STOP
            }
            data = DurableAttentionIntentCodec.uri(handle)
            `package` = context.packageName
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

internal class AndroidDurableAttentionNotifier(
    context: Context,
) : DurableAttentionNotifier {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)

    init {
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                appContext.getString(R.string.durable_attention_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = appContext.getString(R.string.durable_attention_channel_description)
            },
        )
    }

    override fun reconcile(stalled: List<DurableOperationRecord>) {
        val desiredTags = stalled.mapTo(mutableSetOf(), ::notificationTag)
        runCatching {
            manager?.activeNotifications
                ?.filter { it.id == NOTIFICATION_ID && it.tag !in desiredTags }
                ?.forEach { manager.cancel(it.tag, it.id) }
        }
        if (!canPost()) return
        stalled.forEach { record ->
            runCatching {
                manager?.notify(notificationTag(record), NOTIFICATION_ID, notification(record))
            }
        }
    }

    private fun canPost(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < 33 ||
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return DurableAttentionNotificationPolicy.canPost(
            Build.VERSION.SDK_INT,
            permissionGranted,
            manager?.areNotificationsEnabled() == true,
            manager?.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE,
        )
    }

    private fun notification(record: DurableOperationRecord): Notification {
        val phase = appContext.getString(DurableOperationPresentation.phaseResource(record))
        val diagnostics = DurableOperationPresentation.diagnosticText(appContext, record)
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.notification)
            .setContentTitle(
                appContext.getString(
                    R.string.durable_attention_title,
                    appContext.getString(DurableOperationPresentation.titleResource(record.kind)),
                ),
            )
            .setContentText(phase)
            .setStyle(
                Notification.BigTextStyle().bigText(
                    diagnostics?.let {
                        "$phase\n${appContext.getString(R.string.durable_diagnostics_label)}: $it"
                    } ?: phase,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
        DurableAttentionNotificationPolicy.actions(record).forEach { action ->
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    appContext.getString(action.labelResource()),
                    DurableAttentionPendingIntents.action(appContext, record.handle(), action),
                ).build(),
            )
        }
        return builder.build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        Intent(appContext, Nanidroid::class.java).apply {
            action = Intent.ACTION_MAIN
            data = Uri.parse("nanidroid://durable-operation/open")
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun notificationTag(record: DurableOperationRecord) =
        DurableAttentionNotificationPolicy.notificationTag(record.handle())

    private fun DurableOperationRecord.handle() = OperationHandle(id, attemptId)

    private fun DurableAttentionAction.labelResource() = when (this) {
        DurableAttentionAction.KEEP_WAITING -> R.string.durable_action_keep_waiting
        DurableAttentionAction.STOP -> R.string.durable_action_stop
        DurableAttentionAction.RETRY_STOP -> R.string.durable_action_retry_stop
    }

    private companion object {
        const val CHANNEL_ID = "nanidroid_operation_attention"
        const val NOTIFICATION_ID = 43
    }
}
