package com.cattailsw.nanidroid.test;

import junit.framework.TestCase;

import com.cattailsw.nanidroid.ShioriResponse;
import org.apache.http.ProtocolVersion;
import com.cattailsw.nanidroid.PatternHolders;
import java.util.regex.Matcher;

public class ShioriResponseTest extends TestCase {
    private static final String TAG = "ShioriResponseTest";

    public void testGetResVersionPtrn() {
	String res_header = "SHIORI/3.0 200 OK";
	Matcher m = PatternHolders.shiori_res_header_ptrn.matcher(res_header);
	assertTrue(m.matches());
	assertEquals("SHIORI", m.group(1));
	assertEquals("3", m.group(2));
	assertEquals("0", m.group(3));
	assertEquals("200", m.group(4));
	assertEquals("OK", m.group(5));

	res_header = "SHIORI/2.0 500 INTERNAL ERROR";
	m = PatternHolders.shiori_res_header_ptrn.matcher(res_header);
	assertTrue(m.matches());
	assertEquals("SHIORI", m.group(1));
	assertEquals("2", m.group(2));
	assertEquals("0", m.group(3));
	assertEquals("500", m.group(4));
	assertEquals("INTERNAL ERROR", m.group(5));

	res_header = "SHIORI/2.0 500";
	m = PatternHolders.shiori_res_header_ptrn.matcher(res_header);
	assertTrue(m.matches());
	assertEquals("SHIORI", m.group(1));
	assertEquals("2", m.group(2));
	assertEquals("0", m.group(3));
	assertEquals("500", m.group(4));
	assertEquals("", m.group(5));
	
    }


    public void testHeaderParsing() {
	String res_header = "SHIORI/3.0 200 OK";
	
	ShioriResponse r = new ShioriResponse(res_header);

	ProtocolVersion p = r.getProtocolVersion();
	assertNotNull(p);
	assertEquals("SHIORI", p.getProtocol());
	assertEquals(3, p.getMajor());
	assertEquals(0, p.getMinor());
	assertEquals(200, r.getStatusCode());

	res_header = "SHIORI/2.0 500 INTERNAL ERROR";
	
	r = new ShioriResponse(res_header);

	p = r.getProtocolVersion();
	assertNotNull(p);
	assertEquals("SHIORI", p.getProtocol());
	assertEquals(2, p.getMajor());
	assertEquals(0, p.getMinor());
	assertEquals(500, r.getStatusCode());
	

	res_header = "SHIORI/1.1 400 BAD REQUEST";
	
	r = new ShioriResponse(res_header);

	p = r.getProtocolVersion();
	assertNotNull(p);
	assertEquals("SHIORI", p.getProtocol());
	assertEquals(1, p.getMajor());
	assertEquals(1, p.getMinor());
	assertEquals(400, r.getStatusCode());


	res_header = "SHIORI/1.1 400";
	
	r = new ShioriResponse(res_header);

	p = r.getProtocolVersion();
	assertNotNull(p);
	assertEquals("SHIORI", p.getProtocol());
	assertEquals(1, p.getMajor());
	assertEquals(1, p.getMinor());
	assertEquals(400, r.getStatusCode());

    }

}
