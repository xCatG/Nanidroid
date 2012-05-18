package com.cattailsw.nanidroid;

import com.cattailsw.nanidroid.shiori.EchoShiori;
import com.cattailsw.nanidroid.shiori.SatoriPosixShiori;
import com.cattailsw.nanidroid.shiori.Shiori;
import com.cattailsw.nanidroid.shiori.NanidroidShiori;
import com.cattailsw.nanidroid.shiori.NotSupportedShiori;
import com.cattailsw.nanidroid.shiori.Kawari;
import java.io.File;
import java.util.Map;
import android.content.Context;

public class ShioriFactory {

    private static ShioriFactory _self = null;
    private ShioriFactory() {

    }

    public static final ShioriFactory getInstance() {
	if ( _self == null )
	    _self = new ShioriFactory();

	return _self;
    }

    // path is the master ghost path 



    public Shiori getShiori(String path, Map<String, String> masterDesc){
	return getShiori(path, masterDesc, null);
    }

    public Shiori getShiori(String path, Map<String, String> masterDesc, Context ctx) {
	// need to first read the shiori dll file name from descript.txt
	String sdllname = masterDesc.get("shiori");

	if ( sdllname.matches("Nanidroid") ) {
	    return new NanidroidShiori(ctx, path);
	}
	else if ( sdllname.matches("satori.dll") ) {
	    return new SatoriPosixShiori(path);
	}
	else if ( sdllname.matches("shiori.dll") ) {
	    // need to do some more checking... argh
	    if ( (new File(path, "kawarirc.kis")).exists() ) {
		return new Kawari(path); // should be kawari878
	    }
	    else if ( (new File(path, "kawari.ini") ).exists() ) {
		return new Kawari(path); // kawari 7 or something like this
	    }
	    else if ( (new File(path, "aya5.txt")).exists()) {
		return new NotSupportedShiori(ctx); // assume yaya
	    }	    
	}
	else if ( sdllname.matches("yaya.dll") ) {
	    return new NotSupportedShiori(ctx); // should be yaya
	}

	return new NotSupportedShiori(ctx);
    }

}
