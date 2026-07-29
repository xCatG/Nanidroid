package com.cattailsw.nanidroid.dlgs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;

public class ErrMsgDlg extends DialogFragment {

    public interface ErrDlgCallback {
	public void onDismiss(int flag);
    }

    private static final String S_TITLE = "title";
    private static final String S_MSG = "msg";
    private int tR;
    private int mR;
    private int flag;
    private ErrDlgCallback cb;
    public static ErrMsgDlg newInstance(int titleRes, int msgRes) {
	return newInstance(titleRes, msgRes, null, -1);
    }

    public static ErrMsgDlg newInstance(int titleRes, int msgRes, ErrDlgCallback cb, int flag) {
	ErrMsgDlg frag = new ErrMsgDlg();
        Bundle args = new Bundle();
        args.putInt(S_TITLE, titleRes);
	args.putInt(S_MSG, msgRes);
        frag.setArguments(args);
        frag.tR = titleRes;
        frag.mR = msgRes;
	frag.cb = cb;
	frag.flag = flag;
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
				if ( cb != null )
				    cb.onDismiss(flag);
			}
		});
		
		return builder.create();
	}

}
