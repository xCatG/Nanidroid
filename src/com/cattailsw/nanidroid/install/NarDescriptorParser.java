package com.cattailsw.nanidroid.install;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for a defensively snapshotted NAR install descriptor. */
public final class NarDescriptorParser {
    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset SHIFT_JIS = Charset.forName("Shift_JIS");
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int MAX_DESCRIPTOR_BYTES = 64 * 1024;
    private static final int MAX_TARGET_BYTES = 255;
    private static final Pattern COMPOUND_INSTALL_KEY = Pattern.compile(
            "^(?:balloon|headline|plugin|calendar\\.skin|"
                    + "calendar\\.plugin)\\d*\\."
                    + "(directory|source\\.directory|refresh|"
                    + "refreshundeletemask)$");
    private static final Set<String> UNSUPPORTED_TYPES = unsupportedTypes();

    public NarDescriptorResult parse(
            byte[] descriptor,
            String forcedTargetId) {
        if (descriptor == null) {
            return NarDescriptorResult.failure(
                    NarInstallError.INVALID_METADATA, "null descriptor");
        }
        if (descriptor.length > MAX_DESCRIPTOR_BYTES) {
            return NarDescriptorResult.failure(
                    NarInstallError.INSTALL_DESCRIPTOR_LIMIT,
                    "descriptor exceeds 64 KiB");
        }
        byte[] snapshot = snapshot(descriptor);
        try {
            return NarDescriptorResult.success(
                    parseSnapshot(snapshot, forcedTargetId));
        } catch (Rejected rejected) {
            return NarDescriptorResult.failure(
                    rejected.error, rejected.getMessage());
        }
    }

    private static NarInstallDescriptor parseSnapshot(
            byte[] bytes,
            String forcedTargetId) throws Rejected {
        Encoding encoding = selectEncoding(bytes);
        String text = decode(bytes, encoding.offset, encoding.charset);
        Map<String, String> metadata = parseLines(text);
        metadata.put("charset", encoding.charset.name());
        rejectCompoundInstall(metadata);

        if (!metadata.containsKey("type")) {
            reject(NarInstallError.MISSING_TYPE, "type is required");
        }
        String type = metadata.get("type");
        if (type.length() == 0) {
            reject(NarInstallError.INVALID_TYPE, "blank type");
        }
        String normalizedType = collisionKey(type);
        if (!"ghost".equals(normalizedType)) {
            reject(
                    UNSUPPORTED_TYPES.contains(normalizedType)
                            ? NarInstallError.UNSUPPORTED_TYPE
                            : NarInstallError.INVALID_TYPE,
                    normalizedType);
        }
        if (!metadata.containsKey("name")
                || metadata.get("name").length() == 0
                || !metadata.containsKey("directory")
                || metadata.get("directory").length() == 0) {
            reject(
                    NarInstallError.MISSING_METADATA,
                    "name and directory are required");
        }

        String descriptorDirectory = normalizeTarget(
                metadata.get("directory"));
        if (descriptorDirectory == null) {
            reject(NarInstallError.INVALID_TARGET_ID, "unsafe directory");
        }
        String targetId = forcedTargetId == null
                ? descriptorDirectory
                : normalizeTarget(forcedTargetId);
        if (targetId == null) {
            reject(NarInstallError.INVALID_TARGET_ID, "unsafe forced id");
        }
        if ("1".equals(metadata.get("refresh"))) {
            reject(NarInstallError.UNSUPPORTED_REFRESH, "unsupported");
        }

        metadata.put("type", "ghost");
        metadata.put("directory", descriptorDirectory);
        return new NarInstallDescriptor(
                "ghost",
                metadata.get("name"),
                descriptorDirectory,
                targetId,
                metadata.get("accept"),
                metadata);
    }

    static byte[] snapshot(byte[] descriptor) {
        return descriptor.clone();
    }

    private static void rejectCompoundInstall(Map<String, String> metadata)
            throws Rejected {
        boolean compoundInstall = false;
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            Matcher matcher = COMPOUND_INSTALL_KEY.matcher(entry.getKey());
            if (!matcher.matches()) {
                continue;
            }
            compoundInstall = true;
            if ("refresh".equals(matcher.group(1))
                    && "1".equals(entry.getValue())) {
                reject(
                        NarInstallError.UNSUPPORTED_REFRESH,
                        entry.getKey());
            }
        }
        if (compoundInstall) {
            reject(
                    NarInstallError.UNSUPPORTED_COMPOUND_INSTALL,
                    "compound install directive");
        }
    }

    private static Encoding selectEncoding(byte[] bytes) throws Rejected {
        if (bytes.length >= 3
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf) {
            return new Encoding(UTF_8, 3);
        }
        int end = 0;
        while (end < bytes.length
                && bytes[end] != '\n'
                && bytes[end] != '\r') {
            end++;
        }
        String firstLine = new String(bytes, 0, end, ASCII);
        int comma = firstLine.indexOf(',');
        if (comma > 0
                && "charset".equals(collisionKey(
                        firstLine.substring(0, comma).trim()))) {
            String name = firstLine.substring(comma + 1).trim();
            try {
                return new Encoding(Charset.forName(name), 0);
            } catch (RuntimeException error) {
                reject(
                        NarInstallError.UNSUPPORTED_DESCRIPTOR_CHARSET,
                        name);
            }
        }
        return new Encoding(SHIFT_JIS, 0);
    }

    private static String decode(
            byte[] bytes,
            int offset,
            Charset charset) throws Rejected {
        try {
            CharsetDecoder decoder = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(
                    ByteBuffer.wrap(bytes, offset, bytes.length - offset))
                    .toString();
        } catch (CharacterCodingException error) {
            reject(
                    NarInstallError.INVALID_DESCRIPTOR_ENCODING,
                    charset.name());
            return null;
        }
    }

    private static Map<String, String> parseLines(String text)
            throws Rejected {
        Map<String, String> metadata =
                new LinkedHashMap<String, String>();
        String[] lines = text.split("\\r?\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.trim().length() == 0) {
                continue;
            }
            int comma = line.indexOf(',');
            if (comma <= 0) {
                reject(NarInstallError.INVALID_METADATA, "malformed line");
            }
            String key = collisionKey(line.substring(0, comma).trim());
            String value = Normalizer.normalize(
                    line.substring(comma + 1).trim(),
                    Normalizer.Form.NFC);
            if (key.length() == 0
                    || containsControl(key)
                    || containsControl(value)
                    || ("charset".equals(key) && index != 0)
                    || metadata.containsKey(key)) {
                reject(
                        NarInstallError.INVALID_METADATA,
                        "invalid or duplicate metadata");
            }
            metadata.put(key, value);
        }
        return metadata;
    }

    private static String normalizeTarget(String value) {
        if (value == null
                || value.length() == 0
                || value.length() > MAX_TARGET_BYTES
                || !validUnicode(value)
                || hasBoundaryWhitespace(value)
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0
                || containsControl(value)
                || ".".equals(value)
                || "..".equals(value)) {
            return null;
        }
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFC);
        return normalized.getBytes(UTF_8).length <= MAX_TARGET_BYTES
                ? normalized
                : null;
    }

    private static boolean hasBoundaryWhitespace(String value) {
        int first = value.codePointAt(0);
        int last = value.codePointBefore(value.length());
        return Character.isWhitespace(first)
                || Character.isSpaceChar(first)
                || Character.isWhitespace(last)
                || Character.isSpaceChar(last);
    }

    private static String collisionKey(String value) {
        String nfc = Normalizer.normalize(value, Normalizer.Form.NFC);
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
                        || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }

    private static Set<String> unsupportedTypes() {
        Set<String> types = new HashSet<String>();
        types.addAll(Arrays.asList(
                "shell",
                "supplement",
                "balloon",
                "plugin",
                "headline",
                "language",
                "calendar skin",
                "calendar plugin",
                "calendar",
                "package"));
        return Collections.unmodifiableSet(types);
    }

    private static void reject(NarInstallError error, String detail)
            throws Rejected {
        throw new Rejected(error, detail);
    }

    private static final class Encoding {
        private final Charset charset;
        private final int offset;
        private Encoding(Charset charset, int offset) {
            this.charset = charset;
            this.offset = offset;
        }
    }

    private static final class Rejected extends Exception {
        private final NarInstallError error;
        private Rejected(NarInstallError error, String detail) {
            super(detail);
            this.error = error;
        }
    }
}
