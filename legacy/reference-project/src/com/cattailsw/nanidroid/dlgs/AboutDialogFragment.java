package com.cattailsw.nanidroid.dlgs;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.view.View;
import android.webkit.WebView;

import com.cattailsw.nanidroid.R;

public class AboutDialogFragment extends DialogFragment {

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {

		View aboutView = View.inflate(getActivity(), R.layout.installdlg, null);
		WebView webView = (WebView) aboutView.findViewById(R.id.readme_view);
		/*webView.setWebViewClient(new WebViewClient() {
			@Override
			public boolean shouldOverrideUrlLoading(WebView view, String url) {
			    // Disable all links open from this web view.
			    return true;
			}
		    });*/
		webView.loadUrl("file:///android_asset/about.html");

		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle(R.string.about_title);
		builder.setView(aboutView);
		builder.setPositiveButton(R.string.close_btn_text, new DialogInterface.OnClickListener() {
			
			@Override
			public void onClick(DialogInterface dialog, int which) {
				dialog.dismiss();
			}
		});
		
		return builder.create();
	}

}
