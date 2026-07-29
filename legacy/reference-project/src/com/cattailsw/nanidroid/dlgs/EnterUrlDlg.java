package com.cattailsw.nanidroid.dlgs;

import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.TextView.OnEditorActionListener;

import com.cattailsw.nanidroid.PatternHolders;
import com.cattailsw.nanidroid.R;

public class EnterUrlDlg extends DialogFragment implements OnEditorActionListener, OnClickListener{

    public interface EUrlDlgListener {
        void onFinishURL(String inputText);
    }

    private EditText urlText = null;
    private View urlErr = null;
    private Button btnDL = null;


    public View onCreateView(final LayoutInflater layoutInflater, ViewGroup container, final Bundle bundle) {
	View v = layoutInflater.inflate(R.layout.dlg_url_input, container);
	urlText = (EditText)v.findViewById(R.id.url_in);
	urlErr = v.findViewById(R.id.errmsg_disp);
	btnDL = (Button) v.findViewById(R.id.dl_btn);
	btnDL.setOnClickListener(this);
	getDialog().setTitle(R.string.more_g_enter_url_text);

	urlText.requestFocus();
	getDialog().getWindow().setSoftInputMode( WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
	urlText.setOnEditorActionListener(this);

	return v;
    }

    private boolean validateUrl(String txt) {
	return PatternHolders.url_ptrn.matcher(txt).find();
    }

    private boolean dataVal() {
	    String url = urlText.getText().toString();
	    if ( validateUrl(url) == false ) {
		
		urlErr.setVisibility(View.VISIBLE);

		return false;
	    }

	    EUrlDlgListener a = (EUrlDlgListener) getActivity();
	    a.onFinishURL( urlText.getText().toString() );
	    this.dismiss();
	    return true;
    }

    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
	if ( EditorInfo.IME_ACTION_DONE == actionId ) {
	    return dataVal();
	}
	return false;
    }

    public void onClick(View v) {
	dataVal();
    }
}
