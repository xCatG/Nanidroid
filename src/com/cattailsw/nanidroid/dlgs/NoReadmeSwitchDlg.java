package com.cattailsw.nanidroid.dlgs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;

import com.cattailsw.nanidroid.Nanidroid;
import com.cattailsw.nanidroid.R;
import com.cattailsw.nanidroid.Setup;
import com.cattailsw.nanidroid.util.AnalyticsUtils;

public class NoReadmeSwitchDlg extends DialogFragment {
    public static NoReadmeSwitchDlg newInstance(String gid) {
	NoReadmeSwitchDlg frag = new NoReadmeSwitchDlg();
	Bundle args = new Bundle();
	args.putString("ghostid", gid);
	frag.setArguments(args);
	frag.gid = gid;
	return frag;
    }

    String gid = null;

    @Override
    public void onSaveInstanceState(Bundle args) {
	// TODO Auto-generated method stub

	if (gid != null)
	    args.putString("ghostid", gid);
	super.onSaveInstanceState(args);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
	if ( gid == null )
	    gid = savedInstanceState.getString("ghostid");
		
	AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
	builder.setTitle(R.string.no_readme_dlg_title)
	    .setMessage(String.format(getString(R.string.no_readme_text), gid))
	    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
			
		    @Override
		    public void onClick(DialogInterface dialog, int which) {
			AnalyticsUtils.getInstance(getActivity()).trackEvent(Setup.ANA_BTN, "close", "ghost_readme_dlg", 1);			
			dialog.dismiss();				
		    }
		})
	    .setPositiveButton(R.string.switch_to_ghost_btn_text, new DialogInterface.OnClickListener() {
			
		    @Override
		    public void onClick(DialogInterface dialog, int which) {
			dialog.dismiss();
			AnalyticsUtils.getInstance(getActivity()).trackEvent(Setup.ANA_BTN, "ghost_switch", "ghost_readme_dlg", 1);
			((Nanidroid)getActivity()).switchGhost(gid);
		    }
		});

	return builder.create();
    }
}
