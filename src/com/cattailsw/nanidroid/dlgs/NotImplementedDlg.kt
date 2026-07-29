package com.cattailsw.nanidroid.dlgs
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.cattailsw.nanidroid.R
class NotImplementedDlg : DialogFragment() { override fun onCreateDialog(state:Bundle?):Dialog = AlertDialog.Builder(activity).setMessage(R.string.not_implemented).setTitle(R.string.not_implemeted_title).setPositiveButton(android.R.string.ok){d,_->d.dismiss()}.create() }
