package com.cattailsw.nanidroid.dlgs
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.cattailsw.nanidroid.R
import com.cattailsw.nanidroid.Setup
class MoreGhostFuncDlg : DialogFragment() {
 interface MoreGhostFuncListener { fun startInstallFromSDCard(); fun showUrlDlg(); fun showGhostTown() }
 override fun onCreateDialog(state: Bundle?): Dialog = AlertDialog.Builder(activity).setTitle(R.string.more_g_title).setItems(R.array.more_g_items) { dialog, which -> val listener=activity as MoreGhostFuncListener; when(which){0->listener.showUrlDlg();1->listener.startInstallFromSDCard();2->listener.showGhostTown();else->NotImplementedDlg().show(fragmentManager,Setup.DLG_NOT_IMPL)};dialog.dismiss() }.create()
}
