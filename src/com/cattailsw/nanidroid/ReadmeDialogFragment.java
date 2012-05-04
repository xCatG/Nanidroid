package com.cattailsw.nanidroid;

import java.io.File;

import com.cattailsw.nanidroid.util.NarUtil;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.DialogFragment;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class ReadmeDialogFragment extends DialogFragment {
	public static ReadmeDialogFragment newInstance(File readme, String gid){
		ReadmeDialogFragment frag = new ReadmeDialogFragment();
        Bundle args = new Bundle();
        args.putString("readmepath", readme.getAbsolutePath());
        args.putString("ghostid", gid);
        frag.setArguments(args);
        frag.freadme = readme;
        frag.gid = gid;
        frag.setStyle(STYLE_NORMAL, STYLE_NORMAL);
        return frag;
	}
	
	File freadme = null;
	String gid = null;	
	
	@Override
	public void onSaveInstanceState(Bundle args) {
		// TODO Auto-generated method stub
		if ( freadme != null )
		args.putString("readmepath", freadme.getAbsolutePath());
		if ( gid != null )
	    args.putString("ghostid", gid);		
		super.onSaveInstanceState(args);
	}

	@Override
	public Dialog onCreateDialog(Bundle savedInstanceState) {
		if ( freadme == null ) {
			String rpath = savedInstanceState.getString("readmepath");
			freadme = new File(rpath);
		}
		
		if ( gid == null )
			gid = savedInstanceState.getString("ghostid");
		
		View readmeView = View.inflate(getActivity(), R.layout.installdlg, null);
		WebView webView = (WebView) readmeView.findViewById(R.id.readme_view);
		webView.setWebViewClient(new WebViewClient() {

		    });
	 	webView.loadDataWithBaseURL(Uri.fromFile(freadme).toString(), NarUtil.readTxt(freadme), 
	 				    "text/html", "UTF-8", null);

		AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
		builder.setTitle("installed new ghost");	
		builder.setView(readmeView);
		builder.setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener(){
			public void onClick(DialogInterface dlg, int which){
			    dlg.dismiss();
			}
		    });
		builder.setPositiveButton("switch to ghost", new DialogInterface.OnClickListener(){
			public void onClick(DialogInterface dlg, int which){
			    dlg.dismiss();
				Activity act = ReadmeDialogFragment.this.getActivity();// getActivity();
				if ( act != null )
			    ((Nanidroid)act).switchGhost(gid);
			}
		    });

		//builder.show();
		return builder.create();
	}
}
