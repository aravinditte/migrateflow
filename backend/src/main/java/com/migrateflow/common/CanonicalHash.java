package com.migrateflow.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical SHA-256 digests (spec section 6): stable UTF-8 serialization with
 * explicitly ordered fields and sorted map keys. Never hashes toString().
 * Null is rendered as the JSON literal null; strings are length-prefixed so
 * field boundaries can never be ambiguous.
 */
public final class CanonicalHash {
    private CanonicalHash() {}

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Canonical form of an ordered list of named fields. */
    public static String canonicalFields(Map<String, ?> fields) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ?> e : new TreeMap<>(fields).entrySet()) {
            sb.append(e.getKey()).append('=');
            appendValue(sb, e.getValue());
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(',');
                appendValue(sb, list.get(i));
            }
            sb.append(']');
        } else {
            String s = String.valueOf(v);
            sb.append(s.length()).append(':').append(s);
        }
    }

    public static String hashFields(Map<String, ?> fields) {
        return sha256Hex(canonicalFields(fields));
    }

    public static String shortFingerprint(String fullHash) {
        return fullHash == null || fullHash.length() < 12 ? fullHash : fullHash.substring(0, 12);
    }
}
