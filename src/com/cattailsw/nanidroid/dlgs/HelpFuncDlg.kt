package com.cattailsw.nanidroid.dlgs
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.util.AnalyticsUtils
class HelpFuncDlg : DialogFragment() {
 override fun onCreateDialog(state: Bundle?): Dialog = AlertDialog.Builder(activity).setTitle(R.string.menu_help).setItems(R.array.gen_usage_items) { _, which -> when(which) { 0 -> { AnalyticsUtils.getInstance(activity).trackPageView("/help/install"); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_help_install)))) }; 1 -> { AnalyticsUtils.getInstance(activity).trackPageView("/help/supported_ops"); startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.url_support_ops)))) } } }.create()
}
