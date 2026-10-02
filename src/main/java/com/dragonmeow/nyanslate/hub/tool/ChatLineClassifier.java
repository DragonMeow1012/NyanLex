package com.dragonmeow.nyanslate.hub.tool;

import com.dragonmeow.nyanslate.translate.EnchantListComposer;
import com.dragonmeow.nyanslate.translate.RarityLineComposer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic classifier used by {@link HubExportTool}'s {@code --drop-chat} flag to split
 * an unlabeled legacy AI-cache row (the mod shipped before any per-row source tag existed)
 * into "chat" (dropped before the row ever reaches the public hub repository) or
 * "item/other" (kept).
 *
 * <p>Author's decision (2026-10-01, see {@code design-hub-download.md} "使用者決定 2."):
 * the FIRST initial hub upload tags every row {@code hypixel.net} but must never publish
 * chat content, only item tooltips and other non-chat UI text. Since the pre-hub cache
 * carries no per-row surface tag, this class works off the row's TEXT SHAPE alone.</p>
 *
 * <p>Priority order, each step short-circuiting the next:</p>
 * <ol>
 *   <li><b>Item/other preservation features</b> (rarity line, stat line, ability line, a
 *       single enchant+level token, an enchant-name list, a reforge/dungeon-star glyph, an
 *       Auction House tooltip field/price/timer — see {@code AUCTION_FIELD}/
 *       {@code AUCTION_PRICE_OR_TIMER}): always KEPT, even if the line also loosely
 *       resembles one of the chat shapes below (a stat line such as
 *       {@code "Strength: +10"} must never be caught by the generic "name: message" shape;
 *       likewise {@code "Seller: ⟦0⟧"} must never be caught by the masked-name fallback
 *       below — 2026-10-01 user decision: AH item info is item content, not chat, even
 *       though it carries a {@code ⟦n⟧}/{@code ⟦MTn⟧} placeholder like genuine chat does).
 *       A genuine whisper/guild/party/dialogue line never pairs a Seller:/Buyer:/Bidder:
 *       label with a placeholder immediately after the colon, so this never masks a real
 *       chat line.</li>
 *   <li><b>Explicit chat shapes</b> named by the user: a rank-badge prefix
 *       ({@code [VIP]}/{@code [MVP+]}/…), a private-message routing prefix
 *       ({@code From}/{@code To ... :}), a guild/party/co-op channel prefix
 *       ({@code Guild >}/{@code Party >}/{@code Co-op >}/{@code Officer >}), a masked
 *       player name immediately followed by a colon ({@code ⟦0⟧: message} — by the time a
 *       line reaches the AI cache, {@code NameMasker}/{@code PlayerNamePatterns} has
 *       already turned every TAB/frame name it recognises into a {@code ⟦n⟧} token, so a
 *       raw-username colon shape is not needed here), and a long, banner-style multi-row
 *       announcement (several {@code ⟦PBn⟧} paragraph rows plus a separator line or a long
 *       all-caps run): all DROPPED.</li>
 *   <li><b>Conservative fallback for anything else carrying a masked name</b>: a
 *       {@code ⟦n⟧} token appears somewhere but the line did not match a clean shape
 *       above. Per the author's instruction ("判不準一律當聊天刪掉"), a masked name is the
 *       strongest signal a real player conversation was once here, so an otherwise
 *       unrecognised shape around one is DROPPED rather than risk publishing a private
 *       line.</li>
 *   <li><b>Default</b>: a line with no item/other feature AND no chat signal at all (no
 *       masked name, no rank/routing prefix, no banner shape) is KEPT. Ordinary item
 *       names, tooltip bodies, GUI/book/sign text and scoreboard lines overwhelmingly fall
 *       here and carry no correlation with any of the listed chat indicators.</li>
 * </ol>
 *
 * <p>This is a best-effort heuristic over a single raw string, not a surface-aware
 * classifier: {@link HubExportTool}'s CHECKLIST output always includes kept AND dropped
 * samples so the author can review and correct misclassifications before the first
 * publish.</p>
 */
public final class ChatLineClassifier {

    private ChatLineClassifier() {
    }

    /** @param chat   {@code true} when this row should be dropped as chat
     *  @param reason short, stable diagnostic tag (never shown to end users; used only in
     *                the export tool's stats breakdown and the replay harness's samples) */
    public record Verdict(boolean chat, String reason) {
        static Verdict keep(String reason) {
            return new Verdict(false, reason);
        }

        static Verdict chat(String reason) {
            return new Verdict(true, reason);
        }
    }

    // ---- item/other preservation features (checked first; always win) ----------------

    /** "Strength: +10", "Crit Chance: +10%", "True Defense: 50", "Speed: -5" — a Title
     *  Case label immediately followed by a signed/unsigned number. Deliberately requires
     *  the label to START with an uppercase ASCII letter (never a ⟦n⟧ masked token or a
     *  digit) and the colon to be followed immediately by a number, so a masked-name chat
     *  line such as "⟦0⟧: 123" can never match this. */
    private static final Pattern STAT_LINE = Pattern.compile(
            "^\\s*[A-Z][A-Za-z '()/-]{1,30}:\\s*[+-]?[0-9]");

    /** "Ability: Flame Breath", "Item Ability: Rain of Arrows RIGHT CLICK". */
    private static final Pattern ABILITY_LINE = Pattern.compile("(?i)\\b(?:Item )?Ability:\\s*\\S");

    /** A single "Name + Roman numeral/level" token, e.g. "Sharpness VII", "Growth 6" — the
     *  one-item shape {@link EnchantListComposer} deliberately leaves alone (no reordering
     *  risk, see its own javadoc), so it needs its own positive check here. */
    private static final Pattern SINGLE_ENCHANT = Pattern.compile(
            "^\\s*[A-Z][A-Za-z]*(?:\\s[A-Z][A-Za-z]*)*\\s+(?:I|II|III|IV|V|VI|VII|VIII|IX|X"
                    + "|XI|XII|XIII|XIV|XV|XVI|XVII|XVIII|XIX|XX|[0-9]{1,3})\\s*$");

    /** Hypixel reforge-star / recombobulated decorations that only ever sit on item names
     *  ({@code ✦}=BLACK FOUR POINTED STAR, {@code ✧}=WHITE FOUR POINTED STAR,
     *  {@code ✪}, {@code ✭}=rendered star glyphs the resource pack substitutes
     *  for "✪"). */
    private static final Pattern ITEM_DECORATION = Pattern.compile("[✦✧✪✭⭐]");

    /** Auction House tooltip field label immediately followed by a protected placeholder
     *  (a masked player name {@code ⟦n⟧}, optionally rank-badged, or a templated
     *  {@code ⟦MTn⟧} number/rank slot) — the structural fingerprint of an AH item
     *  tooltip's Seller/Buyer/Bidder line. 2026-10-01 user decision ("同樣名稱的只有一種
     *  翻譯名稱，同樣解釋的同理"): this is item information, not chat, even though it also
     *  carries protected placeholders — unlike a genuine whisper/guild/party/dialogue
     *  line, which never pairs a "Seller:"/"Buyer:"/"Bidder:" label with a placeholder
     *  immediately after the colon. */
    private static final Pattern AUCTION_FIELD = Pattern.compile(
            "(?i)\\b(?:Seller|Buyer|Bidder)\\s*:\\s*(?:\\[[A-Z]{2,12}\\+{0,3}\\]\\s*)?⟦(?:MT)?[0-9]+⟧");

    /** Auction House tooltip price/countdown line: a label immediately followed by a
     *  templated {@code ⟦MTn⟧} number slot ("Buy it now: ⟦MT2⟧ coins",
     *  "Starting bid: ⟦MT0⟧ coins", "Ends in: ⟦MT3⟧"). */
    private static final Pattern AUCTION_PRICE_OR_TIMER = Pattern.compile(
            "(?i)\\b(?:Buy it now|Starting bid|Top bid|Current bid|Ends in|Time left)\\s*:?\\s*⟦MT[0-9]+⟧");

    // ---- explicit chat shapes (dropped) -----------------------------------------------

    /** "[VIP]", "[MVP+]", "[MVP++]", "[YOUTUBE]", "[ADMIN]" rank badge opening the line. */
    private static final Pattern RANK_PREFIX = Pattern.compile("^\\s*\\[[A-Z]{2,12}\\+{0,3}\\]");

    /** Private-message routing: "From NAME: ...", "To NAME: ..." (optionally rank-badged).
     *  No shipped {@link com.dragonmeow.nyanslate.translate.PlayerNamePatterns} frame masks
     *  this shape's name, so it is matched on the raw username, not a ⟦n⟧ token. */
    private static final Pattern WHISPER_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:From|To)\\s+(?:\\[[A-Z]{2,12}\\+{0,3}\\]\\s*)?[A-Za-z0-9_]{2,16}\\s*:");

    /** Guild/Party/Co-op/Officer channel routing: "Guild > msg", "Party > msg", … */
    private static final Pattern CHANNEL_PREFIX = Pattern.compile(
            "(?i)^\\s*(?:Guild|Party|Co-op|Officer)\\s*>");

    /** A masked player name immediately followed by a colon: "⟦0⟧: message" (optionally
     *  rank-badged: "[MVP+] ⟦0⟧: message"). */
    private static final Pattern NAME_COLON = Pattern.compile(
            "^\\s*(?:\\[[A-Z]{2,12}\\+{0,3}\\]\\s*)?⟦[0-9]+⟧\\s*:\\s*\\S");

    /** Any masked-name token, anywhere in the line — the conservative fallback signal. */
    private static final Pattern MASKED_NAME = Pattern.compile("⟦[0-9]+⟧");

    /** One paragraph-break token (see {@code ParagraphModel.BREAK_TOKEN_PATTERN}). */
    private static final Pattern PB_TOKEN = Pattern.compile("⟦\\s*PB\\s*[0-9]+\\s*⟧");

    /** A banner-style repeated separator ("=====", "-----", "~~~~~", "*****"). */
    private static final Pattern BANNER_SEPARATOR = Pattern.compile("[=~*#-]{5,}");

    /** A long run of upper-case letters (shouty banner text), 10+ letters in a row. */
    private static final Pattern LONG_CAPS_RUN = Pattern.compile("[A-Z]{10,}");

    /** Minimum paragraph-break count for the "long announcement" shape: 5+ rows. */
    private static final int ANNOUNCEMENT_PB_THRESHOLD = 4;

    public static Verdict classify(String sourceKey) {
        if (sourceKey == null || sourceKey.isBlank()) return Verdict.chat("blank");
        String text = sourceKey.strip();

        // ---- 1. item/other preservation features always win ----
        if (RarityLineComposer.match(text) != null) return Verdict.keep("rarity-line");
        if (EnchantListComposer.match(text) != null) return Verdict.keep("enchant-list");
        if (STAT_LINE.matcher(text).find()) return Verdict.keep("stat-line");
        if (ABILITY_LINE.matcher(text).find()) return Verdict.keep("ability-line");
        if (SINGLE_ENCHANT.matcher(text).matches()) return Verdict.keep("single-enchant");
        if (ITEM_DECORATION.matcher(text).find()) return Verdict.keep("item-decoration");
        if (AUCTION_FIELD.matcher(text).find() || AUCTION_PRICE_OR_TIMER.matcher(text).find()) {
            return Verdict.keep("auction-house-tooltip");
        }

        // ---- 2. explicit chat shapes ----
        if (RANK_PREFIX.matcher(text).find()) return Verdict.chat("rank-prefix");
        if (WHISPER_PREFIX.matcher(text).find()) return Verdict.chat("whisper-prefix");
        if (CHANNEL_PREFIX.matcher(text).find()) return Verdict.chat("channel-prefix");
        if (NAME_COLON.matcher(text).find()) return Verdict.chat("masked-name-colon");
        if (isLongPbAnnouncement(text)) return Verdict.chat("long-pb-announcement");

        // ---- 3. conservative fallback: an unrecognised shape still carrying a masked name ----
        if (MASKED_NAME.matcher(text).find()) return Verdict.chat("masked-name-uncertain");

        // ---- 4. default: no item feature, no chat signal at all ----
        return Verdict.keep("no-chat-signal");
    }

    public static boolean isLikelyChat(String sourceKey) {
        return classify(sourceKey).chat();
    }

    private static boolean isLongPbAnnouncement(String text) {
        if (countMatches(PB_TOKEN, text) < ANNOUNCEMENT_PB_THRESHOLD) return false;
        return BANNER_SEPARATOR.matcher(text).find() || LONG_CAPS_RUN.matcher(text).find();
    }

    private static int countMatches(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) count++;
        return count;
    }
}
