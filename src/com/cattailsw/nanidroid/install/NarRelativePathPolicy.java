package com.cattailsw.nanidroid.install;

import java.nio.charset.Charset;
import java.text.Normalizer;
import java.util.Locale;

/** Shared pure relative-path rules for NAR entries and live ghost trees. */
final class NarRelativePathPolicy {
    static final int MAX_DEPTH = 32;
    static final int MAX_PATH_BYTES = 1024;
    static final int MAX_COMPONENT_BYTES = 255;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private NarRelativePathPolicy() {}

    static Result normalize(String raw) {
        if (raw == null
                || raw.length() == 0
                || raw.startsWith("/")
                || raw.indexOf('\\') >= 0
                || !validUnicode(raw)) {
            return Result.failure(Error.INVALID_PATH);
        }
        String[] components = raw.split("/", -1);
        if (components.length > MAX_DEPTH) {
            return Result.failure(Error.PATH_DEPTH_LIMIT);
        }
        StringBuilder normalized = new StringBuilder();
        try {
            for (String component : components) {
                if (component.length() == 0
                        || ".".equals(component)
                        || "..".equals(component)
                        || component.indexOf(':') >= 0
                        || containsControl(component)) {
                    return Result.failure(Error.INVALID_PATH);
                }
                String nfc = Normalizer.normalize(
                        component, Normalizer.Form.NFC);
                if (nfc.getBytes(UTF_8).length
                        > MAX_COMPONENT_BYTES) {
                    return Result.failure(
                            Error.COMPONENT_LENGTH_LIMIT);
                }
                if (normalized.length() > 0) {
                    normalized.append('/');
                }
                normalized.append(nfc);
            }
            String path = normalized.toString();
            if (path.getBytes(UTF_8).length > MAX_PATH_BYTES) {
                return Result.failure(Error.PATH_LENGTH_LIMIT);
            }
            return Result.success(
                    path, collisionKey(path));
        } catch (RuntimeException error) {
            return Result.failure(Error.INVALID_PATH);
        }
    }

    static String collisionKey(String value) {
        String nfc = Normalizer.normalize(
                value, Normalizer.Form.NFC);
        return Normalizer.normalize(
                nfc.toLowerCase(Locale.US),
                Normalizer.Form.NFC);
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    private static boolean validUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length()
                        || !Character.isLowSurrogate(
                                value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    enum Error {
        INVALID_PATH,
        PATH_DEPTH_LIMIT,
        PATH_LENGTH_LIMIT,
        COMPONENT_LENGTH_LIMIT
    }

    static final class Result {
        private final String normalized;
        private final String key;
        private final Error error;

        private Result(
                String normalized, String key, Error error) {
            this.normalized = normalized;
            this.key = key;
            this.error = error;
        }

        private static Result success(
                String normalized, String key) {
            return new Result(normalized, key, null);
        }

        private static Result failure(Error error) {
            return new Result(null, null, error);
        }

        boolean isSuccess() { return error == null; }
        String getNormalized() { return normalized; }
        String getKey() { return key; }
        Error getError() { return error; }
    }
}
