package com.cattailsw.nanidroid.dlgs;

import android.support.v4.app.DialogFragment;
import android.os.Bundle;

import com.cattailsw.nanidroid.R;
import android.widget.EditText;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.view.View;
import android.widget.Button;

public class UserInputDlg extends DialogFragment {

    public interface UserInputListener {
	void onFinishUserInput(String id, String userinput);
	void onCancelInput();
    }

    private EditText mEdit = null;
    private Button okBtn = null;
    private Button cancleBtn = null;
    private String id = null;
    public UserInputDlg(String iid){
	id = iid;
    }

    public View onCreateView(final LayoutInflater layoutInflater, ViewGroup container, final Bundle bundle) {
	View v = layoutInflater.inflate(R.layout.dlg_user_input, container);
	mEdit = (EditText) v.findViewById(R.id.user_in);
	okBtn = (Button) v.findViewById(R.id.ok_btn);
	okBtn.setOnClickListener(new View.OnClickListener(){
		public void onClick(View v) {
		    UserInputListener i = (UserInputListener) getActivity();
		    i.onFinishUserInput(id, mEdit.getText().toString());
		    UserInputDlg.this.dismiss();
		}
	    });
	cancleBtn = (Button) v.findViewById(R.id.cancel_btn);
	cancleBtn.setOnClickListener(new View.OnClickListener(){
		public void onClick(View v) {
		    UserInputListener i = (UserInputListener) getActivity();
		    i.onCancelInput();
		    UserInputDlg.this.dismiss();
		}
	    });
	getDialog().setTitle(R.string.user_input_dlg_title);
	return v;
    }

    

}
