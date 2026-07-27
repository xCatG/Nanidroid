package com.cattailsw.nanidroid.dlgs

import android.app.AlertDialog
import android.app.Dialog
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.View
import android.webkit.WebView
import com.cattailsw.nanidroid.R

class AboutDialogFragment : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val aboutView = View.inflate(activity, R.layout.installdlg, null)
        val webView = aboutView.findViewById<WebView>(R.id.readme_view)
        webView.loadUrl("file:///android_asset/about.html")

        return AlertDialog.Builder(activity)
            .setTitle(R.string.about_title)
            .setView(aboutView)
            .setPositiveButton(R.string.close_btn_text) { dialog, _ ->
                dialog.dismiss()
            }
            .create()
    }
}
