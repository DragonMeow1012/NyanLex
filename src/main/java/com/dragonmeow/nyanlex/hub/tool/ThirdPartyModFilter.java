package com.dragonmeow.nyanlex.hub.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Author-only export gate: a row whose source or translation names another client mod
 * (typically a chat message or prefix that a mod prints into the player's chat) is not
 * server content and must never enter the public translation repository.
 *
 * <p>The name list is stored only as SHA-256 hashes of the normalized name (lower case,
 * everything except letters and digits removed), so no third-party name appears in this
 * source. Matching splits the text into words (runs of ASCII letters and digits), forms
 * every 1 to 3 word sequence, normalizes it the same way, and looks the hash up. A
 * possessive "'s" is dropped from the word stream so "Name's Mod" matches "Name Mod".
 * A bracketed single word such as "[abc]" is also checked against a second hash set.</p>
 */
final class ThirdPartyModFilter {
    /** Longest word sequence that is hashed. */
    static final int MAX_NGRAM = 3;

    /** SHA-256 of each normalized name, sorted. */
    static final String[] NAME_HASHES = {
            "024c0037826d3e1d92d2dfd5a12a92f804cf68aaea60788c3d887efb35bed434",
            "0aa468e92a63d00afbb3a8026658f27d6acaaf67613ee399378cac2b2986622d",
            "242d2f23a194483a0aea19c60f86ca2fb887d97edfd2cdfdcf4e2d650a2f79f3",
            "4765dff4448946e6c50092c1750bf13066e1c66747e9b02b6bdd49f14c345f9e",
            "4da38a35ddb424132bd7ee58308d254957aa8e1ec42d5740c3e51a8ce4f032de",
            "52ec33ec7ecc5993cd10231e7d4e5ece43847fe81987c7516368ff81f3b0213e",
            "57cb732a1dec268756582cbf8e059b8d7ac52c45bd8b62632f7b33e7c2179ac0",
            "5f21f1478012d6fdd8d93f98a885b97fb2eb93b4864cfc4863e4445105576e7d",
            "63f31936b96841a417920a7c994ab367233b2c12c421562636c67c88393950e0",
            "6e52eafb048cac20bb5474e8e5e97fd50caf180c6870199425ddadac1b995c13",
            "71022a2a53578be320cddfeaae6007f7ec29bd6dd1bcf9df22b2588c52d357a9",
            "7b2ad399e7d842a1eb235afd8c6343d9f7702dcbb8894e824517c2f83e1d036b",
            "7cdcd61f26da4298fa0b1a7355542c788fc7dd0a005ac6c6b24fd67f9e472e68",
            "82b4ee7fd8ed41ef3ff5e48489c5a02b6aa721d679f3d81ffae9ef74e8c636a6",
            "87b137ee885e661c83489f4b2f303c3d2f8468e3b0a7f0a60d423689dd58c246",
            "91731db9f34b910480810cf39bfe7a1f3a09354b7b4d6c04eced8d4b956dfc42",
            "989db18c071b3c737ae501ff74e9dd95350c92cd8f970f093b9ab07d3695771e",
            "a3431dc141c82f3d4f568e7789fc0a99ab03951ad5a7c07d28eec55a8087197e",
            "b46ba8e1a142d26f91ee4dd9344c7a5fbe72760b4cf9b58cdd02deaa009c140e",
            "b8fccba48fe1d892740ef6fc0973384a4ac116f5221b0803a813b1d48770bcba",
            "c51f479f675797f742c7a2b220da8a1ee13d29748152d4dfa890b3bfa8daa484",
            "f29798f0ee5a6ff7f0c2bbcf9dfe5f124687df8459c91e1228c596c39e50e8a6"
    };

    /** SHA-256 of normalized names that only count when written inside square brackets. */
    static final String[] BRACKET_HASHES = {
            "b1688cbab7e2c8ad7cf619047e25eed6c9e344ed970badcdacd0e9ec73b08626"
    };

    /** SHA-256 over {@code NAME_HASHES} joined by newlines; guards against accidental edits. */
    static final String NAME_HASHES_CHECKSUM =
            "e49114694cad0b0266aec0204dd3b076d8fe134eb9147c5ada368dafe374053e";

    private static final Set<String> DEFAULT_NAMES = Set.of(NAME_HASHES);
    private static final Set<String> DEFAULT_BRACKETS = Set.of(BRACKET_HASHES);

    private ThirdPartyModFilter() {}

    /** True when {@code text} mentions a known third-party mod name. */
    static boolean mentionsMod(String text) {
        return mentionsMod(text, DEFAULT_NAMES, DEFAULT_BRACKETS);
    }

    /** Same check against caller-supplied hash sets (used by tests with made-up names). */
    static boolean mentionsMod(String text, Set<String> nameHashes, Set<String> bracketHashes) {
        if (text == null || text.isEmpty()) return false;
        List<String> words = new ArrayList<>();
        List<Boolean> bracketed = new ArrayList<>();
        int n = text.length();
        int i = 0;
        while (i < n) {
            if (!isAlnum(text.charAt(i))) {
                i++;
                continue;
            }
            int start = i;
            while (i < n && isAlnum(text.charAt(i))) i++;
            String word = text.substring(start, i);
            boolean possessive = word.equalsIgnoreCase("s") && start >= 2
                    && text.charAt(start - 1) == '\'' && isAlnum(text.charAt(start - 2));
            if (possessive) continue;
            words.add(word.toLowerCase(Locale.ROOT));
            bracketed.add(start > 0 && text.charAt(start - 1) == '[' && i < n && text.charAt(i) == ']');
        }
        for (int a = 0; a < words.size(); a++) {
            if (!bracketHashes.isEmpty() && bracketed.get(a)
                    && bracketHashes.contains(sha256Hex(words.get(a)))) {
                return true;
            }
            StringBuilder joined = new StringBuilder();
            for (int len = 1; len <= MAX_NGRAM && a + len <= words.size(); len++) {
                joined.append(words.get(a + len - 1));
                if (nameHashes.contains(sha256Hex(joined.toString()))) return true;
            }
        }
        return false;
    }

    /** Normalize (lower case, letters and digits only) and hash; used to build the lists. */
    static String hashName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if (isAlnum(c)) sb.append(c);
        }
        return sha256Hex(sb.toString());
    }

    /** Checksum of a hash collection in sorted order. */
    static String checksum(Set<String> hashes) {
        return sha256Hex(String.join("\n", new TreeSet<>(hashes)));
    }

    private static boolean isAlnum(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
