package com.dragonmeow.nyanslate.translate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Recognises a SkyBlock item-stat row — {@code "Strength: +10"}, {@code "Crit Chance:
 * +10%"}, {@code "True Defense: 50"} — glued by {@link
 * com.dragonmeow.nyanslate.fabric.FabricTextStyle#joinParagraph} to unrelated neighbouring
 * rows (a rarity line, a trade field, …) with no blank line between them into one
 * otherwise opaque multi-row paragraph key.
 *
 * <p>Once isolated, {@link TemplateText} neutralises the numeric value the moment the row
 * is requested/cached standalone ({@code "Strength: +10"} and {@code "Strength: +50"}
 * both key to {@code "Strength: ⟦MT0⟧"}), so — exactly like {@link TradeLineComposer} —
 * this composer's only job is BOUNDARY detection: which rows are a stat line, so {@link
 * com.dragonmeow.nyanslate.service.TranslationService} can treat each one as its own
 * independent, cacheable unit. {@link Item#text()} is always the WHOLE raw row.</p>
 *
 * <p>Deliberately requires the label to start with an uppercase ASCII letter (never a
 * {@code ⟦n⟧} masked token or a digit) and the colon to be followed immediately by a
 * signed/unsigned number, mirroring {@code ChatLineClassifier.STAT_LINE} — so a masked
 * chat-style {@code "⟦0⟧: 123"} line, or a "Seller: Name" trade row (no digit right after
 * the colon), can never be misjudged as a stat line.</p>
 *
 * <p>The value may ALSO be an already-templated {@code ⟦MTn⟧} slot instead of a literal
 * digit — real Hypixel data confirms {@link com.dragonmeow.nyanslate.translate.TemplateText}
 * folds the sign/percent suffix INTO the same slot ({@code "Strength: +10"} templates to
 * {@code "Strength: ⟦MT0⟧"}, never {@code "Strength: +⟦MT0⟧"}), but a stored on-disk AI-cache
 * KEY (the export-tool path, see {@code HubExportTool#splitLegacyWholeRows}) has already been
 * through that templating pass, while the live render path ({@link TooltipSegmentPlanner})
 * sees the PRE-template literal digit — both must match the SAME composer (2026-10-01
 * real-cache calibration: ~6% of otherwise-abandoned multi-row paragraphs were a stat/scoreboard
 * row blocked on exactly this, e.g. {@code "You have: ⟦MT0⟧ Gems"}, {@code "⟦CS0⟧Accessory
 * Power:⟦/CS0⟧ ⟦CS1⟧⟦MT0⟧⟦/CS1⟧ ⟦CS2⟧"}). A stray external sign before the token
 * ({@code "+⟦MT0⟧"}) is accepted too though never observed in practice — a harmless
 * superset, still gated by the mandatory {@code Label:} prefix. */
public final class StatsLineComposer {

    private static final Pattern STAT_ROW = Pattern.compile(
            "^\\s*[A-Z][A-Za-z '()/-]{1,30}:\\s*[+-]?(?:[0-9]\\S*"
                    + "|\\u27E6\\s*MT\\s*\\d+\\s*\\u27E7\\S*).*$");

    /** One claimed stat row: {@code [rawStart, rawEnd)} is the WHOLE row. */
    public static final class Item {
        private final String text;
        private final int rawStart;
        private final int rawEnd;

        Item(String text, int rawStart, int rawEnd) {
            this.text = text;
            this.rawStart = rawStart;
            this.rawEnd = rawEnd;
        }

        public String text() {
            return text;
        }

        public int rawStart() {
            return rawStart;
        }

        public int rawEnd() {
            return rawEnd;
        }
    }

    /** A recognised set of stat rows, in row order. */
    public static final class Match {
        private final List<Item> items;

        Match(List<Item> items) {
            this.items = items;
        }

        public List<Item> items() {
            return items;
        }

        public List<String> texts() {
            LinkedHashSet<String> distinct = new LinkedHashSet<>();
            for (Item item : items) distinct.add(item.text());
            return List.copyOf(distinct);
        }

        /** Splice every item's resolved row text into {@code rawLine}; {@code null} when
         *  {@code resolver} cannot resolve one of {@link #texts()} yet. */
        public String compose(String rawLine, Function<String, String> resolver) {
            StringBuilder out = new StringBuilder(rawLine.length() + 16);
            int cursor = 0;
            for (Item item : items) {
                String value = resolver.apply(item.text());
                if (value == null) return null;
                out.append(rawLine, cursor, item.rawStart());
                out.append(value);
                cursor = item.rawEnd();
            }
            out.append(rawLine, cursor, rawLine.length());
            return out.toString();
        }
    }

    private StatsLineComposer() {
    }

    /** Shape test for exactly ONE row. Package-visible for {@link
     *  TooltipSegmentPlanner}'s row-by-row classification. */
    static boolean matchesRow(String rowText) {
        if (rowText == null) return false;
        String projected = TextFilter.stripFormatting(rowText).strip();
        if (projected.isEmpty()) return false;
        return STAT_ROW.matcher(projected).matches();
    }

    /** {@code null} when {@code text} holds no stat row at all; otherwise every {@code
     *  ⟦PBn⟧}-delimited row of {@code text} that independently matches the stat shape. */
    public static Match match(String text) {
        if (text == null || text.length() < 4) return null;
        List<int[]> rows = ParagraphModel.splitRawRows(text);
        if (rows == null || rows.isEmpty()) return null;
        List<Item> items = null;
        for (int[] row : rows) {
            String rowText = text.substring(row[0], row[1]);
            if (matchesRow(rowText)) {
                // See TradeLineComposer#trimmedSpan: a row's leading/trailing space from
                // the ⟦PBn⟧ join padding must not leak into the cache key.
                int[] trimmed = TradeLineComposer.trimmedSpan(text, row[0], row[1]);
                if (trimmed[0] < trimmed[1]) {
                    if (items == null) items = new ArrayList<>();
                    items.add(new Item(text.substring(trimmed[0], trimmed[1]), trimmed[0], trimmed[1]));
                }
            }
        }
        return items == null ? null : new Match(List.copyOf(items));
    }
}
