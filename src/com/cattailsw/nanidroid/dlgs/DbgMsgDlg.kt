package com.cattailsw.nanidroid.dlgs

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.View
import android.widget.TextView
import com.cattailsw.nanidroid.R

class DbgMsgDlg : DialogFragment() {
    private var msg: String? = null

    companion object {
        @JvmStatic fun newInstance(msg: String?): DbgMsgDlg = DbgMsgDlg().also { it.msg = msg }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val root = View.inflate(activity, R.layout.dbgdlg, null)
        root.findViewById<TextView>(R.id.msgtxt).text = msg
        return AlertDialog.Builder(activity)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { dialog, _ -> dialog.dismiss() }
            .create()
    }
}
