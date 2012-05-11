package com.cattailsw.nanidroid.dlgs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.widget.ListAdapter;

import com.cattailsw.nanidroid.R;

public class NarPickDlg extends DialogFragment {

    String[] narz = null;

    public interface NarPickDlgListener {
	public void onNarPick(String pathToNar);
    }

    public NarPickDlg(String[] n) {
	narz = n;
    }

    ListAdapter adapter = null;
    int selectedItem = 0;
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
    	//adapter = new ArrayAdapter<String>(getActivity(), R.layout.ghost_list_item, narz);

        return new AlertDialog.Builder(getActivity())
	    .setTitle(R.string.dlg_sel_nar_title)
	    .setSingleChoiceItems(narz, -1, new DialogInterface.OnClickListener() {
		    public void onClick(DialogInterface dialog, int whichButton) {
			selectedItem = whichButton;
		    }
		})
	    .setPositiveButton(R.string.dlg_sel_nar_install_text,
			       new DialogInterface.OnClickListener() {
				   public void onClick(DialogInterface dialog, int whichButton) {
				       //Toast.makeText(getActivity(), "which:" + whichButton + ",sel:" + selectedItem, Toast.LENGTH_SHORT).show();
				       dialog.dismiss();
				       ((NarPickDlgListener)getActivity()).onNarPick(narz[selectedItem]);
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
