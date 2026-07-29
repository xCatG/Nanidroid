package com.cattailsw.nanidroid.dlgs
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.cattailsw.nanidroid.*
import com.cattailsw.nanidroid.util.AnalyticsUtils
class NoReadmeSwitchDlg : DialogFragment() {
 private var gid:String?=null; private var gname:String?=null
 companion object { @JvmStatic fun newInstance(gid:String?,gname:String?):NoReadmeSwitchDlg=NoReadmeSwitchDlg().also{it.arguments=Bundle().apply{putString("ghostid",gid);putString("ghostname",gname)};it.gid=gid;it.gname=gname} }
 override fun onSaveInstanceState(out:Bundle){gid?.let{out.putString("ghostid",it)};gname?.let{out.putString("ghostname",it)};super.onSaveInstanceState(out)}
 override fun onCreateDialog(state:Bundle?):Dialog { if(gid==null)gid=state?.getString("ghostid");if(gname==null)gname=state?.getString("ghostname");return AlertDialog.Builder(activity).setTitle(R.string.no_readme_dlg_title).setMessage(String.format(getString(R.string.no_readme_text),gname)).setNegativeButton(android.R.string.cancel){d,_->AnalyticsUtils.getInstance(activity).trackEvent(Setup.ANA_BTN,"close","ghost_readme_dlg",1);d.dismiss()}.setPositiveButton(R.string.switch_to_ghost_btn_text){d,_->d.dismiss();AnalyticsUtils.getInstance(activity).trackEvent(Setup.ANA_BTN,"ghost_switch","ghost_readme_dlg",1);(activity as Nanidroid).switchGhost(gid!!)}.create() }
}
