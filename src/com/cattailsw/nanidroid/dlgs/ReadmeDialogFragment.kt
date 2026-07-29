package com.cattailsw.nanidroid.dlgs
import android.app.AlertDialog
import android.app.Dialog
import android.net.Uri
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import com.cattailsw.nanidroid.*
import com.cattailsw.nanidroid.util.AnalyticsUtils
import com.cattailsw.nanidroid.util.NarUtil
import java.io.File
class ReadmeDialogFragment : DialogFragment() {
 private var freadme:File?=null; private var gid:String?=null
 companion object { @JvmStatic fun newInstance(readme:File?,gid:String?):ReadmeDialogFragment=ReadmeDialogFragment().also{it.arguments=Bundle().apply{putString("readmepath",readme?.absolutePath);putString("ghostid",gid)};it.freadme=readme;it.gid=gid;it.setStyle(STYLE_NORMAL,STYLE_NORMAL)} }
 override fun onSaveInstanceState(out:Bundle){freadme?.let{out.putString("readmepath",it.absolutePath)};gid?.let{out.putString("ghostid",it)};super.onSaveInstanceState(out)}
 override fun onCreateDialog(state:Bundle?):Dialog { if(freadme==null)freadme=File(state!!.getString("readmepath"));if(gid==null)gid=state!!.getString("ghostid");val readme=freadme!!;val view=View.inflate(activity,R.layout.installdlg,null);view.findViewById<WebView>(R.id.readme_view).apply{webViewClient=WebViewClient();loadDataWithBaseURL(Uri.fromFile(readme).toString(),NarUtil.readTxt(readme),"text/html","UTF-8",null)};return AlertDialog.Builder(activity).setTitle(R.string.new_ghost_installed_title).setView(view).setNeutralButton(R.string.close_btn_text){d,_->AnalyticsUtils.getInstance(activity).trackEvent(Setup.ANA_BTN,"close","ghost_readme_dlg",0);d.dismiss()}.setPositiveButton(R.string.switch_to_ghost_btn_text){d,_->d.dismiss();AnalyticsUtils.getInstance(activity).trackEvent(Setup.ANA_BTN,"ghost_switch","ghost_readme_dlg",0);activity?.let{(it as Nanidroid).switchGhost(gid!!)}}.create() }
}
