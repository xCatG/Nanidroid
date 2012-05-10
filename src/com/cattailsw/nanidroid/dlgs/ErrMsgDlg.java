package com.cattailsw.nanidroid.dlgs;

import com.cattailsw.nanidroid.R;
import com.cattailsw.nanidroid.R.string;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;

public class ErrMsgDlg extends DialogFragment {

    private static final String S_TITLE = "title";
    private static final String S_MSG = "msg";
private int tR;
private int mR;
    public static ErrMsgDlg newInstance(int titleRes, int msgRes) {
	ErrMsgDlg frag = new ErrMsgDlg();
        Bundle args = new Bundle();
        args.putInt(S_TITLE, titleRes);
	args.putInt(S_MSG, msgRes);
        frag.setArguments(args);
        frag.tR = titleRes;
        frag.mR = msgRes;
	return frag;
    }

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
	    /*int tR = savedInstanceState.getInt(S_TITLE);
	    int mR = savedInstanceState.getInt(S_MSG);
*/
		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setMessage(mR)
		.setTitle(tR)
		.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
			
			@Override
			public void onClick(DialogInterface dialog, int which) {
				// TODO Auto-generated method stub
				dialog.dismiss();
			}
		});
		
		return builder.create();
	}

}
