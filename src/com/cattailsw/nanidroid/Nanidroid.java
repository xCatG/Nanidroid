package com.cattailsw.nanidroid;

import android.app.Activity;
import android.os.Bundle;
import android.widget.ImageView;
import android.os.Environment;
import java.io.File;
import android.widget.TextView;

public class Nanidroid extends Activity
{
    private ImageView iv = null;
    private TextView tv = null;

    /** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

	iv = (ImageView) findViewById(R.id.sakura_display);
	tv = (TextView) findViewById(R.id.tv);

	// need to get a list of ghosts on sd card
	if ( Environment.getExternalStorageState().equalsIgnoreCase(Environment.MEDIA_MOUNTED) == false ) {
	    tv.setText("sd card error");
	    return;
	    // need to prompt SD card issue
	}

	// use /sdcard/Android/data/com.cattailsw.nanidroid/ghost/yohko for the time being
	String ghost_path = Environment.getExternalStorageDirectory().getPath() + "/Android/data/com.cattailsw.nanidroid/ghost/yohko";

	// read the ghost shell
	//
	String master_shell_path = ghost_path + "/shell/master";

	// read master data
	String master_ghost_path = ghost_path + "/ghost/master";

	String shell_desc_path = master_shell_path + "/descript.txt";
	String shell_surface_path = master_shell_path + "/surfaces.txt";

	File shell_desc = new File(shell_desc_path);
	if ( shell_desc.exists() == false ) {
	    tv.setText("shell desc error.");
	    return;
	}

	DescReader shellDescReader = new DescReader(shell_desc);
	//DescReader shellSurfaceReader = 
	File shellSurface = new File(shell_surface_path);
	SurfaceReader sr = new SurfaceReader(shellSurface);


	tv.setText("shell desc size=" + shellDescReader.table.size() + ", ghost name=" + shellDescReader.table.get("name") 
		   + "\nshell surface count=" + sr.table.size() );
	iv.setImageDrawable( sr.table.get("1").getSurfaceDrawable(getResources()) );
    }
}
