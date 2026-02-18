package com.urlshortener.service;

/**
 * Base62 encoder/decoder: converts long → 7-char string using [0-9, a-z, A-Z].
 * 62^7 = 3.5 trillion combinations.
 */
public class Base62 {

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = ALPHABET.length(); // 62
    private static final int CODE_LENGTH = 7;

    public static String encode(long id) {
        StringBuilder sb = new StringBuilder();
        while (id > 0) {
            sb.append(ALPHABET.charAt((int) (id % BASE)));
            id /= BASE;
        }
        // Pad to CODE_LENGTH
        while (sb.length() < CODE_LENGTH) {
            sb.append('0');
        }
        return sb.reverse().toString();
    }

    public static long decode(String code) {
        long id = 0;
        for (char c : code.toCharArray()) {
            id = id * BASE + ALPHABET.indexOf(c);
        }
        return id;
    }
}
