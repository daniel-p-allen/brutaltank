package com.brutaltank.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Generates short, human-readable match join codes. These are shared between
 * friends verbally/over chat, not passwords, so readability beats keyspace
 * size: single case (uppercase), and the alphabet drops visually ambiguous
 * characters (I/O look like 1/0) so a spoken or handwritten code is
 * unambiguous to retype.
 */
public final class MatchCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int LENGTH = 5;

    private MatchCodeGenerator() {
    }

    public static String generate() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(ThreadLocalRandom.current().nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** Normalizes a user-typed code for lookup: trim whitespace, uppercase. */
    public static String normalize(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }
}
