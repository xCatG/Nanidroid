package com.cattailsw.nanidroid;

public class ShioriFactory {

    private static ShioriFactory _self = null;
    private ShioriFactory() {

    }

    public static final ShioriFactory getInstance() {
	if ( _self == null )
	    _self = new ShioriFactory();

	return _self;
    }

    public Shiori getShiori(String path){
	return new EchoShiori();
    }

}
