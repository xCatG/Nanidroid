package com.cattailsw.nanidroid.dlgs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;

import com.cattailsw.nanidroid.R;
import com.cattailsw.nanidroid.util.AnalyticsUtils;

public class HelpFuncDlg extends DialogFragment {

    public HelpFuncDlg() {

    }

    public Dialog onCreateDialog(final Bundle bundle) {
	return new AlertDialog.Builder(getActivity())
	    .setTitle(R.string.menu_help)
	    .setItems(R.array.gen_usage_items, new DialogInterface.OnClickListener() {
		    public void onClick(DialogInterface dlg, int whichButton) {
			switch(whichButton) {
			case 0: // install more ghost
	AnalyticsUtils.getInstance(getActivity()).trackPageView("/help/install");
	Uri help_install = Uri.parse(getString(R.string.url_help_install));
	startActivity(new Intent(Intent.ACTION_VIEW, help_install));

			    break;
			case 1: // supported ops
	AnalyticsUtils.getInstance(getActivity()).trackPageView("/help/supported_ops");
	Uri help_sup_ops = Uri.parse(getString(R.string.url_support_ops));
	startActivity(new Intent(Intent.ACTION_VIEW, help_sup_ops));
	break;
			}
		    }
		})
	    .create();
    }


}
