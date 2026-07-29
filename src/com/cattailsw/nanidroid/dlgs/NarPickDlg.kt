package com.cattailsw.nanidroid.dlgs
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.cattailsw.nanidroid.R
class NarPickDlg(private val narz:Array<String>) : DialogFragment() {
 interface NarPickDlgListener { fun onNarPick(pathToNar:String) }
 private var selectedItem=0
 override fun onCreateDialog(state:Bundle?):Dialog = AlertDialog.Builder(activity).setTitle(R.string.dlg_sel_nar_title).setSingleChoiceItems(narz,-1){_,which->selectedItem=which}.setPositiveButton(R.string.dlg_sel_nar_install_text){d,_->d.dismiss();(activity as NarPickDlgListener).onNarPick(narz[selectedItem])}.setNegativeButton(android.R.string.cancel){d,_->d.dismiss()}.create()
}
