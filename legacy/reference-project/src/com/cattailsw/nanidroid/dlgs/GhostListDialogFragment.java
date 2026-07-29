package com.cattailsw.nanidroid.dlgs;

import java.io.File;

import com.cattailsw.nanidroid.GhostMgr;
import com.cattailsw.nanidroid.Nanidroid;
import com.cattailsw.nanidroid.R;
import com.cattailsw.nanidroid.Setup;
import com.cattailsw.nanidroid.util.AnalyticsUtils;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.widget.ArrayAdapter;
import android.widget.ListAdapter;

public class GhostListDialogFragment extends DialogFragment {
    String[] gnz = null;
    String[] gidz = null;
    GhostMgr gm = null;
    ListAdapter adapter = null;
    public static GhostListDialogFragment newInstance(String[] gnz, GhostMgr gm) {
    	GhostListDialogFragment frag = new GhostListDialogFragment();
        Bundle args = new Bundle();
        //args.putInt("title", title);
        frag.setArguments(args);
        //frag.gnz = gnz;
        frag.gm = gm;
        return frag;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	//adapter = new ArrayAdapter<String>(getActivity(), R.layout.ghost_list_item, gnz);
	gnz = gm.getGDispNames();
	gidz = gm.getGnames();
        return new AlertDialog.Builder(getActivity())
	    //.setIcon(R.drawable.alert_dialog_icon)
	    .setTitle(R.string.list_ghost_dlg_title)
	    .setItems(gnz,/*
			    .setAdapter(adapter,*/new OnClickListener(){

		    @Override
		    public void onClick(DialogInterface dialog, int which) {
			// TODO Auto-generated method stub
			if ( which < gnz.length) {
			    AnalyticsUtils.getInstance(getActivity())
				.trackEvent(Setup.ANA_UI_TOUCH, "ghost_list_touch", gnz[which], 
					    gm.getGhostLaunchCount(which));

			    File readme = gm.getGhostReadMe(gidz[which]);
			    if ( readme.exists() ){
				DialogFragment r = ReadmeDialogFragment.newInstance(readme, gidz[which]);
				r.show(getFragmentManager(), Setup.DLG_README);
			    }
			    else {
				// need to show a dialog asking user to switch or not?
				DialogFragment r = NoReadmeSwitchDlg.newInstance(gidz[which], gnz[which]);
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
			    AnalyticsUtils.getInstance(getActivity())
				.trackEvent(Setup.ANA_BTN, "ghost_list_cancel", "", 0);

				       dialog.dismiss();
				   }
			       }
			       )
	    .create();
    }
}
