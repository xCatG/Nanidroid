package com.cattailsw.nanidroid;

import android.app.Application;
import org.acra.*;
import org.acra.annotation.*;

@ReportsCrashes(formKey="dFE5LWxwSUs3TGhTaTd6TE1aLTdlcXc6MQ")
public class CatTailApplication extends Application {

    public final void onCreate() {
	ACRA.init(this);
	super.onCreate();
    }


}
