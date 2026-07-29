package com.cattailsw.nanidroid.dlgs
import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import com.cattailsw.nanidroid.*
import com.cattailsw.nanidroid.util.AnalyticsUtils

class GhostListDialogFragment : DialogFragment() {
    private var gnz: Array<String>?=null; private var gidz:Array<String>?=null; private var gm:GhostMgr?=null
    companion object { @JvmStatic fun newInstance(gnz:Array<String>?,gm:GhostMgr?):GhostListDialogFragment=GhostListDialogFragment().also{it.arguments=Bundle();it.gm=gm} }
    override fun onCreateDialog(state:Bundle?):Dialog { val manager=gm!!;gnz=manager.gDispNames;gidz=manager.gnames;return AlertDialog.Builder(activity).setTitle(R.string.list_ghost_dlg_title).setItems(gnz){_,which->if(which<gnz!!.size){AnalyticsUtils.getInstance(activity).trackEvent(Setup.ANA_UI_TOUCH,"ghost_list_touch",gnz!![which],manager.getGhostLaunchCount(which));val id=gidz!![which];if(manager.getGhostReadMe(id).exists())ReadmeDialogFragment.newInstance(manager.getGhostReadMe(id),id).show(fragmentManager,Setup.DLG_README)else NoReadmeSwitchDlg.newInstance(id,gnz!![which]).show(fragmentManager,Setup.DLG_NO_REAMDE)}}.setPositiveButton(R.string.more_ghosts_btn_text){_,_->(activity as Nanidroid).getMoreGhost(1)}.setNegativeButton(android.R.string.cancel){d,_->AnalyticsUtils.getInstance(activity).trackEvent(Setup.ANA_BTN,"ghost_list_cancel","",0);d.dismiss()}.create() }
}
