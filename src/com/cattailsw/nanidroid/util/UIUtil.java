package com.cattailsw.nanidroid.util;

import android.os.Build;

public class UIUtil {

    public static boolean isAfterEclair(){ // 2.1
	return Build.VERSION.SDK_INT > Build.VERSION_CODES.ECLAIR_MR1;
    }

}
