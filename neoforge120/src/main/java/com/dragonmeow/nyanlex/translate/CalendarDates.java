package com.dragonmeow.nyanlex.translate;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * English calendar dates as one unit: recognised by {@link TemplateText} as a single opaque
 * slot, and converted to "2026年10月14日" for Chinese-target display. Supported shapes:
 * {@code Oct 14, 2026}, {@code October 14th, 2026}, {@code Oct 14 2026},
 * {@code 14 Oct 2026}, {@code 14th of October, 2026} and {@code 10/14/2026}
 * (month/day unless the first number cannot be a month).
 */
public final class CalendarDates {
    private static final String MONTH =
            "(?:January|February|March|April|May|June|July|August|September|October"
                    + "|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sept?|Oct|Nov|Dec)";
    private static final String DAY = "\\d{1,2}(?:st|nd|rd|th)?";
    private static final String MONTH_FIRST =
            "(" + MONTH + ")\\.?\\s+(" + DAY + "),?\\s+(\\d{4})";
    private static final String DAY_FIRST =
            "(" + DAY + ")\\s+(?:of\\s+)?(" + MONTH + ")\\.?,?\\s+(\\d{4})";
    private static final String SLASH = "(\\d{1,2})/(\\d{1,2})/(\\d{4})";

    /** Regex body (three alternatives, nine capture groups) without any boundary guard. */
    static final String REGEX = "(?:" + MONTH_FIRST + "|" + DAY_FIRST + "|" + SLASH + ")"
            + "(?![A-Za-z0-9/])";

    private static final Pattern DATE = Pattern.compile("(?i)(?<![A-Za-z0-9/])" + REGEX);
    private static final Map<String, Integer> MONTHS = months();

    private CalendarDates() {}

    private static Map<String, Integer> months() {
        Map<String, Integer> months = new HashMap<>();
        String[] full = {"january", "february", "march", "april", "may", "june", "july",
                "august", "september", "october", "november", "december"};
        for (int i = 0; i < full.length; i++) months.put(full[i], i + 1);
        months.put("jan", 1); months.put("feb", 2); months.put("mar", 3); months.put("apr", 4);
        months.put("jun", 6); months.put("jul", 7); months.put("aug", 8);
        months.put("sep", 9); months.put("sept", 9);
        months.put("oct", 10); months.put("nov", 11); months.put("dec", 12);
        return Collections.unmodifiableMap(months);
    }

    /** Cheap pre-filter: every supported date contains a four-digit year. */
    public static boolean mayContainDate(String text) {
        if (text == null) return false;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                if (++run >= 4) return true;
            } else {
                run = 0;
            }
        }
        return false;
    }

    /** Replace every supported English date in {@code text} with "yyyy年M月d日". */
    public static String toChinese(String text) {
        if (!mayContainDate(text)) return text;
        Matcher matcher = DATE.matcher(text);
        StringBuilder out = null;
        int last = 0;
        while (matcher.find()) {
            int year, month, day;
            try {
                if (matcher.group(1) != null) {
                    month = monthNumber(matcher.group(1));
                    day = dayNumber(matcher.group(2));
                    year = Integer.parseInt(matcher.group(3));
                } else if (matcher.group(4) != null) {
                    day = dayNumber(matcher.group(4));
                    month = monthNumber(matcher.group(5));
                    year = Integer.parseInt(matcher.group(6));
                } else {
                    int first = Integer.parseInt(matcher.group(7));
                    int second = Integer.parseInt(matcher.group(8));
                    year = Integer.parseInt(matcher.group(9));
                    if (first > 12) { day = first; month = second; }
                    else { month = first; day = second; }
                }
            } catch (NumberFormatException notADate) {
                continue;
            }
            if (month < 1 || month > 12 || day < 1 || day > 31) continue;
            if (out == null) out = new StringBuilder(text.length());
            out.append(text, last, matcher.start());
            out.append(year).append('年').append(month).append('月').append(day).append('日');
            last = matcher.end();
        }
        if (out == null) return text;
        out.append(text, last, text.length());
        return out.toString();
    }

    private static int monthNumber(String name) {
        Integer value = MONTHS.get(name.toLowerCase(Locale.ROOT));
        if (value == null) throw new NumberFormatException(name);
        return value;
    }

    private static int dayNumber(String raw) {
        int end = 0;
        while (end < raw.length() && Character.isDigit(raw.charAt(end))) end++;
        return Integer.parseInt(raw.substring(0, end));
    }
}
