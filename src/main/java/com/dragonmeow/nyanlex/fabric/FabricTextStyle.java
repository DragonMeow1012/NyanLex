package com.dragonmeow.nyanlex.fabric;

import com.dragonmeow.nyanlex.config.DisplayMode;
import com.dragonmeow.nyanlex.service.TranslationDecision;
import com.dragonmeow.nyanlex.style.ColorProfile;
import com.dragonmeow.nyanlex.translate.ParagraphModel;
import com.dragonmeow.nyanlex.translate.TextFilter;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Mojang-mapped counterpart of the Fabric {@code TextStyleSupport}: bridges
 * Minecraft's {@link Component} and the loader-agnostic {@link ColorProfile},
 * so the colour-preservation logic is shared with Fabric.
 */
public final class FabricTextStyle {

    private FabricTextStyle() {
    }

    /** Resolve literal legacy section codes into component style runs. */
    public static Component resolveLegacyCodes(Component source) {
        if (source == null || source.getString().indexOf('§') < 0) return source;
        MutableComponent out = Component.empty();
        source.visit((base, value) -> {
            Style current = base;
            StringBuilder chunk = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch != '§' || i + 1 >= value.length()) {
                    chunk.append(ch);
                    continue;
                }
                if (!chunk.isEmpty()) {
                    out.append(Component.literal(chunk.toString()).setStyle(current));
                    chunk.setLength(0);
                }
                current = applyLegacyCode(current, base,
                        Character.toLowerCase(value.charAt(++i)));
            }
            if (!chunk.isEmpty()) out.append(Component.literal(chunk.toString()).setStyle(current));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    private static Style applyLegacyCode(Style current, Style base, char code) {
        int rgb = switch (code) {
            case '0' -> 0x000000; case '1' -> 0x0000AA; case '2' -> 0x00AA00;
            case '3' -> 0x00AAAA; case '4' -> 0xAA0000; case '5' -> 0xAA00AA;
            case '6' -> 0xFFAA00; case '7' -> 0xAAAAAA; case '8' -> 0x555555;
            case '9' -> 0x5555FF; case 'a' -> 0x55FF55; case 'b' -> 0x55FFFF;
            case 'c' -> 0xFF5555; case 'd' -> 0xFF55FF; case 'e' -> 0xFFFF55;
            case 'f' -> 0xFFFFFF;
            default -> -1;
        };
        if (rgb >= 0) return interactiveOnly(base).withColor(TextColor.fromRgb(rgb));
        return switch (code) {
            case 'k' -> current.withObfuscated(true);
            case 'l' -> current.withBold(true);
            case 'm' -> current.withStrikethrough(true);
            case 'n' -> current.withUnderlined(true);
            case 'o' -> current.withItalic(true);
            case 'r' -> interactiveOnly(base);
            default -> current;
        };
    }

    private static Style interactiveOnly(Style base) {
        Style out = Style.EMPTY;
        if (base.getClickEvent() != null) out = out.withClickEvent(base.getClickEvent());
        if (base.getHoverEvent() != null) out = out.withHoverEvent(base.getHoverEvent());
        if (base.getInsertion() != null) out = out.withInsertion(base.getInsertion());
        return out;
    }

    /** Surfaces whose 原文＋翻譯 can render as two stacked lines (they wrap, or our mixin splits '\n'). */
    private static final java.util.Set<String> STACKABLE = java.util.Set.of(
            "book", "nameTag", "bossBar", "actionBar", "questText");

    /** Vertical gap (px, in the surface's text space) between stacked 原文 / 譯文 lines — a touch
     *  wider than the ~9px font so the two lines have clear breathing room and don't touch. */
    public static final int STACK_LINE_GAP = 12;

    /**
     * Per-frame render helper: styled translation for {@code source}, or {@code null}
     * to keep the original. The translation DECISION is memoised per source text, but
     * the styled component is rebuilt from the CURRENT source's {@link ColorProfile}
     * on every call — a colour-cycling line (rainbow "SKYBLOCK" title) keeps animating
     * after translation instead of freezing on the first frame's colours.
     *
     * <p>The memo key is prefixed with {@code surfaceId} so each surface honours its
     * own per-surface mode/engine gate — otherwise an ON surface's translation would
     * leak to an OFF surface (or an AI surface's to a Google surface) sharing the
     * same source string.</p>
     */
    public static Component renderTranslated(String surfaceId, Component source,
                                             Function<String, TranslationDecision> translateFn) {
        if (source == null) return null;
        LangProbeGlue.observe(surfaceId, source);
        source = resolveLegacyCodes(source);
        Rendered rendered = translateParagraphBlock(source, translateFn);
        if (rendered == null) return null;
        Component translated = rendered.component();
        // 原文＋翻譯 handling per surface:
        //  - "tooltip" (and chat): the 原文/分隔線/翻譯 BLOCK is built by the caller, so here we
        //    return TRANSLATION-ONLY (otherwise the block's translation line wrongly shows both).
        //  - STACKABLE surfaces ("book" wraps via Font.split; "nameTag" & "bossBar" are drawn by our
        //    own mixin which splits on '\n') → newline genuinely stacks 原文 line / 譯文 line.
        //  - everything else (GUI single-label text e.g. settings screens, title / action bar / held /
        //    scoreboard) is on a FIXED single row that can't gain a line, so INLINE as 原文　譯文.
        if (rendered.mode() == DisplayMode.BOTH && !"tooltip".equals(surfaceId)) {
            return STACKABLE.contains(surfaceId)
                    ? stackAligned(source, translated)
                    : source.copy().append(Component.literal("　")).append(translated);
        }
        return translated;
    }

    private record Rendered(Component component, DisplayMode mode) {
    }

    private static Rendered translateOne(Component source,
                                         Function<String, TranslationDecision> translateFn) {
        String src = source.getString();
        if (src.isEmpty()) return null;
        MarkedChat markers = markChatContent(source, 0);
        String request = markers.marked() ? markers.text() : src;
        TranslationDecision decision = translateFn.apply(request);
        if (decision == null || !decision.changed()) return null;
        return new Rendered(rebuildRich(source, decision.translated(), markers), decision.mode());
    }

    /** Every multi-line surface is a list of semantic paragraphs, never a list of
     * unrelated translation rows. Blank lines and prose indentation are the only
     * generic boundaries; a one-line surface is simply a one-row paragraph. */
    private static Rendered translateParagraphBlock(
            Component source, Function<String, TranslationDecision> translateFn) {
        List<Component> lines = splitStyledLines(source);
        if (lines.size() <= 1) return translateOne(source, translateFn);
        MutableComponent out = Component.empty();
        DisplayMode mode = null;
        boolean changed = false;
        boolean firstOutput = true;
        for (int[] range : paragraphRanges(lines)) {
            if (!firstOutput) out.append(Component.literal("\n"));
            firstOutput = false;
            Component first = lines.get(range[0]);
            if (first == null || first.getString().isBlank()) {
                if (first != null) out.append(first.copy());
                continue;
            }
            List<Component> paragraph = lines.subList(range[0], range[1] + 1);
            Rendered rendered = translateParagraph(paragraph, translateFn);
            if (rendered == null) {
                out.append(joinStyledLines(paragraph));
            } else {
                out.append(rendered.component());
                if (mode == null) mode = rendered.mode();
                changed = true;
            }
        }
        return changed ? new Rendered(out, mode) : null;
    }

    private static Rendered translateParagraph(
            List<Component> paragraph, Function<String, TranslationDecision> translateFn) {
        if (paragraph.size() == 1) return translateOne(paragraph.get(0), translateFn);
        Component joined = resolveLegacyCodes(joinParagraph(paragraph));
        MarkedChat marked = markChatContent(joined, 0);
        String request = marked.marked() ? marked.text() : joined.getString();
        TranslationDecision decision = translateFn.apply(request);
        if (decision == null || !decision.changed()) return null;
        MutableComponent rebuilt;
        if (ParagraphModel.validBreakSequence(decision.translated(),
                ParagraphModel.countBreakTokens(request))) {
            rebuilt = restoreParagraphBreaks(rebuildRich(joined, decision.translated(), marked));
        } else {
            // The model dropped/duplicated/reordered a PB token: restoring on the surviving
            // tokens would misplace every following row. Flatten instead — one flowing block.
            rebuilt = rebuildRich(joined,
                    ParagraphModel.flattenBreakTokens(decision.translated()), marked);
        }
        return new Rendered(rebuilt, decision.mode());
    }

    public static void clearRenderMemo() {
        // Kept as a compatibility hook for loader screens; render decisions are no
        // longer duplicated outside TranslationCache.
    }

    // ---- wrapped-sentence tooltips: join → translate whole → re-wrap ----

    /** True when {@code next} continues a server-wrapped lore sentence that {@code prev} starts
     * (see {@link ParagraphModel#continuesWrappedSentence}). Independent stat/enchantment rows
     * deliberately stay separate. */
    public static boolean continuesSentence(String prev, String next) {
        return ParagraphModel.continuesWrappedSentence(prev, next);
    }

    /** Join visual lore rows into one styled semantic sentence. */
    public static MutableComponent joinLines(List<Component> group) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < group.size(); i++) {
            if (i > 0) out.append(Component.literal(" "));
            Component line = group.get(i);
            if (line == null) continue;
            for (Seg seg : segments(line)) {
                out.append(Component.literal(seg.text()).setStyle(seg.style()));
            }
        }
        return out;
    }

    public static String joinPlain(List<Component> group) {
        StringBuilder sb = new StringBuilder();
        for (Component c : group) {
            if (c == null) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.getString().strip());
        }
        return sb.toString();
    }

    public static String groupRequestText(List<Component> group) {
        Component joined = resolveLegacyCodes(joinLines(group));
        MarkedChat marked = markChatContent(joined, 0);
        return marked.marked() ? marked.text() : joinPlain(group);
    }

    public static List<Component> splitToWidth(Component styled, int width, Font font) {
        List<Component> out = new ArrayList<>();
        for (FormattedText line : font.getSplitter().splitLines(
                styled, Math.max(60, width), Style.EMPTY)) {
            out.add(toComponent(line));
        }
        if (out.isEmpty()) out.add(styled.copy());
        return out;
    }

    /** Translate a visual wrap group atomically, then wrap the translated sentence back
     * to the original tooltip width. Until the one semantic cache entry is ready, every
     * source row remains unchanged—there is no half-translated paragraph state. */
    public static List<Component> renderTranslatedGroup(
            List<Component> group, Function<String, TranslationDecision> translateFn,
            Font font) {
        Component joined = resolveLegacyCodes(joinLines(group));
        MarkedChat marked = markChatContent(joined, 0);
        String request = marked.marked() ? marked.text() : joinPlain(group);
        TranslationDecision decision = translateFn.apply(request);
        if (decision == null || !decision.changed()) return null;
        MutableComponent translated = rebuildRich(joined, decision.translated(), marked);
        int width = 0;
        for (Component c : group) if (c != null) width = Math.max(width, font.width(c));
        return splitToWidth(translated, width, font);
    }

    private static final java.util.regex.Pattern PARAGRAPH_BREAK =
            ParagraphModel.BREAK_TOKEN_PATTERN;

    /** Join every visible row in one blank-line-delimited information paragraph while
     * retaining explicit protected line breaks. The model receives one semantic unit.
     * A lower-case sentence continuation (a server-side visual wrap) is joined back with
     * a single space instead of a PB token, so the model translates the whole sentence
     * naturally; PB indices stay contiguous 0..n-1 for sequence validation. */
    private static MutableComponent joinParagraph(List<Component> paragraph) {
        MutableComponent out = Component.empty();
        int breaks = 0;
        String prevPlain = null;
        for (int i = 0; i < paragraph.size(); i++) {
            Component line = paragraph.get(i);
            String plain = (line == null) ? "" : line.getString();
            if (i > 0) {
                if (continuesSentence(prevPlain, plain)) {
                    out.append(Component.literal(" "));
                } else {
                    out.append(Component.literal(ParagraphModel.breakToken(breaks++)));
                }
            }
            if (line != null) {
                for (Seg seg : segments(line)) {
                    out.append(Component.literal(seg.text()).setStyle(seg.style()));
                }
            }
            prevPlain = plain;
        }
        return out;
    }

    public static String paragraphRequestText(List<Component> paragraph) {
        Component joined = resolveLegacyCodes(joinParagraph(paragraph));
        MarkedChat marked = markChatContent(joined, 0);
        return marked.marked() ? marked.text() : joined.getString();
    }

    /** Translate one information paragraph, restore its protected rows, then re-wrap any
     * expanded Traditional-Chinese row to the original pixel width. */
    public static List<Component> renderTranslatedParagraph(
            List<Component> paragraph, Function<String, TranslationDecision> translateFn,
            Font font) {
        Component joined = resolveLegacyCodes(joinParagraph(paragraph));
        MarkedChat marked = markChatContent(joined, 0);
        String request = marked.marked() ? marked.text() : joined.getString();
        TranslationDecision decision = translateFn.apply(request);
        if (decision == null || !decision.changed()) return null;
        int expected = ParagraphModel.countBreakTokens(request);
        List<Component> hardLines;
        if (ParagraphModel.validBreakSequence(decision.translated(), expected)) {
            hardLines = splitStyledLines(restoreParagraphBreaks(
                    rebuildRich(joined, decision.translated(), marked)));
        } else {
            // Scrambled PB sequence: hard row boundaries are unrecoverable. Flatten the
            // whole paragraph into one flowing block and let splitToWidth re-wrap it.
            String flowed = ParagraphModel.flattenBreakTokens(decision.translated());
            hardLines = List.of(rebuildRich(joined, flowed, marked));
        }
        int width = 0;
        for (Component line : paragraph) if (line != null) width = Math.max(width, font.width(line));
        List<Component> out = new ArrayList<>();
        for (Component line : hardLines) {
            out.addAll(splitToWidth(line, width, font));
        }
        return out;
    }

    private static MutableComponent restoreParagraphBreaks(Component styled) {
        List<Seg> runs = segments(styled);
        StringBuilder plain = new StringBuilder();
        for (Seg run : runs) plain.append(run.text());
        MutableComponent out = Component.empty();
        java.util.regex.Matcher matcher = PARAGRAPH_BREAK.matcher(plain);
        int cursor = 0;
        while (matcher.find()) {
            appendStyledRange(out, runs, cursor, matcher.start());
            out.append(Component.literal("\n"));
            cursor = matcher.end();
        }
        appendStyledRange(out, runs, cursor, plain.length());
        return out;
    }

    /** Copy a visible character range even when a protected PB token was split across
     * multiple colour/style runs by semantic style projection. */
    private static void appendStyledRange(
            MutableComponent out, List<Seg> runs, int start, int end) {
        if (start >= end) return;
        int offset = 0;
        for (Seg run : runs) {
            int runStart = offset;
            int runEnd = offset + run.text().length();
            offset = runEnd;
            int from = Math.max(start, runStart);
            int to = Math.min(end, runEnd);
            if (from < to) {
                out.append(Component.literal(run.text().substring(
                        from - runStart, to - runStart)).setStyle(run.style()));
            }
            if (runEnd >= end) break;
        }
    }

    /** One request per prose/information paragraph; blank rows stay in context as boundaries. */
    public static List<String> paragraphRequests(Component source) {
        List<Component> lines = splitStyledLines(resolveLegacyCodes(source));
        List<String> out = new ArrayList<>();
        for (int[] range : paragraphRanges(lines)) {
            Component first = lines.get(range[0]);
            if (first == null || first.getString().isBlank()) {
                out.add("");
            } else {
                out.add(paragraphRequestText(lines.subList(range[0], range[1] + 1)));
            }
        }
        return out;
    }

    /** Paragraph-aware page/log rendering. There is intentionally no first-line title
     * assumption here; blank rows and two-column paragraph indentation are the only cuts. */
    public static Component renderTranslatedParagraphPage(
            Component source, Function<String, TranslationDecision> translateFn, Font font) {
        Component resolved = resolveLegacyCodes(source);
        Rendered rendered = translateParagraphBlock(resolved, translateFn);
        return rendered == null ? null : rendered.component();
    }

    private static List<int[]> paragraphRanges(List<Component> lines) {
        List<int[]> ranges = new ArrayList<>();
        List<String> text = new ArrayList<>(lines.size());
        for (Component line : lines) text.add(line == null ? null : line.getString());
        for (ParagraphModel.Range range : ParagraphModel.ranges(text)) {
            ranges.add(new int[] {range.start(), range.end()});
        }
        return ranges;
    }
    /** Rebuild rich formatted text without flattening its style runs. */
    public static MutableComponent toComponent(FormattedText line) {
        MutableComponent out = Component.empty();
        line.visit((style, str) -> {
            if (!str.isEmpty()) out.append(Component.literal(str).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** Rebuild a pre-laid-out GUI line without flattening its per-character Style. */
    public static MutableComponent toComponent(FormattedCharSequence line) {
        MutableComponent out = Component.empty();
        if (line != null) {
            line.accept((index, style, codePoint) -> {
                out.append(Component.literal(new String(Character.toChars(codePoint))).setStyle(style));
                return true;
            });
        }
        return out;
    }

    public static ColorProfile extract(Component text) {
        if (text == null) return ColorProfile.empty();
        List<Integer> colors = new ArrayList<>();
        boolean[] allBold = {true};
        boolean[] allItalic = {true};
        boolean[] allUnderline = {true};
        boolean[] allStrike = {true};
        boolean[] allObf = {true};
        boolean[] sawAny = {false};

        text.visit((style, str) -> {
            TextColor color = style.getColor();
            int rgb = (color != null) ? color.getValue() : ColorProfile.NO_COLOR;
            for (int i = 0; i < str.length(); i++) {
                sawAny[0] = true;
                colors.add(rgb);
                allBold[0] &= style.isBold();
                allItalic[0] &= style.isItalic();
                allUnderline[0] &= style.isUnderlined();
                allStrike[0] &= style.isStrikethrough();
                allObf[0] &= style.isObfuscated();
            }
            return Optional.empty();
        }, Style.EMPTY);

        if (!sawAny[0]) return ColorProfile.empty();
        int[] arr = new int[colors.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = colors.get(i);
        return new ColorProfile(arr, allBold[0], allItalic[0], allUnderline[0], allStrike[0], allObf[0]);
    }

    /**
     * Styled translation rebuilt from the original's colours.
     *
     * <ul>
     *   <li><b>≥2 distinct colours</b> (e.g. a coloured chat broadcast like
     *       {@code GTS » name added [Item] for $50,000}) → the original colour
     *       sequence is mapped onto the translation per-segment, so the separate
     *       fixed colours are preserved. (A gradient/rainbow original is stretched
     *       across the translation — approximate, but keeps the colour feel.)</li>
     *   <li><b>0–1 colour</b> → one flat colour (the original's), which never
     *       fragments an otherwise-uniform line.</li>
     * </ul>
     */
    public static MutableComponent styled(String translated, ColorProfile profile) {
        Style style = formatStyle(profile);
        int color = (profile == null) ? ColorProfile.NO_COLOR : profile.dominantColor();
        if (color != ColorProfile.NO_COLOR) {
            style = style.withColor(TextColor.fromRgb(color));
        }
        return Component.literal(translated).setStyle(style);
    }

    /** {@link #styled(String, ColorProfile)} that ALSO inherits the original's interactive
     *  style (click event, hover event, insertion) — so a translated clickable line stays
     *  clickable. Children only carry colour/format, so the root-level interactive style
     *  propagates to every run. */
    public static MutableComponent styled(String translated, ColorProfile profile,
                                          Component original, int fromChar) {
        return withInteractive(styled(translated, profile), interactiveStyle(original, fromChar));
    }

    /**
     * The first click/hover/insertion carried by {@code c} at visible char {@code >= fromChar}
     * (the message content, after the rank/name prefix), or {@code null} when there is none.
     */
    public static Style interactiveStyle(Component c, int fromChar) {
        if (c == null) return null;
        Style[] found = {null};
        int[] seen = {0};
        c.visit((style, str) -> {
            if (found[0] == null && seen[0] + str.length() > fromChar
                    && (style.getClickEvent() != null || style.getHoverEvent() != null
                        || style.getInsertion() != null)) {
                found[0] = style;
            }
            seen[0] += str.length();
            return Optional.empty();
        }, Style.EMPTY);
        return found[0];
    }

    /** Copy click/hover/insertion (only) from {@code interactive} onto {@code out}'s root style. */
    public static MutableComponent withInteractive(MutableComponent out, Style interactive) {
        if (out == null || interactive == null) return out;
        Style s = out.getStyle();
        if (interactive.getClickEvent() != null) s = s.withClickEvent(interactive.getClickEvent());
        if (interactive.getHoverEvent() != null) s = s.withHoverEvent(interactive.getHoverEvent());
        if (interactive.getInsertion() != null) s = s.withInsertion(interactive.getInsertion());
        return out.setStyle(s);
    }

    private static Style formatStyle(ColorProfile profile) {
        if (profile == null) return Style.EMPTY;
        return Style.EMPTY
                .withBold(profile.bold())
                .withItalic(profile.italic())
                .withUnderlined(profile.underline())
                .withStrikethrough(profile.strikethrough())
                .withObfuscated(profile.obfuscated());
    }

    // ---- per-segment colour preservation + pixel re-centering (multi-colour chat lines) ----

    /**
     * One run of the original. {@code style} is the run's single resolved style for every
     * ordinary caller — the DOMINANT style when this run was coalesced from several (by
     * {@link #coalesceSubWordRuns} or {@link #coalesceGradientRuns}). {@code charStyles} is
     * {@code null} for an ordinary run; a run coalesced from a detected per-character
     * gradient/rainbow (2026-10-02 colour-format-only sharing) instead carries the FULL
     * original per-character style sequence here (length == {@code text.length()}), so the
     * marker-rebuild step can redistribute it across the translated text's characters
     * instead of flattening the whole run to one flat colour — see {@link MarkedChat}.
     */
    public record Seg(String text, Style style, List<Style> charStyles) {
        public Seg(String text, Style style) {
            this(text, style, null);
        }

        public boolean isGradient() {
            return charStyles != null;
        }
    }

    /** Split a component into its styled runs (text + resolved style), in order. */
    public static List<Seg> segments(Component c) {
        return segmentsFrom(c, 0);
    }

    /**
     * Styled runs of {@code c} starting at visible character {@code fromChar} (the run that
     * straddles the boundary is split). Used to take only the message CONTENT (after the
     * rank/name prefix), so the prefix's colours are never mapped onto the translation.
     */
    public static List<Seg> segmentsFrom(Component c, int fromChar) {
        List<Seg> out = new ArrayList<>();
        if (c == null) return out;
        int[] seen = {0};
        c.visit((style, str) -> {
            int start = (seen[0] < fromChar) ? Math.min(str.length(), fromChar - seen[0]) : 0;
            if (start < str.length()) out.add(new Seg(str.substring(start), style));
            seen[0] += str.length();
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /**
     * Like {@link #extract} but only over visible characters at index {@code >= fromChar},
     * i.e. the message CONTENT after the rank/name prefix — so the translation is coloured
     * from the content's colours, not the (often multi-coloured) prefix.
     */
    public static ColorProfile extractFrom(Component text, int fromChar) {
        if (text == null) return ColorProfile.empty();
        List<Integer> colors = new ArrayList<>();
        boolean[] allBold = {true};
        boolean[] allItalic = {true};
        boolean[] allUnderline = {true};
        boolean[] allStrike = {true};
        boolean[] allObf = {true};
        boolean[] sawAny = {false};
        int[] seen = {0};
        text.visit((style, str) -> {
            TextColor color = style.getColor();
            int rgb = (color != null) ? color.getValue() : ColorProfile.NO_COLOR;
            for (int i = 0; i < str.length(); i++) {
                if (seen[0] >= fromChar) {
                    sawAny[0] = true;
                    colors.add(rgb);
                    allBold[0] &= style.isBold();
                    allItalic[0] &= style.isItalic();
                    allUnderline[0] &= style.isUnderlined();
                    allStrike[0] &= style.isStrikethrough();
                    allObf[0] &= style.isObfuscated();
                }
                seen[0]++;
            }
            return Optional.empty();
        }, Style.EMPTY);
        if (!sawAny[0]) return ColorProfile.empty();
        int[] arr = new int[colors.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = colors.get(i);
        return new ColorProfile(arr, allBold[0], allItalic[0], allUnderline[0], allStrike[0], allObf[0]);
    }

    // ---- word-level colour preservation for multi-colour chat (marker round-trip) ----

    /**
     * A marked-up chat content string plus the styles its ⟦CS#⟧ markers refer to.
     * {@code gradients} is {@code null} when nothing in this call was a detected
     * gradient/rainbow run (the overwhelming majority of calls); otherwise it is parallel
     * to {@code styles} and {@code gradients.get(idx)} is either {@code null} (marker
     * {@code idx} is an ordinary flat-coloured run — use {@code styles.get(idx)} as
     * before) or the FULL original per-character style sequence for that run, which
     * {@link #markedChat} redistributes across the translated text instead of flattening
     * to one colour (2026-10-02 colour-format-only sharing — see
     * {@code com.dragonmeow.nyanlex.style.ColorShapeMatcher}).
     */
    public record MarkedChat(String text, List<Style> styles, List<List<Style>> gradients) {
        public MarkedChat(String text, List<Style> styles) {
            this(text, styles, null);
        }

        public boolean marked() {
            return !styles.isEmpty();
        }

        /** The gradient style sequence for marker {@code idx}, or {@code null} when that
         *  marker is an ordinary single-style run. */
        public List<Style> gradientAt(int idx) {
            if (gradients == null || idx < 0 || idx >= gradients.size()) return null;
            return gradients.get(idx);
        }
    }

    /** Canonical backend input for one rendered component. */
    public static String requestText(Component source) {
        if (source == null) return "";
        Component resolved = resolveLegacyCodes(source);
        MarkedChat marked = markChatContent(resolved, 0);
        return marked.marked() ? marked.text() : resolved.getString();
    }

    /** Backend units for a rich component: one request per semantic paragraph. */
    public static List<String> requestLines(Component source) {
        if (source == null) return List.of();
        return paragraphRequests(source).stream().filter(s -> !s.isBlank()).toList();
    }

    /** Verified style-run marker protocol used for every multi-style line. */
    private static final java.util.regex.Pattern MARKER =
            java.util.regex.Pattern.compile("\\u27E6\\s*(/?)\\s*CS\\s*(\\d+)\\s*\\u27E7");

    private static String openMarker(int index) {
        return "⟦CS" + index + "⟧";
    }

    private static String closeMarker(int index) {
        return "⟦/CS" + index + "⟧";
    }

    private static boolean horizontalLayoutSpace(char ch) {
        return ch == ' ' || ch == '\t' || ch == '\u00A0';
    }

    private static String stripMarkers(String text) {
        return text == null ? "" : MARKER.matcher(text).replaceAll("");
    }

    private static final java.util.regex.Pattern MARKER_RESIDUE =
            java.util.regex.Pattern.compile("\\u27E6?\\s*/?\\s*CS\\s*\\d+\\s*\\u27E7?");

    /** Strip complete ⟦CS#⟧ markers AND orphaned residue left when a translator ate the rare
     *  U+27E6/27E7 brackets but kept the "CS#" body — otherwise bare "CS4" leaks on screen.
     *  Used ONLY on the marked-mode fallback, where these markers were definitely injected. */
    private static String stripMarkerResidue(String text) {
        if (text == null) return "";
        if (text.indexOf('\u27E6') < 0 && text.indexOf('\u27E7') < 0) return text;
        return MARKER_RESIDUE.matcher(MARKER.matcher(text).replaceAll("")).replaceAll("");
    }

    /**
     * Wrap each merged style run of the message content in an invisible ⟦CS#⟧…⟦/CS#⟧ marker
     * pair. The whole line is then translated in ONE request and {@link #markedChat} maps
     * every marker region back to its style — a red word stays red on the translated word,
     * wherever the grammar moved it. Every multi-run line is marked; style is never
     * inferred from character positions or translated string length.
     */
    public static MarkedChat markChatContent(Component c, int fromChar) {
        // Coalesce cross-word gradient zones (a per-character rainbow spanning several
        // WORDS, e.g. a Hypixel "MYTHIC DUNGEON BOW" rarity animation) BEFORE the sub-word
        // pass, so the whole animated phrase becomes ONE run — same marker shape a solid-
        // coloured render of the identical text would produce (2026-10-02 colour-format-
        // only sharing). Then coalesce sub-word colour runs, so a per-letter gradient NAME
        // never gets a marker pair walled INSIDE a word (the backend can't translate
        // isolated letters). After both passes every marker boundary lands at a
        // whitespace/punctuation gap → whole words reach the translator.
        List<Seg> rawSegments = mergeSegments(segmentsFrom(c, fromChar));
        List<Seg> segs = coalesceSubWordRuns(coalesceGradientRuns(coalesceRankBadges(rawSegments)));
        if (rawSegments.size() <= 1) {
            StringBuilder plain = new StringBuilder();
            for (Seg seg : segs) plain.append(seg.text());
            return new MarkedChat(plain.toString(), List.of());
        }
        StringBuilder text = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        List<List<Style>> gradients = new ArrayList<>();
        boolean anyGradient = false;
        for (Seg seg : segs) {
            String s = seg.text();
            if (s == null || s.isEmpty()) continue;
            if (s.isBlank()) {
                text.append(s);
                continue;
            }
            int start = 0;
            int end = s.length();
            while (start < end && horizontalLayoutSpace(s.charAt(start))) start++;
            while (end > start && horizontalLayoutSpace(s.charAt(end - 1))) end--;
            if (start > 0) text.append(s, 0, start);
            int idx = styles.size();
            styles.add(seg.style());
            if (seg.isGradient()) {
                gradients.add(new ArrayList<>(seg.charStyles().subList(start, end)));
                anyGradient = true;
            } else {
                gradients.add(null);
            }
            text.append(openMarker(idx)).append(s, start, end).append(closeMarker(idx));
            if (end < s.length()) text.append(s, end, s.length());
        }
        return new MarkedChat(text.toString(), styles, anyGradient ? gradients : null);
    }

    /** Rebuild a translated marked-up line: each ⟦CS#⟧ region gets its original style
     *  (colour AND click/hover). Marker loss returns the exact original; positions are
     *  never guessed from translated character counts. */
    public static MutableComponent markedChat(Component original, int contentStart,
                                              String translated, MarkedChat marked) {
        if (translated == null || translated.isEmpty()) return Component.empty();
        if (marked == null || !marked.marked()) {
            List<Seg> raw = mergeSegments(segmentsFrom(original, contentStart));
            if (raw.size() > 1) return copyFrom(original, contentStart);
            Style style = raw.isEmpty() ? Style.EMPTY : raw.get(0).style();
            return Component.literal(stripMarkers(translated)).setStyle(style);
        }
        if (!validMarkedResponse(marked, translated)) {
            if (TextFilter.isStyleFallback(translated)) {
                String semantic = TextFilter.stripStyleFallback(translated);
                return withInteractive(styledAnchored(original, contentStart, semantic),
                        interactiveStyle(original, contentStart));
            }
            return copyFrom(original, contentStart);
        }
        java.util.regex.Matcher matcher = MARKER.matcher(translated);
        MutableComponent out = Component.empty();
        int pos = 0;
        Style current = Style.EMPTY;
        Style last = marked.styles().isEmpty() ? Style.EMPTY : marked.styles().get(0);
        int currentIdx = -1;
        boolean saw = false;
        while (matcher.find()) {
            if (matcher.start() > pos) {
                appendMarkedChunk(out, translated.substring(pos, matcher.start()),
                        current, last, marked.gradientAt(currentIdx));
            }
            saw = true;
            int idx = Integer.parseInt(matcher.group(2));
            boolean close = matcher.group(1) != null && !matcher.group(1).isEmpty();
            if (!close && idx >= 0 && idx < marked.styles().size()) {
                current = marked.styles().get(idx);
                last = current;
                currentIdx = idx;
            } else {
                current = Style.EMPTY;
                currentIdx = -1;
            }
            pos = matcher.end();
        }
        if (pos < translated.length()) {
            appendMarkedChunk(out, translated.substring(pos), current, last, marked.gradientAt(currentIdx));
        }
        String plain = out.getString();
        if (!saw || plain.isBlank()) return copyFrom(original, contentStart);
        return out;
    }

    /** Appends one marker region's translated content: a detected gradient run
     *  ({@code gradient != null}, see {@link Seg#charStyles}) is redistributed across
     *  {@code chunk}'s characters ({@link #appendGradient}); an ordinary run keeps the
     *  existing flat-colour behaviour unchanged. */
    private static void appendMarkedChunk(MutableComponent out, String chunk,
                                          Style current, Style last, List<Style> gradient) {
        if (chunk.isEmpty()) return;
        if (gradient != null && !gradient.isEmpty()) {
            appendGradient(out, chunk, gradient);
            return;
        }
        out.append(Component.literal(chunk).setStyle(current == Style.EMPTY ? last : current));
    }

    /**
     * Proportionally redistributes {@code gradient} (the ORIGINAL per-character style
     * sequence a detected rainbow run carried) across {@code chunk}'s characters, using
     * the same proportional index {@link
     * com.dragonmeow.nyanlex.style.ColorShapeMatcher#sourceIndexFor} defines — so a
     * translated word of a different length still animates smoothly instead of freezing
     * on one flat colour. Consecutive output characters that map to the same source index
     * are emitted as one run (never one {@link Component} per character).
     */
    private static void appendGradient(MutableComponent out, String chunk, List<Style> gradient) {
        // Walk by code point: a supplementary-plane character or emoji must never be split into
        // lone surrogates (each half would render as '?').
        int targetLen = chunk.codePointCount(0, chunk.length());
        int sourceLen = gradient.size();
        int i = 0;
        int charPos = 0;
        while (i < targetLen) {
            int srcIdx = com.dragonmeow.nyanlex.style.ColorShapeMatcher
                    .sourceIndexFor(targetLen, sourceLen, i);
            Style style = gradient.get(srcIdx);
            int j = i + 1;
            int endChar = chunk.offsetByCodePoints(charPos, 1);
            while (j < targetLen && com.dragonmeow.nyanlex.style.ColorShapeMatcher
                    .sourceIndexFor(targetLen, sourceLen, j) == srcIdx) {
                endChar = chunk.offsetByCodePoints(endChar, 1);
                j++;
            }
            out.append(Component.literal(chunk.substring(charPos, endChar)).setStyle(style));
            charPos = endChar;
            i = j;
        }
    }

    private static boolean validMarkedResponse(MarkedChat marked, String translated) {
        if (marked == null || translated == null) return false;
        java.util.Map<String, Integer> expected = markerMultiset(marked.text());
        java.util.Map<String, Integer> actual = markerMultiset(translated);
        if (!expected.equals(actual)) return false;

        java.util.regex.Matcher matcher = MARKER.matcher(translated);
        Integer open = null;
        int cursor = 0;
        while (matcher.find()) {
            if (open == null && !outsideMarkerLayoutOnly(
                    translated.substring(cursor, matcher.start()))) return false;
            int index = Integer.parseInt(matcher.group(2));
            if (index < 0 || index >= marked.styles().size()) return false;
            boolean closing = matcher.group(1) != null && !matcher.group(1).isEmpty();
            if (!closing) {
                if (open != null) return false;
                open = index;
            } else {
                if (open == null || open != index) return false;
                open = null;
            }
            cursor = matcher.end();
        }
        return open == null && outsideMarkerLayoutOnly(translated.substring(cursor));
    }

    private static final java.util.regex.Pattern OUTSIDE_LAYOUT_SLOT =
            java.util.regex.Pattern.compile("\\u27E6\\s*(?:WS|PB)\\s*\\d+\\s*\\u27E7");

    private static boolean outsideMarkerLayoutOnly(String text) {
        if (text == null || text.isEmpty()) return true;
        return TextFilter.isLayoutOrPunctuationOnly(
                OUTSIDE_LAYOUT_SLOT.matcher(TextFilter.stripSectionCodes(text)).replaceAll(""));
    }

    private static java.util.Map<String, Integer> markerMultiset(String text) {
        java.util.Map<String, Integer> out = new java.util.TreeMap<>();
        java.util.regex.Matcher matcher = MARKER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String key = (matcher.group(1) == null ? "" : matcher.group(1)) + matcher.group(2);
            out.merge(key, 1, Integer::sum);
        }
        return out;
    }

    private static MutableComponent copyFrom(Component original, int fromChar) {
        MutableComponent out = Component.empty();
        for (Seg seg : segmentsFrom(original, fromChar)) {
            out.append(Component.literal(seg.text()).setStyle(seg.style()));
        }
        return out;
    }

    /**
     * Rebuild rich UI text from the exact source styles.  Multi-run content uses CS
     * markers; a one-run component copies that run's complete Style object so click,
     * hover and insertion events survive just like colour and font decorations.
     */
    public static MutableComponent rebuildRich(Component original, String translated,
                                               MarkedChat marked) {
        if (translated == null || translated.isEmpty()) return Component.empty();
        if (marked != null && marked.marked()) return markedChat(original, 0, translated, marked);
        String clean = stripMarkerResidue(translated);

        // Project multi-line surfaces one line at a time. A whole-page proportional
        // projection lets a short translated heading steal the body style and can move
        // a clickable final action onto an unrelated line.
        List<Component> originalLines = splitStyledLines(original);
        String[] translatedLines = clean.split("\n", -1);
        if (originalLines.size() > 1 && originalLines.size() == translatedLines.length) {
            MutableComponent out = Component.empty();
            for (int i = 0; i < translatedLines.length; i++) {
                if (i > 0) out.append(Component.literal("\n"));
                Component sourceLine = originalLines.get(i);
                out.append(rebuildRichSingle(sourceLine, translatedLines[i],
                        markChatContent(sourceLine, 0)));
            }
            return out;
        }
        return rebuildRichSingle(original, clean, marked);
    }

    private static MutableComponent rebuildRichSingle(Component original, String translated,
                                                      MarkedChat marked) {
        if (marked != null && marked.marked()) {
            return markedChat(original, 0, translated, marked);
        }
        List<Seg> segs = mergeSegments(segmentsFrom(original, 0));
        if (segs.size() == 1) {
            return Component.literal(translated).setStyle(segs.get(0).style());
        }
        return copyFrom(original, 0);
    }

    /**
     * Split only on hard {@code '\n'} boundaries while retaining every resolved run
     * style (including click, hover, insertion and font data). Empty rows are kept.
     * Chat ingress uses this before making backend requests, so a multi-line component
     * can never be flattened into one translation unit.
     */
    public static List<Component> splitStyledLines(Component source) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.empty());
        source.visit((style, value) -> {
            int start = 0;
            for (int i = 0; i < value.length(); i++) {
                if (value.charAt(i) != '\n') continue;
                if (i > start) {
                    ((MutableComponent) lines.get(lines.size() - 1))
                            .append(Component.literal(value.substring(start, i)).setStyle(style));
                }
                lines.add(Component.empty());
                start = i + 1;
            }
            if (start < value.length()) {
                ((MutableComponent) lines.get(lines.size() - 1))
                        .append(Component.literal(value.substring(start)).setStyle(style));
            }
            return Optional.empty();
        }, Style.EMPTY);
        return lines;
    }

    /** Reassemble hard lines without changing their styles, order, or empty-row positions. */
    public static MutableComponent joinStyledLines(List<? extends Component> lines) {
        MutableComponent out = Component.empty();
        if (lines == null) return out;
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) out.append(Component.literal("\n"));
            Component line = lines.get(i);
            if (line != null) out.append(line);
        }
        return out;
    }

    /** Immutable request/rebuild data for exactly one incoming chat hard line. */
    public record ChatLinePlan(Component source, int contentStart, String content,
                               MarkedChat marked, String request) {
    }

    public static ChatLinePlan prepareChatLine(Component source) {
        Component resolved = resolveLegacyCodes(source == null ? Component.empty() : source);
        String full = resolved.getString();
        int start = com.dragonmeow.nyanlex.translate.ChatSegmenter.contentStart(full);
        if (start < 0 || start >= full.length()) start = 0;
        String content = start > 0 ? full.substring(start) : full;
        MarkedChat marked = markChatContent(resolved, start);
        return new ChatLinePlan(resolved, start, content, marked,
                marked.marked() ? marked.text() : content);
    }

    /** Rebuild one translated hard line against only that line's exact component skeleton. */
    public static Component rebuildChatLine(ChatLinePlan plan, String translated) {
        if (plan == null || translated == null) {
            return plan == null ? Component.empty() : plan.source().copy();
        }
        Component source = plan.source();
        MutableComponent core;
        if (plan.marked().marked()) {
            core = markedChat(source, plan.contentStart(), translated, plan.marked());
        } else {
            Style interactive = interactiveStyle(source, plan.contentStart());
            core = withInteractive(styledChatContent(source, plan.contentStart(), translated), interactive);
        }
        if (plan.contentStart() <= 0) return core;
        return Component.empty().append(takePrefix(source, plan.contentStart())).append(core);
    }

    /**
     * Colour a whole-content translation when markers are unavailable (Google ate them /
     * gradient lines). Colour positions are NEVER guessed from character counts or
     * proportions: the whole core text takes the original content's single DOMINANT style
     * (the merged run carrying the most semantic weight — letters/digits). Only leading /
     * trailing whitespace decoration keeps the first / last run's style, as before. A flat
     * single colour is preferred over a positionally mis-aligned multi-colour split.
     */
    public static MutableComponent styledChatContent(Component original, int contentStart, String translated) {
        ColorProfile profile = extractFrom(original, contentStart);
        if (translated == null || translated.isEmpty()) return Component.empty();
        MutableComponent gradientLine = gradientWholeLine(original, contentStart, translated);
        if (gradientLine != null) return gradientLine;
        List<Seg> segs = mergeSegments(segmentsFrom(original, contentStart));
        if (segs.size() <= 1 || profile.distinctColorCount() > Math.max(3, segs.size())) {
            return styled(translated, profile);
        }
        int lead = 0;
        while (lead < translated.length() && Character.isWhitespace(translated.charAt(lead))) lead++;
        int trail = translated.length();
        while (trail > lead && Character.isWhitespace(translated.charAt(trail - 1))) trail--;
        String core = translated.substring(lead, trail);
        if (core.isEmpty()) return Component.literal(translated).setStyle(segs.get(0).style());
        Style dominant = dominantStyle(segs, 0, segs.size() - 1);
        if (dominant == null) return styled(translated, profile);
        MutableComponent out = Component.empty();
        if (lead > 0) out.append(Component.literal(translated.substring(0, lead)).setStyle(segs.get(0).style()));
        out.append(Component.literal(core).setStyle(dominant));
        if (trail < translated.length()) {
            out.append(Component.literal(translated.substring(trail)).setStyle(segs.get(segs.size() - 1).style()));
        }
        return out;
    }

    /**
     * Marker-less colouring of a translation whose ORIGINAL is one whole-line per-character
     * gradient/rainbow (the same detection {@link #markChatContent} uses): the original
     * per-character colour sequence is redistributed across the translated characters
     * ({@link #appendGradient}: endpoint-anchored proportional interpolation — a shorter
     * Chinese text samples the sequence sparsely, a longer one repeats neighbouring colours,
     * so every translated character gets one colour and first/last colour are preserved),
     * instead of flattening the line to one dominant colour. Returns {@code null} unless
     * the original is exactly one such gradient (plus optional blank runs); every other
     * multi-colour line keeps its structure-preserving behaviour and is never proportionally
     * coloured. Reached when a cached marker-free (style-fallback) translation is reused for
     * a gradient render, e.g. the tooltip path of a rainbow "MYTHIC DUNGEON BOW".
     */
    private static MutableComponent gradientWholeLine(Component original, int fromChar, String translated) {
        List<Seg> segs = coalesceGradientRuns(mergeSegments(segmentsFrom(original, fromChar)));
        Seg grad = null;
        for (Seg seg : segs) {
            if (seg.text().isBlank()) continue;
            if (grad != null || !seg.isGradient()) return null;
            grad = seg;
        }
        if (grad == null) return null;
        String gt = grad.text();
        int gs = 0;
        int ge = gt.length();
        while (gs < ge && Character.isWhitespace(gt.charAt(gs))) gs++;
        while (ge > gs && Character.isWhitespace(gt.charAt(ge - 1))) ge--;
        List<Style> sequence = grad.charStyles().subList(gs, ge);
        int lead = 0;
        while (lead < translated.length() && Character.isWhitespace(translated.charAt(lead))) lead++;
        int trail = translated.length();
        while (trail > lead && Character.isWhitespace(translated.charAt(trail - 1))) trail--;
        if (lead >= trail || sequence.isEmpty()) return null;
        MutableComponent out = Component.empty();
        if (lead > 0) out.append(Component.literal(translated.substring(0, lead)).setStyle(grad.style()));
        appendGradient(out, translated.substring(lead, trail), sequence);
        if (trail < translated.length()) {
            out.append(Component.literal(translated.substring(trail)).setStyle(grad.style()));
        }
        return out;
    }

    /** The single style among {@code segs[from..to]} carrying the most semantic weight
     *  (letters/digits; earliest run wins a tie), or {@code null} when the range is empty
     *  or purely decorative. Never guesses positions — callers apply it to a WHOLE span. */
    private static Style dominantStyle(List<Seg> segs, int from, int to) {
        // One semantic style is often split into several source runs by an intervening
        // amount or item name. Compare the TOTAL weight of each complete Style, not the
        // largest individual run; otherwise one long white item can paint an entire gold
        // auction sentence white when only a marker-free fallback is available.
        Map<Style, Integer> weights = new java.util.LinkedHashMap<>();
        for (int i = Math.max(0, from); i <= Math.min(to, segs.size() - 1); i++) {
            int weight = semanticWeight(segs.get(i).text());
            if (weight > 0) weights.merge(segs.get(i).style(), weight, Integer::sum);
        }
        Style best = null;
        int bestWeight = 0;
        for (Map.Entry<Style, Integer> entry : weights.entrySet()) {
            if (entry.getValue() > bestWeight) {
                bestWeight = entry.getValue();
                best = entry.getKey();
            }
        }
        return best;
    }

    /**
     * Anchor-aligned colouring for translated single lines (tooltip titles, HUD rows).
     * Fragments the translator keeps verbatim — proper nouns, numbers ("SkyBlock", "500",
     * "Hypixel") — are located in the translation (semantic match, never positional) and
     * get EXACTLY their original run's style; the text between two anchors takes ONE style —
     * the dominant style of the ORIGINAL fragment between those same anchor runs — so a gap
     * never switches colour mid-way on a guessed boundary.
     */
    public static MutableComponent styledAnchored(Component original, int fromChar, String translated) {
        ColorProfile profile = extractFrom(original, fromChar);
        if (translated == null || translated.isEmpty()) return Component.empty();
        MutableComponent gradientLine = gradientWholeLine(original, fromChar, translated);
        if (gradientLine != null) return gradientLine;
        List<Seg> segs = mergeSegments(segmentsFrom(original, fromChar));
        if (segs.size() <= 1) return styled(translated, profile);

        List<StyleAnchor> anchors = new ArrayList<>();
        List<StyleProbe> probes = new ArrayList<>();
        for (int i = 0; i < segs.size(); i++) {
            String whole = segs.get(i).text().strip();
            if (whole.length() >= 2) probes.add(new StyleProbe(i, whole));
            java.util.regex.Matcher stable = STABLE_ANCHOR.matcher(whole);
            while (stable.find()) {
                String token = stable.group();
                if (!token.equals(whole)) probes.add(new StyleProbe(i, token));
            }
        }
        probes.sort((left, right) -> {
            int length = Integer.compare(right.text().length(), left.text().length());
            return length != 0 ? length : Integer.compare(left.segment(), right.segment());
        });

        // Verbatim numbers, URLs, names and ids are matched independently of source
        // order. Translations commonly reorder them; a forward-only search lost styles.
        for (StyleProbe candidate : probes) {
            int index = candidate.segment();
            String probe = candidate.text();
            int at = translated.indexOf(probe);
            while (at >= 0 && overlapsAnchor(anchors, at, at + probe.length())) {
                at = translated.indexOf(probe, at + 1);
            }
            if (at >= 0) anchors.add(new StyleAnchor(index, at, at + probe.length()));
        }
        if (anchors.isEmpty()) return styledChatContent(original, fromChar, translated);
        anchors.sort(java.util.Comparator.comparingInt(StyleAnchor::start));

        MutableComponent out = Component.empty();
        int cursor = 0;
        StyleAnchor previous = null;
        for (StyleAnchor anchor : anchors) {
            if (anchor.start() > cursor) {
                out.append(Component.literal(translated.substring(cursor, anchor.start()))
                        .setStyle(gapStyle(segs, previous, anchor)));
            }
            out.append(Component.literal(translated.substring(anchor.start(), anchor.end()))
                    .setStyle(segs.get(anchor.segment()).style()));
            cursor = anchor.end();
            previous = anchor;
        }
        if (cursor < translated.length()) {
            out.append(Component.literal(translated.substring(cursor))
                    .setStyle(gapStyle(segs, previous, null)));
        }
        return out;
    }

    /** ONE style for the whole translated gap between two anchors: the dominant style of the
     *  ORIGINAL runs lying between the same two anchor runs (a {@code null} side means the
     *  line start / end). When nothing semantic lies between them (adjacent anchors, purely
     *  decorative filler), the gap borrows the nearest anchor's own style — still a single
     *  style, never a positional split. */
    private static Style gapStyle(List<Seg> segs, StyleAnchor before, StyleAnchor after) {
        int lo = (before == null) ? -1 : before.segment();
        int hi = (after == null) ? segs.size() : after.segment();
        if (lo > hi) {
            int swap = lo;
            lo = hi;
            hi = swap;
        }
        Style dominant = dominantStyle(segs, lo + 1, hi - 1);
        if (dominant != null) return dominant;
        return (before != null) ? segs.get(before.segment()).style()
                : segs.get(after.segment()).style();
    }

    private record StyleAnchor(int segment, int start, int end) {
    }

    /** Verbatim fragments likely to survive translation even when the surrounding
     * coloured phrase changes language: signed values, percentages, ids/names and icons. */
    private static final java.util.regex.Pattern STABLE_ANCHOR = java.util.regex.Pattern.compile(
            "[+-]?\\d[\\d,]*(?:\\.\\d+)?%?|[A-Za-z_][A-Za-z0-9_./:#-]{1,}|[\\p{So}\\p{Co}]");

    private record StyleProbe(int segment, String text) {
    }

    private static boolean overlapsAnchor(List<StyleAnchor> anchors, int start, int end) {
        for (StyleAnchor anchor : anchors) {
            if (start < anchor.end() && end > anchor.start()) return true;
        }
        return false;
    }

    /** Merge adjacent runs with identical styles so one word isn't split across markers. */
    private static List<Seg> mergeSegments(List<Seg> input) {
        List<Seg> out = new ArrayList<>();
        for (Seg seg : input) {
            if (seg == null || seg.text() == null || seg.text().isEmpty()) continue;
            if (!out.isEmpty()) {
                Seg last = out.get(out.size() - 1);
                if (last.style().equals(seg.style())) {
                    out.set(out.size() - 1, new Seg(last.text() + seg.text(), last.style()));
                    continue;
                }
            }
            out.add(seg);
        }
        return out;
    }

    /**
     * Coalesce adjacent runs whose shared boundary sits mid-word — the last code point of the
     * left run and the first code point of the right run are BOTH word chars (letter/digit/{@code _},
     * per {@link #isWordChar}, so usernames like {@code xX_Player_Xx} stay whole). A per-letter
     * gradient/rainbow name (each character its own {@link TextColor}) survives
     * {@link #mergeSegments} as N single-character runs; wrapping each in its own ⟦CS#⟧ pair
     * would wall the letters apart, so the backend can't recognise the word and returns it
     * shredded letter-by-letter. Merging every maximal word-char run into ONE segment keeps
     * all marker boundaries at whitespace/punctuation, so whole words reach the translator. The
     * merged run takes the DOMINANT style (the colour covering the most name chars); only
     * sub-word gradient colour collapses to that one colour — marker-isolated letters were
     * untranslatable anyway. Whole-word coloured runs share no mid-word boundary, so
     * single-colour and word-coloured lines pass through unchanged.
     */
    private static List<Seg> coalesceSubWordRuns(List<Seg> segs) {
        List<Seg> out = new ArrayList<>();
        List<Seg> group = new ArrayList<>();
        for (Seg seg : segs) {
            if (!group.isEmpty()) {
                Seg prev = group.get(group.size() - 1);
                boolean midWord = isWordChar(lastCodePoint(prev.text()))
                        && isWordChar(firstCodePoint(seg.text()));
                if (!midWord) {
                    out.add(mergeDominant(group));
                    group = new ArrayList<>();
                }
            }
            group.add(seg);
        }
        if (!group.isEmpty()) out.add(mergeDominant(group));
        return out;
    }

    /** Merge a maximal mid-word group into one run: text concatenated, style = the one whose
     *  runs cover the most name chars (semantic weight; earliest wins on a tie, as a pure
     *  rainbow has no majority). A single-element group is returned unchanged. If any
     *  member already carries a gradient (from {@link #coalesceGradientRuns}, or from a
     *  previous mid-word merge), the merged run keeps a combined per-character style
     *  sequence too, so further coalescing never discards gradient information. */
    private static Seg mergeDominant(List<Seg> group) {
        if (group.size() == 1) return group.get(0);
        StringBuilder text = new StringBuilder();
        Map<Style, Integer> weightByStyle = new java.util.LinkedHashMap<>();
        boolean anyGradient = false;
        for (Seg seg : group) {
            text.append(seg.text());
            weightByStyle.merge(seg.style(), Math.max(1, semanticWeight(seg.text())), Integer::sum);
            anyGradient |= seg.isGradient();
        }
        Style dominant = group.get(0).style();
        int best = -1;
        for (Map.Entry<Style, Integer> e : weightByStyle.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                dominant = e.getKey();
            }
        }
        if (!anyGradient) return new Seg(text.toString(), dominant);
        List<Style> charStyles = new ArrayList<>(text.length());
        for (Seg seg : group) {
            if (seg.isGradient()) {
                charStyles.addAll(seg.charStyles());
            } else {
                for (int i = 0; i < seg.text().length(); i++) charStyles.add(seg.style());
            }
        }
        return new Seg(text.toString(), dominant, charStyles);
    }

    /** Below this per-run visible-character length, a run is "tiny enough" to be a sample
     *  of a per-character gradient/rainbow sweep (letters AND the whitespace between words
     *  are both allowed to be tiny — a gradient does not pause at word boundaries). A
     *  deliberately two-toned line ({@code "EPIC"} + {@code " DUNGEON GLOVES"}, each its
     *  own single run of 4+ characters) never qualifies, so it is never mistaken for one. */
    private static final int GRADIENT_RUN_MAX_CHARS = 2;
    /** Minimum total non-whitespace characters across a candidate zone before it is
     *  trusted as a genuine gradient rather than a couple of incidentally short runs. */
    private static final int GRADIENT_MIN_NON_WS_CHARS = 5;

    /**
     * Coalesces a maximal CROSS-WORD run of tiny, almost-all-distinctly-coloured segments
     * (a per-character rainbow/gradient sweep spanning more than one word, e.g. a Hypixel
     * {@code "MYTHIC DUNGEON BOW"} rarity-line animation) into ONE {@link Seg} that keeps
     * the full per-character style sequence (see {@link Seg#charStyles}), so:
     * <ul>
     *   <li>the resulting {@link MarkedChat} wraps the WHOLE phrase in a single ⟦CS#⟧
     *       pair — the exact same marker shape a solid-coloured render of the identical
     *       text produces — so local composers that reject a phrase split across colour
     *       runs ({@code RarityLineComposer}) keep matching regardless of which animation
     *       frame was captured; and</li>
     *   <li>{@link #markedChat} can redistribute the stored sequence across the
     *       translated text's characters instead of collapsing it to one flat colour.</li>
     * </ul>
     * A PURE mid-word gradient (no whitespace at all, e.g. a per-letter rainbow account
     * name) never qualifies here — it crosses no whitespace, so it is left for
     * {@link #coalesceSubWordRuns} exactly as before, unaffected by this pass.
     */
    private static List<Seg> coalesceGradientRuns(List<Seg> segs) {
        List<Seg> out = null;
        int i = 0;
        while (i < segs.size()) {
            int zoneEnd = gradientZoneEnd(segs, i);
            if (zoneEnd > i) {
                if (out == null) {
                    out = new ArrayList<>(segs.subList(0, i));
                }
                out.add(mergeGradientZone(segs.subList(i, zoneEnd)));
                i = zoneEnd;
            } else {
                if (out != null) out.add(segs.get(i));
                i++;
            }
        }
        return out == null ? segs : out;
    }

    /** The end (exclusive) of the maximal qualifying gradient zone starting at {@code
     *  start}, or {@code start} itself when no zone starts there. */
    private static int gradientZoneEnd(List<Seg> segs, int start) {
        int end = start;
        while (end < segs.size() && !segs.get(end).isGradient()
                && segs.get(end).text().length() <= GRADIENT_RUN_MAX_CHARS) {
            end++;
        }
        return qualifiesAsGradientZone(segs, start, end) ? end : start;
    }

    /** Whether {@code segs[start, end)} looks like a genuine cross-word gradient: it must
     *  cross at least one whitespace-only run (otherwise it is a plain mid-word case,
     *  left to {@link #coalesceSubWordRuns}), cover enough non-whitespace characters to
     *  rule out noise, and have almost every non-whitespace RUN carry its own colour. */
    private static boolean qualifiesAsGradientZone(List<Seg> segs, int start, int end) {
        boolean crossedWhitespace = false;
        int nonWsChars = 0;
        int nonWsSegCount = 0;
        java.util.Set<Style> distinct = new java.util.HashSet<>();
        for (int i = start; i < end; i++) {
            Seg seg = segs.get(i);
            if (seg.text().isBlank()) {
                crossedWhitespace = true;
                continue;
            }
            nonWsChars += seg.text().length();
            nonWsSegCount++;
            distinct.add(seg.style());
        }
        if (!crossedWhitespace || nonWsChars < GRADIENT_MIN_NON_WS_CHARS) return false;
        if (distinct.size() < nonWsSegCount - 1) return false;
        return switchesColourInsideWords(segs, start, end);
    }

    /** A genuine per-CHARACTER gradient changes colour INSIDE words. A line coloured one
     *  WORD at a time ("Go on up to it now": each 1-2 letter word its own colour) never does,
     *  so it is not a gradient. Every maximal non-whitespace word of 2+ characters is checked
     *  for being split across 2+ runs; at least one word must be split, and unsplit multi-
     *  character words may be at most half as many as split ones (tolerates a slow gradient
     *  whose neighbouring letters briefly share a colour). Single-letter words are neutral. */
    private static boolean switchesColourInsideWords(List<Seg> segs, int start, int end) {
        int split = 0;
        int unsplit = 0;
        int wordLen = 0;
        int firstSeg = -1;
        boolean multiSeg = false;
        for (int i = start; i <= end; i++) {
            String t = i < end ? segs.get(i).text() : " ";
            for (int c = 0; c < t.length(); c++) {
                if (!Character.isWhitespace(t.charAt(c))) {
                    if (wordLen == 0) firstSeg = i;
                    else if (i != firstSeg) multiSeg = true;
                    wordLen++;
                } else {
                    if (wordLen >= 2) {
                        if (multiSeg) split++; else unsplit++;
                    }
                    wordLen = 0;
                    multiSeg = false;
                }
            }
        }
        return split >= 1 && unsplit * 2 <= split;
    }

    /** Merge a qualifying gradient zone into one run, retaining every member's style as a
     *  per-character sequence (see {@link Seg#charStyles}) plus a weighted dominant style
     *  for any caller that only wants a flat fallback colour. */
    private static Seg mergeGradientZone(List<Seg> group) {
        StringBuilder text = new StringBuilder();
        List<Style> charStyles = new ArrayList<>();
        Map<Style, Integer> weightByStyle = new java.util.LinkedHashMap<>();
        for (Seg seg : group) {
            text.append(seg.text());
            for (int i = 0; i < seg.text().length(); i++) charStyles.add(seg.style());
            weightByStyle.merge(seg.style(), Math.max(1, semanticWeight(seg.text())), Integer::sum);
        }
        Style dominant = group.get(0).style();
        int best = -1;
        for (Map.Entry<Style, Integer> e : weightByStyle.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                dominant = e.getKey();
            }
        }
        return new Seg(text.toString(), dominant, charStyles);
    }

    private static final java.util.regex.Pattern RANK_BADGE =
            java.util.regex.Pattern.compile("\\[[A-Z]{2,10}\\+{0,3}\\]");
    private static final java.util.regex.Pattern RANK_PARTIAL =
            java.util.regex.Pattern.compile("\\[[A-Z]{0,10}\\+{0,3}");

    /**
     * Isolates every Hypixel rank badge ({@code [VIP]}, {@code [MVP+]}, {@code [MVP++]},
     * {@code [YOUTUBE]}, …) as its OWN run, so "Seller: [VIP] Alice" and "Seller: [MVP+] Bob"
     * (whose badge is drawn as {@code "[MVP"}+{@code "+"}+{@code "] Bob"} in three colours)
     * produce the same marker shape: {@code NameMasker} can then mask the badge text as a
     * slot (2026-10 rank slotting). A badge that was split across several colour runs keeps
     * its full per-character style sequence ({@link Seg#charStyles}), so after the badge is
     * restored verbatim its original per-letter colours are redistributed one-to-one. Only a
     * badge delimited by a line start/space before and a space/line end after is touched.
     */
    private static List<Seg> coalesceRankBadges(List<Seg> segs) {
        List<Seg> out = null;
        int i = 0;
        while (i < segs.size()) {
            Seg seg = segs.get(i);
            String text = seg.text();
            int open = text.lastIndexOf('[');
            int[] hit = open < 0 ? null : matchBadge(segs, i, open);
            if (hit == null) {
                if (out != null) out.add(seg);
                i++;
                continue;
            }
            // hit = {lastSegIndex, charsTakenFromLastSeg, totalBadgeLength}
            if (out == null) out = new ArrayList<>(segs.subList(0, i));
            if (open > 0) out.add(new Seg(text.substring(0, open), seg.style()));
            StringBuilder badge = new StringBuilder();
            List<Style> chars = new ArrayList<>();
            boolean multi = hit[0] > i;
            for (int k = i; k <= hit[0]; k++) {
                String t = segs.get(k).text();
                int from = k == i ? open : 0;
                int to = k == hit[0] ? hit[1] : t.length();
                for (int c = from; c < to; c++) {
                    badge.append(t.charAt(c));
                    chars.add(segs.get(k).style());
                }
            }
            out.add(multi ? new Seg(badge.toString(), seg.style(), chars)
                    : new Seg(badge.toString(), seg.style()));
            Seg last = segs.get(hit[0]);
            if (hit[1] < last.text().length()) {
                out.add(new Seg(last.text().substring(hit[1]), last.style()));
            }
            i = hit[0] + 1;
        }
        return out == null ? segs : out;
    }

    /** Matches a rank badge starting at {@code segs[start].text()[open]}; returns
     *  {@code {lastSegIndex, endIndexInLastSegText, badgeLength}} or {@code null}. */
    private static int[] matchBadge(List<Seg> segs, int start, int open) {
        String first = segs.get(start).text();
        boolean spaceBefore = open == 0
                ? (start == 0 || endsWithSpace(segs.get(start - 1).text()))
                : first.charAt(open - 1) == ' ';
        if (!spaceBefore) return null;
        StringBuilder acc = new StringBuilder(first.substring(open));
        int j = start;
        int priorLen = 0;
        while (true) {
            java.util.regex.Matcher m = RANK_BADGE.matcher(acc);
            if (m.lookingAt()) {
                int len = m.end();
                int endInSeg = (j == start ? open : 0) + (len - priorLen);
                String segText = segs.get(j).text();
                boolean spaceAfter = endInSeg < segText.length()
                        ? segText.charAt(endInSeg) == ' '
                        : (j + 1 >= segs.size() || segs.get(j + 1).text().startsWith(" "));
                if (!spaceAfter) return null;
                return new int[] {j, endInSeg, len};
            }
            if (!RANK_PARTIAL.matcher(acc).matches()) return null;
            if (++j >= segs.size()) return null;
            priorLen = acc.length();
            acc.append(segs.get(j).text());
        }
    }

    private static boolean endsWithSpace(String s) {
        return !s.isEmpty() && s.charAt(s.length() - 1) == ' ';
    }

    /** First code point of {@code s} (surrogate-aware), or {@code -1} when empty —
     *  {@link Character#isLetterOrDigit(int)} treats {@code -1} as non-name, so an empty
     *  side never triggers a merge. */
    private static int firstCodePoint(String s) {
        return (s == null || s.isEmpty()) ? -1 : s.codePointAt(0);
    }

    /** Last code point of {@code s} (surrogate-aware), or {@code -1} when empty. */
    private static int lastCodePoint(String s) {
        return (s == null || s.isEmpty()) ? -1 : s.codePointBefore(s.length());
    }

    /** Intra-word (name) char per the codebase's definition ({@code NameMasker.isNameChar}):
     *  a letter/digit or {@code '_'} — so a per-letter-coloured Minecraft username like
     *  {@code xX_Player_Xx} coalesces whole instead of splitting at each underscore. Empty
     *  sides pass {@code -1}, which is correctly non-word. */
    private static boolean isWordChar(int cp) {
        return Character.isLetterOrDigit(cp) || cp == '_';
    }

    private static int semanticWeight(String text) {
        int weight = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) weight++;
        }
        return weight;
    }

    /** Colour/format profile of a laid-out {@link FormattedCharSequence} line, so a translated
     *  GUI line (e.g. a quest description) keeps its colours instead of dropping to white. */
    public static ColorProfile extract(FormattedCharSequence fcs) {
        if (fcs == null) return ColorProfile.empty();
        List<Integer> colors = new ArrayList<>();
        boolean[] allBold = {true};
        boolean[] allItalic = {true};
        boolean[] allUnderline = {true};
        boolean[] allStrike = {true};
        boolean[] allObf = {true};
        fcs.accept((index, style, codePoint) -> {
            TextColor color = style.getColor();
            colors.add((color != null) ? color.getValue() : ColorProfile.NO_COLOR);
            allBold[0] &= style.isBold();
            allItalic[0] &= style.isItalic();
            allUnderline[0] &= style.isUnderlined();
            allStrike[0] &= style.isStrikethrough();
            allObf[0] &= style.isObfuscated();
            return true;
        });
        if (colors.isEmpty()) return ColorProfile.empty();
        int[] arr = new int[colors.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = colors.get(i);
        return new ColorProfile(arr, allBold[0], allItalic[0], allUnderline[0], allStrike[0], allObf[0]);
    }

    /** First click/hover/insertion style found in a laid-out line, or {@code null}. */
    public static Style interactiveStyle(FormattedCharSequence fcs) {
        if (fcs == null) return null;
        Style[] found = {null};
        fcs.accept((index, style, codePoint) -> {
            if (style.getClickEvent() != null || style.getHoverEvent() != null
                    || style.getInsertion() != null) {
                found[0] = style;
                return false;
            }
            return true;
        });
        return found[0];
    }

    /** Flatten a laid-out {@link FormattedCharSequence} (a wrapped GUI line, e.g. a quest
     *  description line) back to plain text so it can be translated. */
    public static String plainText(FormattedCharSequence fcs) {
        if (fcs == null) return "";
        StringBuilder sb = new StringBuilder();
        fcs.accept((index, style, codePoint) -> {
            sb.appendCodePoint(codePoint);
            return true;
        });
        return sb.toString();
    }

    /**
     * Split a component on {@code '\n'} into one component per line, preserving each run's style.
     * Used to render a stacked name tag / hologram ("原文" line then "譯文" line).
     */
    public static List<Component> splitLines(Component c) {
        List<MutableComponent> lines = new ArrayList<>();
        lines.add(Component.empty());
        if (c != null) {
            c.visit((style, str) -> {
                int start = 0;
                for (int i = 0; i < str.length(); i++) {
                    if (str.charAt(i) == '\n') {
                        if (i > start) {
                            lines.get(lines.size() - 1).append(Component.literal(str.substring(start, i)).setStyle(style));
                        }
                        lines.add(Component.empty());
                        start = i + 1;
                    }
                }
                if (start < str.length()) {
                    lines.get(lines.size() - 1).append(Component.literal(str.substring(start)).setStyle(style));
                }
                return Optional.empty();
            }, Style.EMPTY);
        }
        return new ArrayList<>(lines);
    }

    /** Flatten a {@link FormattedText} (a whole, pre-wrap GUI string) to plain text. */
    public static String plainText(FormattedText text) {
        if (text == null) return "";
        StringBuilder sb = new StringBuilder();
        text.visit(content -> {
            sb.append(content);
            return Optional.empty();
        });
        return sb.toString();
    }

    /**
     * Re-centre a (single-colour) translated string under the original line's text centre,
     * by prefixing spaces measured in pixels. Returns the string unchanged when the original
     * has no leading whitespace (i.e. it was left-aligned, not centred/indented).
     */
    public static String matchCenter(Font font, String original, String translatedCore) {
        if (font == null || original == null || translatedCore == null) return translatedCore;
        int spaces = leadSpacesToCenter(font, original, font.width(translatedCore));
        return spaces > 0 ? " ".repeat(spaces) + translatedCore : translatedCore;
    }

    /** Re-centre a translated component under the original's text centre (no-op when the
     *  original was left-aligned). */
    public static MutableComponent centerPad(Font font, String original, MutableComponent core) {
        if (font == null || core == null) return core;
        int spaces = leadSpacesToCenter(font, original, font.width(core));
        return spaces > 0 ? Component.literal(" ".repeat(spaces)).append(core) : core;
    }

    /** Number of leading spaces so a {@code coreWidthPx}-wide line sits at the original's text centre. */
    private static int leadSpacesToCenter(Font font, String original, int coreWidthPx) {
        if (original == null) return 0;
        int lead = 0;
        while (lead < original.length() && Character.isWhitespace(original.charAt(lead))) lead++;
        if (lead == 0) return 0; // original not indented/centred -> leave left-aligned
        int spaceW = Math.max(1, font.width(" "));
        int origLeadPx = font.width(original.substring(0, lead));
        int origTextPx = font.width(original.strip());
        int newLeadPx = origLeadPx + origTextPx / 2 - coreWidthPx / 2;
        return Math.max(0, Math.round(newLeadPx / (float) spaceW));
    }

    /** Build a component of the first {@code charCount} visible characters of {@code original}, keeping styling. */
    public static MutableComponent takePrefix(Component original, int charCount) {
        MutableComponent out = Component.empty();
        int[] remaining = {charCount};
        original.visit((style, str) -> {
            if (remaining[0] <= 0) return Optional.empty();
            int take = Math.min(remaining[0], str.length());
            if (take > 0) {
                out.append(Component.literal(str.substring(0, take)).setStyle(style));
            }
            remaining[0] -= take;
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** Distinctive separator colour (bright magenta) — stands out from common chat colours. */
    private static final int SEPARATOR_RGB = 0xFF55FF;

    /** A separator line of {@code length} dashes in a distinctive colour. */
    public static MutableComponent separatorLine(int length) {
        int n = Math.max(6, Math.min(length, 50));
        return Component.literal("-".repeat(n))
                .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(SEPARATOR_RGB)));
    }

    /** True for plain dash separator lines such as "---------------". */
    public static boolean isSeparatorText(String text) {
        if (text == null) return false;
        String t = text.strip();
        if (t.length() < 6) return false;
        for (int i = 0; i < t.length(); i++) {
            char ch = t.charAt(i);
            if (ch != '-' && ch != '\u2010' && ch != '\u2011' && ch != '\u2012'
                    && ch != '\u2013' && ch != '\u2014' && ch != '\u2500') {
                return false;
            }
        }
        return true;
    }

    /** Longest line (char count) across the given multi-line strings. */
    public static int maxLineLength(String... blocks) {
        int max = 0;
        for (String b : blocks) {
            if (b == null) continue;
            for (String line : b.split("\n", -1)) {
                if (line.length() > max) max = line.length();
            }
        }
        return max;
    }

    /**
     * Stack 原文 above 譯文 for surfaces whose renderer centres EACH LINE independently
     * (name tags / holograms): the narrower line is padded with trailing spaces to the
     * wider line's pixel width, so after centring both lines share the same left edge.
     */
    private static MutableComponent stackAligned(Component original, Component translated) {
        Component top = original;
        Component bottom = translated;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        Font font = (mc != null) ? mc.font : null;
        if (font != null) {
            int w1 = font.width(original);
            int w2 = font.width(translated);
            int spaceW = Math.max(1, font.width(" "));
            int pad = Math.round(Math.abs(w1 - w2) / (float) spaceW);
            if (pad > 0 && w1 < w2) top = original.copy().append(Component.literal(" ".repeat(pad)));
            else if (pad > 0 && w2 < w1) bottom = translated.copy().append(Component.literal(" ".repeat(pad)));
        }
        return Component.empty().append(top).append(Component.literal("\n")).append(bottom);
    }

    public static Component compose(Component original, TranslationDecision decision) {
        if (decision == null || !decision.changed()) return original;
        original = resolveLegacyCodes(original);
        MarkedChat marked = markChatContent(original, 0);
        MutableComponent translated = rebuildRich(original, decision.translated(), marked);
        if (decision.mode() == DisplayMode.BOTH) {
            return chatBlock(original, translated);
        }
        return translated;
    }

    /** Chat-only BOTH layout. A missing translation is shown once as the original;
     *  manufacturing a second identical "translation" row makes a temporary timeout or
     *  marker mismatch look like an input-filter decision. Other surfaces never call this. */
    private static final java.util.concurrent.atomic.AtomicLong CHAT_SEPARATOR_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private static Component uniqueChatSeparator(int length) {
        long id = CHAT_SEPARATOR_SEQUENCE.incrementAndGet();
        // Resource-pack fonts may draw U+200B/U+200C as visible missing-glyph boxes.
        // Trailing spaces stay blank while still defeating compact-chat deduplication.
        int spaces = (int) ((id - 1L) & 3L) + 1;
        return separatorLine(length).copy().append(Component.literal(" ".repeat(spaces)));
    }

    public static Component chatBlock(Component original, Component translated) {
        Component source = original == null ? Component.empty() : resolveLegacyCodes(original);
        if (translated == null) return source.copy();
        Component result = translated;
        int len = maxLineLength(source.getString(), result.getString());
        return Component.empty()
                .append(uniqueChatSeparator(len))
                .append(Component.literal("\n")).append(source)
                .append(Component.literal("\n")).append(result)
                .append(Component.literal("\n")).append(uniqueChatSeparator(len));
    }
}
