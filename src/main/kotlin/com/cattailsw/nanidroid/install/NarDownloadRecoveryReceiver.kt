package com.cattailsw.nanidroid.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconciles durable transfers after reboot or application replacement. */
class NarDownloadRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        NarDownloadRepository.get(context).reconcile()
    }
}
