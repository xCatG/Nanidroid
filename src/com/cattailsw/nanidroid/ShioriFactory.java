package com.cattailsw.nanidroid;

import com.cattailsw.nanidroid.shiori.EchoShiori;
import com.cattailsw.nanidroid.shiori.SatoriPosixShiori;
import com.cattailsw.nanidroid.shiori.Shiori;

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
    public Shiori getShiori(String path){

	if( path.indexOf("2elf") != -1 )
	    return new SatoriPosixShiori(path);

	return new EchoShiori();
    }

}
