package com.cattailsw.nanidroid.dlgs;

import com.cattailsw.nanidroid.R;
import com.cattailsw.nanidroid.Setup;
import com.cattailsw.nanidroid.Nanidroid;
import android.support.v4.app.DialogFragment;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.Toast;
import android.app.AlertDialog.Builder;
import android.content.DialogInterface.OnClickListener;
import android.app.Dialog;

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
