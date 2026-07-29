package com.cattailsw.nanidroid.dlgs
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.cattailsw.nanidroid.R
class UserSelectDlg : DialogFragment() {
 interface UserSelDlgListener { fun onChoiceSelect(id:String) }
 private var labels:Array<String>?=null; private var idz:Array<String>?=null
 companion object { @JvmStatic fun newInstance(text:Array<String>?,ids:Array<String>?):UserSelectDlg=UserSelectDlg().also{it.labels=text;it.idz=ids} }
 override fun onSaveInstanceState(out:Bundle){out.putStringArray("labels",labels);out.putStringArray("idz",idz);super.onSaveInstanceState(out)}
 override fun onCreateDialog(state:Bundle?):Dialog { if(labels==null)labels=state!!.getStringArray("labels");if(idz==null)idz=state!!.getStringArray("idz");return AlertDialog.Builder(activity).setCancelable(false).setTitle(R.string.user_sel_dlg_title).setItems(labels){_,which->if(which<labels!!.size)(activity as UserSelDlgListener).onChoiceSelect(idz!![which])}.create() }
}
