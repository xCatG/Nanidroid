package com.cattailsw.nanidroid.util;

import java.io.IOException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.HttpResponse;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.params.HttpParams;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpRequest;
import org.apache.http.protocol.HttpContext;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.HttpEntity;
import org.apache.http.HeaderElement;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.content.Context;
import java.io.InputStream;
import android.content.pm.PackageManager.NameNotFoundException;
import org.apache.http.conn.ssl.SSLSocketFactory;
import android.util.Log;
import org.apache.http.Header;
import android.text.format.DateUtils;
import org.apache.http.entity.HttpEntityWrapper;
import java.util.zip.GZIPInputStream;
import org.apache.http.conn.ssl.AbstractVerifier;
import javax.net.ssl.SSLException;
import org.apache.http.protocol.HTTP;

public class NetworkUtil {
    private static final String TAG = "NetworkUtil";
    private static final int SECOND_IN_MILLIS = (int) DateUtils.SECOND_IN_MILLIS;
    private static final String HEADER_ACCEPT_ENCODING = "Accept-Encoding";
    private static final String ENCODING_GZIP = "gzip";



    public static InputStream getURLStream(Context ctx, String url) throws IOException {
	final HttpClient lClient = getHttpClient(ctx, url.startsWith("https"));
        final HttpUriRequest request = new HttpGet(url);
	HttpResponse resp = lClient.execute(request);
	InputStream is = resp.getEntity().getContent();
	return is;
    }

    private static DefaultHttpClient getDefaultHttpClient(ClientConnectionManager conMgr, HttpParams params, 
							 boolean useHttps) {
	DefaultHttpClient client = new DefaultHttpClient(conMgr, params);
	if ( UIUtil.isAfterEclair() || useHttps == false ) // will use default client in froyo...
	    return client;
	Log.d(TAG, "try to use more tolarate http client");
	// for earlier android that does not think *.domain cert is the same as domain cert...
	SSLSocketFactory sslSocketFactory = (SSLSocketFactory) client
            .getConnectionManager().getSchemeRegistry().getScheme("https")
            .getSocketFactory();

	final X509HostnameVerifier delegate = sslSocketFactory.getHostnameVerifier();
	if(!(delegate instanceof MyVerifier)) {
	    sslSocketFactory.setHostnameVerifier(new MyVerifier(delegate));
	}

	return client;

    }

    public static HttpClient getHttpClient(Context context, boolean useHttps) {
        final HttpParams params = new BasicHttpParams();
	
	HttpConnectionParams.setStaleCheckingEnabled(params, false);

        // Use generous timeouts for slow mobile networks
        HttpConnectionParams.setConnectionTimeout(params, 20 * SECOND_IN_MILLIS);
        HttpConnectionParams.setSoTimeout(params, 20 * SECOND_IN_MILLIS);

        HttpConnectionParams.setSocketBufferSize(params, 8192);
	HttpProtocolParams.setContentCharset(params, HTTP.UTF_8);
        HttpProtocolParams.setUserAgent(params, buildUserAgent(context));

	SchemeRegistry schReg = new SchemeRegistry();
	schReg.register(new Scheme("http", PlainSocketFactory
				   .getSocketFactory(), 80));
	schReg.register(new Scheme("https",
				   SSLSocketFactory.getSocketFactory(), 443));
	ClientConnectionManager conMgr = new ThreadSafeClientConnManager(params, schReg);


        final DefaultHttpClient client = getDefaultHttpClient(conMgr, params, useHttps); //new DefaultHttpClient(conMgr, params);

        client.addRequestInterceptor(new HttpRequestInterceptor() {
            public void process(HttpRequest request, HttpContext context) {
                // Add header to accept gzip content
                if (!request.containsHeader(HEADER_ACCEPT_ENCODING)) {
                    request.addHeader(HEADER_ACCEPT_ENCODING, ENCODING_GZIP);
                }
            }
        });

        client.addResponseInterceptor(new HttpResponseInterceptor() {
            public void process(HttpResponse response, HttpContext context) {
                // Inflate any responses compressed with gzip
                final HttpEntity entity = response.getEntity();
                final Header encoding = entity.getContentEncoding();
                if (encoding != null) {
                    for (HeaderElement element : encoding.getElements()) {
                        if (element.getName().equalsIgnoreCase(ENCODING_GZIP)) {
			    //Log.d(TAG, "using gzip");
                            response.setEntity(new InflatingEntity(response.getEntity()));
                            break;
                        }
                    }
                }
            }
        });

        return client;
    }

    public static HttpClient getHttpClient(Context context) {
	return getHttpClient(context, true);
    }

    /**
     * Build and return a user-agent string that can identify this application
     * to remote servers. Contains the package name and version code.
     */
    private static String buildUserAgent(Context context) {
	if ( context == null )
	    return "cattail software default UA (gzip)";

        try {
            final PackageManager manager = context.getPackageManager();
            final PackageInfo info = manager.getPackageInfo(context.getPackageName(), 0);

            // Some APIs require "(gzip)" in the user-agent string.
            return info.packageName + "/" + info.versionName
                    + " (" + info.versionCode + ") (gzip)";
        } catch (NameNotFoundException e) {
            return null;
        }

    }

    /**
     * Simple {@link HttpEntityWrapper} that inflates the wrapped
     * {@link HttpEntity} by passing it through {@link GZIPInputStream}.
     */
    private static class InflatingEntity extends HttpEntityWrapper {
        public InflatingEntity(HttpEntity wrapped) {
            super(wrapped);
        }

        @Override
        public InputStream getContent() throws IOException {
            return new GZIPInputStream(wrappedEntity.getContent());
        }

        @Override
        public long getContentLength() {
            return -1;
        }
    }

    // from Stackoverflow 
    // http://stackoverflow.com/questions/3135679/android-httpclient-hostname-in-certificate-didnt-match-example-com-exa/3136980#3136980
    static class MyVerifier extends AbstractVerifier {

	private final X509HostnameVerifier delegate;

	public MyVerifier(final X509HostnameVerifier delegate) {
	    this.delegate = delegate;
	}

	public void verify(String host, String[] cns, String[] subjectAlts)
	    throws SSLException {
	    boolean ok = false;
	    try {
		delegate.verify(host, cns, subjectAlts);
	    } catch (SSLException e) {
		for (String cn : cns) {
		    if (cn.startsWith("*.")) {
			try {
			    delegate.verify(host, new String[] { 
						cn.substring(2) }, subjectAlts);
			    ok = true;
			} catch (Exception e1) { }
		    }
		}
		if(!ok) throw e;
	    }
	}
    }

}
