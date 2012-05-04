package com.cattailsw.nanidroid;

import java.io.File;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;

public class GhostListDialogFragment extends DialogFragment {
	String[] gnz = null;
	GhostMgr gm = null;
    public static GhostListDialogFragment newInstance(int title, String[] gnz, GhostMgr gm) {
    	GhostListDialogFragment frag = new GhostListDialogFragment();
        Bundle args = new Bundle();
        args.putInt("title", title);
        frag.setArguments(args);
        frag.gnz = gnz;
        frag.gm = gm;
        return frag;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        int title = getArguments().getInt("title");

        return new AlertDialog.Builder(getActivity())
                //.setIcon(R.drawable.alert_dialog_icon)
                .setTitle(title)
                .setAdapter(Nanidroid.gAdapter,new OnClickListener(){

					@Override
					public void onClick(DialogInterface dialog, int which) {
						// TODO Auto-generated method stub
		                 if ( which < gnz.length) {
                         	File readme = gm.getGhostReadMe(gnz[which]);
                         	if ( readme.exists() ){
                         		DialogFragment r = ReadmeDialogFragment.newInstance(readme, gnz[which]);
                         		r.show(getFragmentManager(), Setup.DLG_README);
                         	}
                         	else {
                         		// need to show a dialog asking user to switch or not?
                         		DialogFragment r = NoReadmeSwitchDlg.newInstance(gnz[which]);
                         		r.show(getFragmentManager(), Setup.DLG_NO_REAMDE);
                         	}
                         }	
					}
                	
                })
                .setPositiveButton(R.string.more_ghosts_btn_text,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                        	((Nanidroid)getActivity()).getMoreGhost(1);
                        }
                    }
                )
                .setNegativeButton(android.R.string.cancel,
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int whichButton) {
                            dialog.dismiss();
                        }
                    }
                )
                .create();
    }
}