package com.dragonmeow.nyanlex.translate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Strict parser for the plain-decimal markers sent to machine translators.
 *
 * <p>A marker is recognised only when its complete decimal digit run can be
 * decomposed uniquely into known markers. This preserves intentionally
 * adjacent markers while preventing a response such as {@code 170001} or
 * {@code 700010} from impersonating marker {@code 70001} by substring.</p>
 */
final class NumericMarkerCodec {
    private NumericMarkerCodec() {
    }

    static String restoreExactlyOnce(String text, Map<String, String> replacements) {
        if (text == null) return null;
        if (replacements == null || replacements.isEmpty()) return text;
        Set<String> markers = validatedMarkers(replacements.keySet());
        List<Piece> pieces = tokenize(text, markers);
        Map<String, Integer> counts = new HashMap<>();
        for (Piece piece : pieces) {
            if (piece.marker() != null) {
                counts.merge(piece.marker(), 1, Integer::sum);
            }
        }
        for (String marker : markers) {
            if (counts.getOrDefault(marker, 0) != 1) return null;
        }

        StringBuilder restored = new StringBuilder(text.length());
        for (Piece piece : pieces) {
            if (piece.marker() == null) {
                restored.append(piece.literal());
            } else {
                restored.append(replacements.get(piece.marker()));
            }
        }
        return restored.toString();
    }

    static List<String> extractAnchored(String text, int itemCount,
                                        int markerBase, int markerCount) {
        if (text == null || itemCount < 0 || markerCount < itemCount * 2) return null;
        Set<String> markers = consecutiveMarkers(markerBase, markerCount);
        Set<String> anchors = consecutiveMarkers(markerBase, itemCount * 2);
        List<Piece> pieces = tokenize(text, markers);
        List<String> extracted = new ArrayList<>(itemCount);
        int cursor = 0;

        for (int item = 0; item < itemCount; item++) {
            String open = Integer.toString(markerBase + item * 2);
            String close = Integer.toString(markerBase + item * 2 + 1);
            StringBuilder outside = new StringBuilder();
            while (cursor < pieces.size() && !open.equals(pieces.get(cursor).marker())) {
                Piece piece = pieces.get(cursor++);
                if (piece.marker() != null) return null;
                outside.append(piece.literal());
            }
            if (!outside.toString().isBlank() || cursor >= pieces.size()) return null;
            cursor++; // opening anchor

            StringBuilder part = new StringBuilder();
            while (cursor < pieces.size() && !close.equals(pieces.get(cursor).marker())) {
                Piece piece = pieces.get(cursor++);
                if (piece.marker() != null && anchors.contains(piece.marker())) return null;
                part.append(piece.wireText());
            }
            if (cursor >= pieces.size()) return null;
            cursor++; // closing anchor
            extracted.add(part.toString().strip());
        }

        StringBuilder tail = new StringBuilder();
        while (cursor < pieces.size()) {
            Piece piece = pieces.get(cursor++);
            if (piece.marker() != null) return null;
            tail.append(piece.literal());
        }
        return tail.toString().isBlank() ? extracted : null;
    }

    private static List<Piece> tokenize(String text, Set<String> markers) {
        List<Integer> lengths = markerLengths(markers);
        List<Piece> pieces = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            if (!isAsciiDigit(text.charAt(cursor))) {
                literal.append(text.charAt(cursor++));
                continue;
            }
            int end = cursor + 1;
            while (end < text.length() && isAsciiDigit(text.charAt(end))) end++;
            String run = text.substring(cursor, end);
            List<String> decoded = splitUnique(run, markers, lengths);
            if (decoded == null) {
                literal.append(run);
            } else {
                flushLiteral(pieces, literal);
                for (String marker : decoded) pieces.add(Piece.marker(marker));
            }
            cursor = end;
        }
        flushLiteral(pieces, literal);
        return pieces;
    }

    private static List<String> splitUnique(String run, Set<String> markers,
                                            List<Integer> lengths) {
        int size = run.length();
        byte[] ways = new byte[size + 1];
        String[] choice = new String[size];
        ways[size] = 1;
        for (int position = size - 1; position >= 0; position--) {
            int total = 0;
            String only = null;
            for (int length : lengths) {
                int next = position + length;
                if (next > size || ways[next] == 0) continue;
                String candidate = run.substring(position, next);
                if (!markers.contains(candidate)) continue;
                int added = ways[next];
                if (total == 0 && added == 1) only = candidate;
                else only = null;
                total = Math.min(2, total + added);
            }
            ways[position] = (byte) total;
            if (total == 1) choice[position] = only;
        }
        if (ways[0] != 1) return null;

        List<String> result = new ArrayList<>();
        for (int position = 0; position < size; ) {
            String marker = choice[position];
            if (marker == null) return null;
            result.add(marker);
            position += marker.length();
        }
        return result;
    }

    private static Set<String> consecutiveMarkers(int base, int count) {
        if (base < 0 || count < 0 || (long) base + count > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("numeric marker range");
        }
        Set<String> markers = new HashSet<>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) markers.add(Integer.toString(base + i));
        return markers;
    }

    private static Set<String> validatedMarkers(Set<String> supplied) {
        Set<String> markers = new HashSet<>(Math.max(16, supplied.size() * 2));
        for (String marker : supplied) {
            if (marker == null || marker.isEmpty()) {
                throw new IllegalArgumentException("empty numeric marker");
            }
            for (int i = 0; i < marker.length(); i++) {
                if (!isAsciiDigit(marker.charAt(i))) {
                    throw new IllegalArgumentException("non-numeric marker: " + marker);
                }
            }
            markers.add(marker);
        }
        if (markers.size() != supplied.size()) {
            throw new IllegalArgumentException("duplicate numeric marker");
        }
        return markers;
    }

    private static List<Integer> markerLengths(Set<String> markers) {
        Set<Integer> unique = new LinkedHashSet<>();
        for (String marker : markers) unique.add(marker.length());
        List<Integer> lengths = new ArrayList<>(unique);
        lengths.sort(java.util.Comparator.reverseOrder());
        return lengths;
    }

    private static void flushLiteral(List<Piece> pieces, StringBuilder literal) {
        if (literal.length() == 0) return;
        pieces.add(Piece.literal(literal.toString()));
        literal.setLength(0);
    }

    private static boolean isAsciiDigit(char value) {
        return value >= '0' && value <= '9';
    }

    private record Piece(String literal, String marker) {
        static Piece literal(String value) {
            return new Piece(value, null);
        }

        static Piece marker(String value) {
            return new Piece(null, value);
        }

        String wireText() {
            return marker == null ? literal : marker;
        }
    }
}
