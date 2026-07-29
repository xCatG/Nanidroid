package com.cattailsw.nanidroid.dlgs

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment

class ErrMsgDlg : DialogFragment() {
    interface ErrDlgCallback { fun onDismiss(flag: Int) }
    private var tR = 0; private var mR = 0; private var flag = 0; private var cb: ErrDlgCallback? = null
    companion object {
        private const val S_TITLE = "title"; private const val S_MSG = "msg"
        @JvmStatic fun newInstance(titleRes: Int, msgRes: Int): ErrMsgDlg = newInstance(titleRes,msgRes,null,-1)
        @JvmStatic fun newInstance(titleRes: Int,msgRes: Int,cb: ErrDlgCallback?,flag: Int): ErrMsgDlg = ErrMsgDlg().also { f -> f.arguments=Bundle().apply{putInt(S_TITLE,titleRes);putInt(S_MSG,msgRes)};f.tR=titleRes;f.mR=msgRes;f.cb=cb;f.flag=flag }
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = AlertDialog.Builder(activity).setMessage(mR).setTitle(tR).setPositiveButton(android.R.string.ok) { dialog,_ -> dialog.dismiss();cb?.onDismiss(flag) }.create()
}
