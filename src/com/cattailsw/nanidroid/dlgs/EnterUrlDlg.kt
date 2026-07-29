package com.cattailsw.nanidroid.dlgs

import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import com.cattailsw.nanidroid.PatternHolders
import com.cattailsw.nanidroid.R

class EnterUrlDlg : DialogFragment(), TextView.OnEditorActionListener, View.OnClickListener {
    interface EUrlDlgListener { fun onFinishURL(inputText: String) }
    private var urlText: EditText? = null
    private var urlErr: View? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        return inflater.inflate(R.layout.dlg_url_input, container).also { view ->
            urlText = view.findViewById(R.id.url_in)
            urlErr = view.findViewById(R.id.errmsg_disp)
            view.findViewById<Button>(R.id.dl_btn).setOnClickListener(this)
            dialog!!.setTitle(R.string.more_g_enter_url_text)
            urlText!!.requestFocus()
            dialog!!.window!!.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            urlText!!.setOnEditorActionListener(this)
        }
    }
    private fun dataVal(): Boolean { val url=urlText!!.text.toString(); if(!PatternHolders.url_ptrn.matcher(url).find()){urlErr!!.visibility=View.VISIBLE;return false}; (activity as EUrlDlgListener).onFinishURL(url); dismiss(); return true }
    override fun onEditorAction(v: TextView, actionId: Int, event: KeyEvent?): Boolean = if(actionId==EditorInfo.IME_ACTION_DONE)dataVal() else false
    override fun onClick(v: View) { dataVal() }
}
