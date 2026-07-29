package com.cattailsw.nanidroid.dlgs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;

import com.cattailsw.nanidroid.R;
import com.cattailsw.nanidroid.Setup;

public class MoreGhostFuncDlg extends DialogFragment {
    public interface MoreGhostFuncListener {
	public void startInstallFromSDCard();
	public void showUrlDlg();
	public void showGhostTown();
    }

    public MoreGhostFuncDlg() {

    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    
        return new AlertDialog.Builder(getActivity())
	    .setTitle(R.string.more_g_title)
	    .setItems(R.array.more_g_items, new DialogInterface.OnClickListener() {
		    public void onClick(DialogInterface dlg, int whichButton) {
			switch(whichButton) {
			case 0: // enter URL
			    ((MoreGhostFuncListener)getActivity()).showUrlDlg();
			    break;
			case 1: // from SD card
			    ((MoreGhostFuncListener)getActivity()).startInstallFromSDCard();
			    break;
			case 2: // ghost town
			    ((MoreGhostFuncListener)getActivity()).showGhostTown();
			    break;
			default:
			    NotImplementedDlg n = new NotImplementedDlg();
			    n.show(getFragmentManager(), Setup.DLG_NOT_IMPL);
			    break;
			}
			dlg.dismiss();
		    }
		})
	    .create();
    }
}
