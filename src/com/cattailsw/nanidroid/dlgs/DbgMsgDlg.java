package com.cattailsw.nanidroid.dlgs;

import com.cattailsw.nanidroid.R;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.view.View;
import android.widget.TextView;

public class DbgMsgDlg extends DialogFragment {
	private String msg = null;
	public static DbgMsgDlg newInstance(String msg) {
		DbgMsgDlg f = new DbgMsgDlg();
		f.msg = msg;
		return f;
	}

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		View rootView = View.inflate(getActivity(), R.layout.dbgdlg, null);
		TextView tv = (TextView) rootView.findViewById(R.id.msgtxt);
		tv.setText(msg);
		
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setView(rootView);
		builder.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
			
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
	
		return builder.create();
	}

}
