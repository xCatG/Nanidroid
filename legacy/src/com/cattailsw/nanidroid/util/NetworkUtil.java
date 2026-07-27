package com.cattailsw.nanidroid.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

/** Frozen Ant-build compatibility shim; Gradle uses NetworkUtil.kt. */
public final class NetworkUtil {
    private static final int TIMEOUT_MILLIS = 20 * 1000;

    private NetworkUtil() {}

    public static boolean exists(Context context, String url) {
        HttpsURLConnection connection = null;
        try {
            connection = open(context, url);
            return connection.getResponseCode() == HttpsURLConnection.HTTP_OK;
        } catch (IOException ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public static InputStream getURLStream(Context context, String url) throws IOException {
        HttpsURLConnection connection = open(context, url);
        int responseCode = connection.getResponseCode();
        if (responseCode < 200 || responseCode >= 300) {
            connection.disconnect();
            throw new IOException("HTTPS request failed: " + responseCode);
        }
        InputStream stream = connection.getInputStream();
        return "gzip".equalsIgnoreCase(connection.getContentEncoding())
                ? new GZIPInputStream(stream) : stream;
    }

    private static HttpsURLConnection open(Context context, String value) throws IOException {
        URL url = requireHttps(value);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("Accept-Encoding", "gzip");
        connection.setRequestProperty("User-Agent", buildUserAgent(context));
        connection.setInstanceFollowRedirects(false);
        return connection;
    }

    static URL requireHttps(String value) throws IOException {
        URL url = new URL(value);
        if (!"https".equalsIgnoreCase(url.getProtocol()) || url.getHost().length() == 0) {
            throw new IOException("Only HTTPS URLs are supported");
        }
        return url;
    }

    private static String buildUserAgent(Context context) {
        if (context == null) return "Nanidroid (gzip)";
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return info.packageName + "/" + info.versionName + " (" + info.versionCode + ") (gzip)";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "Nanidroid (gzip)";
        }
    }
}
