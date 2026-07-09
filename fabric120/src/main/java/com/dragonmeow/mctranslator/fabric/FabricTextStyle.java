package com.dragonmeow.mctranslator.fabric;

import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.style.ColorProfile;
import com.dragonmeow.mctranslator.style.StyleMapper;
import com.dragonmeow.mctranslator.style.StyledRun;

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
 * Minecraft's {@link Component} and the loader-agnostic {@link ColorProfile} /
 * {@link StyleMapper}, so the colour-preservation logic is shared with Fabric.
 */
public final class FabricTextStyle {

    private FabricTextStyle() {
    }

    /** Memo of translation DECISIONS (plain translated string + mode), NOT built Components:
     *  the styled Component is rebuilt from the CURRENT source's colours on every call, so a
     *  colour-cycling line (rainbow "SKYBLOCK" title) keeps animating after translation
     *  instead of freezing on the first frame's colours. */
    private static final Map<String, TranslationDecision> RENDER_MEMO = new ConcurrentHashMap<>();

    /** Surfaces whose 原文＋翻譯 can render as two stacked lines (they wrap, or our mixin splits '\n'). */
    private static final java.util.Set<String> STACKABLE = java.util.Set.of("book", "nameTag", "bossBar", "actionBar");

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
        String src = source.getString();
        String key = surfaceId + '\u0000' + src;
        TranslationDecision decision = RENDER_MEMO.get(key);
        if (decision == null) {
            decision = translateFn.apply(src);
            if (decision == null || !decision.changed()) return null; // untranslated: keep original
            if (RENDER_MEMO.size() > 8192) RENDER_MEMO.clear();
            RENDER_MEMO.put(key, decision);
        }
        ColorProfile profile = extract(source);
        // Multi-colour lines (e.g. "SkyBlock YEAR 500 RAFFLE") map colours by verbatim
        // anchors instead of proportional stretch, so each word keeps ITS colour.
        Component translated = (profile.distinctColorCount() >= 2)
                ? withInteractive(styledAnchored(source, 0, decision.translated()), interactiveStyle(source, 0))
                : styled(decision.translated(), profile, source, 0);
        // 原文＋翻譯 handling per surface:
        //  - "tooltip" (and chat): the 原文/分隔線/翻譯 BLOCK is built by the caller, so here we
        //    return TRANSLATION-ONLY (otherwise the block's translation line wrongly shows both).
        //  - STACKABLE surfaces ("book" wraps via Font.split; "nameTag" & "bossBar" are drawn by our
        //    own mixin which splits on '\n') → newline genuinely stacks 原文 line / 譯文 line.
        //  - everything else (GUI single-label text e.g. Iris settings, title / action bar / held /
        //    scoreboard) is on a FIXED single row that can't gain a line, so INLINE as 原文　譯文.
        if (decision.mode() == DisplayMode.BOTH && !"tooltip".equals(surfaceId)) {
            return STACKABLE.contains(surfaceId)
                    ? stackAligned(source, translated)
                    : source.copy().append(Component.literal("　")).append(translated);
        }
        return translated;
    }

    /**
     * Drop the per-frame render cache. Called when the config changes (a surface is
     * turned off / mode switched) or the cache is cleared, so stale translations are
     * not returned for memoised sources.
     */
    /** Memo for tooltip sentence-groups: joined source sentence → re-wrapped translated lines. */
    private static final Map<String, List<Component>> GROUP_MEMO = new ConcurrentHashMap<>();

    public static void clearRenderMemo() {
        RENDER_MEMO.clear();
        GROUP_MEMO.clear();
    }

    // ---- wrapped-sentence tooltips: join → translate whole → re-wrap ----

    /**
     * Whether tooltip line {@code next} is the continuation of a sentence that the server
     * wrapped across lines ("…when Diaz is" / "Mayor for special items!"). Conservative:
     * stat rows ("+50% Skill XP") and headers never join.
     */
    public static boolean continuesSentence(String prev, String next) {
        if (prev == null || next == null) return false;
        String p = prev.strip();
        String n = next.strip();
        if (p.isEmpty() || n.isEmpty()) return false;
        if (p.split("\\s+").length < 4) return false; // too short to be a wrapped sentence
        char last = p.charAt(p.length() - 1);
        if (!Character.isLetter(last)) return false;  // ended with punctuation/number: complete
        return Character.isLetter(n.charAt(0));
    }

    /** Join a wrapped sentence's lines into one styled component (runs preserved, single
     *  spaces between lines) so it can be translated and re-coloured as a whole. */
    public static MutableComponent joinLines(List<Component> group) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < group.size(); i++) {
            if (i > 0) out.append(Component.literal(" "));
            for (Seg seg : segments(group.get(i))) {
                out.append(Component.literal(seg.text()).setStyle(seg.style()));
            }
        }
        return out;
    }

    /** The plain translation key for a wrapped-sentence group (must match what is warmed). */
    public static String joinPlain(List<Component> group) {
        StringBuilder sb = new StringBuilder();
        for (Component c : group) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(c.getString().strip());
        }
        return sb.toString();
    }

    /** The EXACT text to translate for a wrapped-sentence group: ⟦CS#⟧-marked when the
     *  paragraph has accent-coloured words (their colour then survives reordering),
     *  plain otherwise. The warm-up and the render path must both use this. */
    public static String groupRequestText(List<Component> group) {
        MutableComponent joined = joinLines(group);
        if (extract(joined).distinctColorCount() >= 2) {
            MarkedChat marked = markChatContent(joined, 0);
            if (marked.marked()) return marked.text();
        }
        return joinPlain(group);
    }

    /** Rebuild a styled {@link FormattedText} line into a Component (used after re-wrapping). */
    public static MutableComponent toComponent(FormattedText line) {
        MutableComponent out = Component.empty();
        line.visit((style, str) -> {
            if (!str.isEmpty()) out.append(Component.literal(str).setStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    /** Wrap a styled component to {@code width} px, one Component per resulting line. */
    public static List<Component> splitToWidth(Component styled, int width, Font font) {
        List<Component> out = new ArrayList<>();
        for (FormattedText line : font.getSplitter().splitLines(styled, Math.max(60, width), Style.EMPTY)) {
            out.add(toComponent(line));
        }
        if (out.isEmpty()) out.add(styled.copy());
        return out;
    }

    /**
     * Translate a wrapped tooltip sentence as ONE unit: colours mapped over the whole
     * sentence (anchors survive reordering), then re-wrapped to the group's original
     * pixel width. Returns {@code null} until the translation is cached (the lookup
     * itself queues the request). Memoised per joined sentence.
     */
    public static List<Component> renderTranslatedGroup(List<Component> group,
                                                        Function<String, TranslationDecision> translateFn,
                                                        Font font) {
        MutableComponent joinedComp = joinLines(group);
        ColorProfile profile = extract(joinedComp);
        MarkedChat marked = (profile.distinctColorCount() >= 2) ? markChatContent(joinedComp, 0) : null;
        boolean useMarkers = marked != null && marked.marked();
        String request = useMarkers ? marked.text() : joinPlain(group);
        String key = "tooltipGroup " + request;
        List<Component> memo = GROUP_MEMO.get(key);
        if (memo != null) return memo;
        TranslationDecision decision = translateFn.apply(request);
        if (decision == null || !decision.changed()) return null;
        MutableComponent styledAll = useMarkers
                ? withInteractive(markedChat(joinedComp, 0, decision.translated(), marked), interactiveStyle(joinedComp, 0))
                : (profile.distinctColorCount() >= 2
                    ? withInteractive(styledAnchored(joinedComp, 0, decision.translated()), interactiveStyle(joinedComp, 0))
                    : styled(decision.translated(), profile, joinedComp, 0));
        int width = 0;
        for (Component c : group) width = Math.max(width, font.width(c));
        List<Component> outLines = splitToWidth(styledAll, width, font);
        if (GROUP_MEMO.size() > 2048) GROUP_MEMO.clear();
        GROUP_MEMO.put(key, outLines);
        return outLines;
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
        if (profile != null && profile.distinctColorCount() >= 2) {
            return styledRuns(translated, profile);
        }
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

    /** Multi-colour rebuild: one styled sibling per colour run mapped onto the translation. */
    private static MutableComponent styledRuns(String translated, ColorProfile profile) {
        MutableComponent out = Component.empty();
        for (StyledRun run : StyleMapper.toRuns(translated, profile)) {
            Style style = Style.EMPTY
                    .withBold(run.bold())
                    .withItalic(run.italic())
                    .withUnderlined(run.underline())
                    .withStrikethrough(run.strikethrough())
                    .withObfuscated(run.obfuscated());
            if (run.hasColor()) {
                style = style.withColor(TextColor.fromRgb(run.color()));
            }
            out.append(Component.literal(run.text()).setStyle(style));
        }
        return out;
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

    /** One run of the original with a single uniform resolved style. */
    public record Seg(String text, Style style) {
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

    /** A marked-up chat content string plus the styles its ⟦CS#⟧ markers refer to. */
    public record MarkedChat(String text, List<Style> styles) {
        public boolean marked() {
            return !styles.isEmpty();
        }
    }

    /** Above this many merged runs the line is treated as a gradient/rainbow: markers would
     *  shred the sentence, so colouring falls back to the proportional stretch instead. */
    private static final int MAX_MARKED_SEGMENTS = 16; // busy broadcasts hit 9-12 runs; gradients are dozens

    private static final java.util.regex.Pattern MARKER =
            java.util.regex.Pattern.compile("\\u27E6\\s*(/?)\\s*CS\\s*(\\d+)\\s*\\u27E7");

    private static String openMarker(int index) {
        return "⟦CS" + index + "⟧";
    }

    private static String closeMarker(int index) {
        return "⟦/CS" + index + "⟧";
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
        return MARKER_RESIDUE.matcher(MARKER.matcher(text).replaceAll("")).replaceAll("");
    }

    /**
     * Wrap each merged style run of the message content in an invisible ⟦CS#⟧…⟦/CS#⟧ marker
     * pair. The whole line is then translated in ONE request and {@link #markedChat} maps
     * every marker region back to its style — a red word stays red on the translated word,
     * wherever the grammar moved it. Gradient-like lines (more than
     * {@value #MAX_MARKED_SEGMENTS} runs) come back unmarked and use the stretch fallback.
     */
    public static MarkedChat markChatContent(Component c, int fromChar) {
        // Coalesce sub-word colour runs first, so a per-letter gradient name never gets a
        // marker pair walled INSIDE a word (the backend can't translate isolated letters).
        // After this every marker boundary lands at a whitespace/punctuation gap → whole
        // words reach the translator. The existing size gate then runs on the coalesced list.
        List<Seg> segs = coalesceSubWordRuns(mergeSegments(segmentsFrom(c, fromChar)));
        if (segs.size() <= 1 || segs.size() > MAX_MARKED_SEGMENTS) {
            StringBuilder plain = new StringBuilder();
            for (Seg seg : segs) plain.append(seg.text());
            return new MarkedChat(plain.toString(), List.of());
        }
        StringBuilder text = new StringBuilder();
        List<Style> styles = new ArrayList<>();
        for (Seg seg : segs) {
            String s = seg.text();
            if (s == null || s.isEmpty()) continue;
            if (s.isBlank()) {
                text.append(s);
                continue;
            }
            int idx = styles.size();
            styles.add(seg.style());
            text.append(openMarker(idx)).append(s).append(closeMarker(idx));
        }
        return new MarkedChat(text.toString(), styles);
    }

    /** Rebuild a translated marked-up line: each ⟦CS#⟧ region gets its original style
     *  (colour AND click/hover). Falls back to the stretch mapping when the translator
     *  ate the markers. */
    public static MutableComponent markedChat(Component original, int contentStart,
                                              String translated, MarkedChat marked) {
        if (translated == null || translated.isEmpty()) return Component.empty();
        if (marked == null || !marked.marked()) {
            return styledAnchored(original, contentStart, stripMarkers(translated));
        }
        java.util.regex.Matcher matcher = MARKER.matcher(translated);
        MutableComponent out = Component.empty();
        int pos = 0;
        Style current = Style.EMPTY;
        Style last = marked.styles().isEmpty() ? Style.EMPTY : marked.styles().get(0);
        boolean saw = false;
        while (matcher.find()) {
            if (matcher.start() > pos) {
                String chunk = translated.substring(pos, matcher.start());
                if (!chunk.isEmpty()) out.append(Component.literal(chunk).setStyle(current == Style.EMPTY ? last : current));
            }
            saw = true;
            int idx = Integer.parseInt(matcher.group(2));
            boolean close = matcher.group(1) != null && !matcher.group(1).isEmpty();
            if (!close && idx >= 0 && idx < marked.styles().size()) {
                current = marked.styles().get(idx);
                last = current;
            } else {
                current = Style.EMPTY;
            }
            pos = matcher.end();
        }
        if (pos < translated.length()) {
            String chunk = translated.substring(pos);
            if (!chunk.isEmpty()) out.append(Component.literal(chunk).setStyle(current == Style.EMPTY ? last : current));
        }
        String plain = out.getString();
        if (!saw || plain.isBlank()) return styledAnchored(original, contentStart, stripMarkerResidue(translated));
        return out;
    }

    /**
     * Colour a whole-content translation from the original's merged style runs, distributing
     * by semantic weight (letters/digits, not raw chars) so colour boundaries land near the
     * matching words. Used when markers are unavailable (Google ate them / gradient lines).
     */
    public static MutableComponent styledChatContent(Component original, int contentStart, String translated) {
        ColorProfile profile = extractFrom(original, contentStart);
        if (translated == null || translated.isEmpty()) return Component.empty();
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
        int total = 0;
        int[] weights = new int[segs.size()];
        for (int i = 0; i < segs.size(); i++) {
            int weight = semanticWeight(segs.get(i).text());
            weights[i] = weight;
            total += weight;
        }
        if (total <= 0) return styled(translated, profile);
        MutableComponent out = Component.empty();
        if (lead > 0) out.append(Component.literal(translated.substring(0, lead)).setStyle(segs.get(0).style()));
        int start = 0;
        int cumulative = 0;
        int lastWeighted = -1;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] > 0) lastWeighted = i;
        }
        for (int i = 0; i < segs.size(); i++) {
            if (weights[i] <= 0) continue;
            cumulative += weights[i];
            int end = (i == lastWeighted) ? core.length()
                    : Math.round((float) cumulative * core.length() / (float) total);
            end = safeBoundary(core, start, Math.max(start, Math.min(end, core.length())));
            if (end > start) {
                out.append(Component.literal(core.substring(start, end)).setStyle(segs.get(i).style()));
                start = end;
            }
        }
        if (start < core.length()) {
            Style style = lastWeighted >= 0 ? segs.get(lastWeighted).style() : Style.EMPTY;
            out.append(Component.literal(core.substring(start)).setStyle(style));
        }
        if (trail < translated.length()) {
            out.append(Component.literal(translated.substring(trail)).setStyle(segs.get(segs.size() - 1).style()));
        }
        return out;
    }

    /**
     * Anchor-aligned colouring for translated single lines (tooltip titles, HUD rows).
     * Fragments the translator keeps verbatim — proper nouns, numbers ("SkyBlock", "500",
     * "Hypixel") — are located in the translation and get EXACTLY their original run's
     * style; the text between anchors is distributed over the intervening runs by
     * semantic weight. Far more accurate than the proportional stretch for the common
     * "coloured name + coloured tag" tooltip titles.
     */
    public static MutableComponent styledAnchored(Component original, int fromChar, String translated) {
        ColorProfile profile = extractFrom(original, fromChar);
        if (translated == null || translated.isEmpty()) return Component.empty();
        List<Seg> segs = mergeSegments(segmentsFrom(original, fromChar));
        if (segs.size() <= 1) return styled(translated, profile);

        int n = segs.size();
        int[] anchorStart = new int[n];
        int[] anchorEnd = new int[n];
        java.util.Arrays.fill(anchorStart, -1);
        int searchFrom = 0;
        boolean anyAnchor = false;
        for (int i = 0; i < n; i++) {
            String probe = segs.get(i).text().strip();
            if (probe.length() < 2) continue; // too short to anchor reliably
            int at = translated.indexOf(probe, searchFrom);
            if (at < 0) continue;
            anchorStart[i] = at;
            anchorEnd[i] = at + probe.length();
            searchFrom = anchorEnd[i];
            anyAnchor = true;
        }
        if (!anyAnchor) {
            // No verbatim anchors survive translation: positional colour mapping would
            // speckle random characters — one clean dominant colour reads far better.
            Style flat = formatStyle(profile);
            if (profile.dominantColor() != ColorProfile.NO_COLOR) {
                flat = flat.withColor(TextColor.fromRgb(profile.dominantColor()));
            }
            return Component.literal(translated).setStyle(flat);
        }

        MutableComponent out = Component.empty();
        int cursor = 0;
        int prevAnchored = -1;
        for (int i = 0; i <= n; i++) {
            boolean atEnd = (i == n);
            if (!atEnd && anchorStart[i] < 0) continue;
            int gapEnd = atEnd ? translated.length() : anchorStart[i];
            if (gapEnd > cursor) {
                appendWeighted(out, translated.substring(cursor, gapEnd),
                        segs, prevAnchored + 1, (atEnd ? n : i) - 1);
                cursor = gapEnd;
            }
            if (!atEnd) {
                if (anchorEnd[i] > cursor) {
                    out.append(Component.literal(translated.substring(cursor, anchorEnd[i]))
                            .setStyle(segs.get(i).style()));
                    cursor = anchorEnd[i];
                }
                prevAnchored = i;
            }
        }
        return out;
    }

    /** Distribute {@code text} over runs {@code segs[from..to]} by semantic weight; a gap
     *  with no run between its anchors (e.g. just a space) keeps the previous run's style. */
    private static void appendWeighted(MutableComponent out, String text, List<Seg> segs, int from, int to) {
        if (text.isEmpty()) return;
        if (from > to) {
            int idx = Math.max(0, Math.min(segs.size() - 1, from - 1));
            out.append(Component.literal(text).setStyle(segs.get(idx).style()));
            return;
        }
        int total = 0;
        int[] weights = new int[to - from + 1];
        for (int i = from; i <= to; i++) {
            weights[i - from] = semanticWeight(segs.get(i).text());
            total += weights[i - from];
        }
        if (total <= 0) {
            out.append(Component.literal(text).setStyle(segs.get(from).style()));
            return;
        }
        int start = 0;
        int cumulative = 0;
        int lastWeighted = -1;
        for (int k = 0; k < weights.length; k++) {
            if (weights[k] > 0) lastWeighted = k;
        }
        for (int k = 0; k < weights.length; k++) {
            if (weights[k] <= 0) continue;
            cumulative += weights[k];
            int end = (k == lastWeighted) ? text.length()
                    : Math.round((float) cumulative * text.length() / (float) total);
            end = safeBoundary(text, start, Math.max(start, Math.min(end, text.length())));
            if (end > start) {
                out.append(Component.literal(text.substring(start, end)).setStyle(segs.get(from + k).style()));
                start = end;
            }
        }
        if (start < text.length()) {
            out.append(Component.literal(text.substring(start)).setStyle(segs.get(to).style()));
        }
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
     *  rainbow has no majority). A single-element group is returned unchanged. */
    private static Seg mergeDominant(List<Seg> group) {
        if (group.size() == 1) return group.get(0);
        StringBuilder text = new StringBuilder();
        Map<Style, Integer> weightByStyle = new java.util.LinkedHashMap<>();
        for (Seg seg : group) {
            text.append(seg.text());
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
        return new Seg(text.toString(), dominant);
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

    /** Word char for the proportional slicer's boundary snap ({@link #safeBoundary}): an
     *  {@link #isWordChar} that is NOT a CJK ideograph. Latin words / underscore usernames /
     *  ASCII proper nouns are kept whole in one colour, while each ideograph legitimately
     *  remains its own colour unit — a translated CJK sentence must still distribute colour
     *  across its characters, so ideographs must stay individually splittable here. */
    private static boolean isSliceWordChar(int cp) {
        return isWordChar(cp) && !Character.isIdeographic(cp);
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

    /**
     * A colour-boundary index for the proportional slicer ({@link #styledChatContent},
     * {@link #appendWeighted}) that never falls INSIDE a maximal Latin/underscore word run —
     * so a verbatim token the translator kept (a player name like {@code Steve}, a proper noun
     * like {@code SkyBlock}, a number, a URL, a {@code xX_Player_Xx} handle, or a ⟦CS#⟧ /
     * ⟦MT#⟧ placeholder body) is emitted WHOLE in one colour instead of as two adjacent
     * differently-coloured literals.
     *
     * <p>Two guards, in order:</p>
     * <ol>
     *   <li>Never split a UTF-16 surrogate pair (the original behaviour).</li>
     *   <li>If the (surrogate-safe) {@code index} sits mid-word — {@link #isSliceWordChar} true
     *       on BOTH sides — snap to a word EDGE. Prefer the END of the word (the whole word
     *       joins the earlier colour run); only if that word reaches the segment end do we snap
     *       to the word START instead (the word joins the later run), and only when that keeps
     *       the current run non-empty ({@code back > start}). The result is always in
     *       {@code (start, len]} for a real snap, so no empty leading/trailing run is created.</li>
     * </ol>
     *
     * <p>CJK ideographs are deliberately NOT word chars here (see {@link #isSliceWordChar}):
     * each ideograph legitimately stays its own colour unit so a translated CJK sentence keeps
     * distributing colour across its characters — this snap does not touch that. It therefore
     * does NOT prevent a <em>transliterated</em> CJK name (e.g. 史蒂夫) from being colour-split;
     * that would need name-awareness at the slicer stage.</p>
     */
    private static int safeBoundary(String text, int start, int index) {
        int len = text.length();
        if (index > 0 && index < len
                && Character.isHighSurrogate(text.charAt(index - 1))
                && Character.isLowSurrogate(text.charAt(index))) {
            index++;
        }
        if (index <= start || index >= len) return index;
        if (!isSliceWordChar(text.codePointBefore(index)) || !isSliceWordChar(text.codePointAt(index))) {
            return index; // boundary is already at a word edge (or between non-word chars)
        }
        // Mid-word: prefer snapping FORWARD to the end of the word (word joins the earlier run).
        int end = index;
        while (end < len) {
            int cp = text.codePointAt(end);
            if (!isSliceWordChar(cp)) break;
            end += Character.charCount(cp);
        }
        if (end < len) return end;
        // The word runs to the segment end: snap BACKWARD to the word start (word joins the
        // later run) unless that would empty the current run, in which case keep the word here.
        int back = index;
        while (back > start) {
            int cp = text.codePointBefore(back);
            if (!isSliceWordChar(cp)) break;
            back -= Character.charCount(cp);
        }
        return back > start ? back : end;
    }

    /** Concatenate per-segment-coloured runs (no trimming / centering) — for prefixed lines. */
    public static MutableComponent buildColored(List<Seg> segs, List<String> translated) {
        MutableComponent core = Component.empty();
        for (int i = 0; i < segs.size(); i++) {
            String t = translated.get(i);
            if (t == null || t.isEmpty()) continue;
            core.append(Component.literal(t).setStyle(segs.get(i).style()));
        }
        return core;
    }

    /** Colour/format profile of a laid-out {@link FormattedCharSequence} line, so a translated
     *  GUI line (e.g. FTB quest description) keeps its colours instead of dropping to white. */
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

    /** Flatten a laid-out {@link FormattedCharSequence} (a wrapped GUI line, e.g. an FTB quest
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
     * Build a per-segment-coloured, re-centred translation of a multi-colour chat line.
     * Each original run keeps its OWN colour (the runs were translated independently), so
     * fixed colours stay on the right words. Outer whitespace is dropped and the line is
     * re-centred by pixel width so a centred original yields a centred translation.
     *
     * @param translated translation of each run (same indices as {@code segs})
     * @param original   the original line string (its leading whitespace marks centering)
     */
    public static MutableComponent buildColoredCentered(Font font, List<Seg> segs,
                                                        List<String> translated, String original) {
        int first = 0;
        int last = segs.size() - 1;
        while (first <= last && (translated.get(first) == null || translated.get(first).isBlank())) first++;
        while (last >= first && (translated.get(last) == null || translated.get(last).isBlank())) last--;

        MutableComponent core = Component.empty();
        int coreWidth = 0;
        for (int i = first; i <= last; i++) {
            String t = translated.get(i);
            if (t == null) continue;
            if (i == first) t = t.stripLeading();
            if (i == last) t = t.stripTrailing();
            if (t.isEmpty()) continue;
            MutableComponent run = Component.literal(t).setStyle(segs.get(i).style());
            coreWidth += font.width(run);
            core.append(run);
        }

        int spaces = leadSpacesToCenter(font, original, coreWidth);
        return spaces > 0 ? Component.literal(" ".repeat(spaces)).append(core) : core;
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
        ColorProfile profile = extract(original);
        MutableComponent translated = (profile.distinctColorCount() >= 2)
                ? withInteractive(styledAnchored(original, 0, decision.translated()), interactiveStyle(original, 0))
                : styled(decision.translated(), profile, original, 0);
        if (decision.mode() == DisplayMode.BOTH) {
            int len = maxLineLength(original.getString(), decision.translated());
            // Wrap the translation block top and bottom for high distinguishability.
            return original.copy()
                    .append(Component.literal("\n")).append(separatorLine(len))
                    .append(Component.literal("\n")).append(translated)
                    .append(Component.literal("\n")).append(separatorLine(len));
        }
        return translated;
    }
}
