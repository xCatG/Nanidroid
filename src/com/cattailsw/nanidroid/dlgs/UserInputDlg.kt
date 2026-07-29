package com.cattailsw.nanidroid.dlgs
import android.os.Bundle
import android.support.v4.app.DialogFragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import com.cattailsw.nanidroid.R
class UserInputDlg() : DialogFragment() {
 interface UserInputListener { fun onFinishUserInput(id:String,userinput:String); fun onCancelInput() }
 private var edit:EditText?=null; private var id:String?=null
 constructor(id:String?) : this(){this.id=id}
 override fun onSaveInstanceState(out:Bundle){out.putString("id",id);super.onSaveInstanceState(out)}
 override fun onCreateView(inflater:LayoutInflater,container:ViewGroup?,state:Bundle?):View { if(id==null)id=state!!.getString("id");return inflater.inflate(R.layout.dlg_user_input,container).also{v->edit=v.findViewById(R.id.user_in);v.findViewById<Button>(R.id.ok_btn).setOnClickListener{(activity as UserInputListener).onFinishUserInput(id!!,edit!!.text.toString());dismiss()};v.findViewById<Button>(R.id.cancel_btn).setOnClickListener{(activity as UserInputListener).onCancelInput();dismiss()};dialog!!.setTitle(R.string.user_input_dlg_title)} }
}
