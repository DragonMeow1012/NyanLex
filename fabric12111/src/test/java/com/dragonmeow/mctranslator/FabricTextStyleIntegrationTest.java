package com.dragonmeow.mctranslator;

import com.dragonmeow.mctranslator.fabric.FabricTextStyle;
import com.dragonmeow.mctranslator.cache.TranslationCache;
import com.dragonmeow.mctranslator.config.DisplayMode;
import com.dragonmeow.mctranslator.service.TranslationDecision;
import com.dragonmeow.mctranslator.translate.TranslationResult;
import com.dragonmeow.mctranslator.translate.TranslationTemplate;
import com.dragonmeow.mctranslator.translate.TextFilter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricTextStyleIntegrationTest {

    @Test
    void manyStyleRunsAndSubwordGradientsStillUseVerifiedMarkers() {
        net.minecraft.network.chat.MutableComponent source = Component.empty();
        for (int i = 0; i < 80; i++) {
            source.append(Component.literal("x").setStyle(Style.EMPTY.withColor(
                    TextColor.fromRgb((i * 7919) & 0xFFFFFF))));
        }

        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        assertTrue(marked.marked(), "no multi-style line may fall back to position projection");
        assertTrue(marked.text().contains("⟦CS0⟧"));
    }

    @Test
    void requestLinesKeepsHardNewlinesAsSeparateBackendUnits() {
        Component source = Component.literal("Title\n\nBody");
        assertEquals(List.of("Title", "Body"), FabricTextStyle.requestLines(source));
    }

    @Test
    void incomingChatHardLinesKeepBlankRowsPrefixMarkersAndInteractiveStyles() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.network.chat.ClickEvent click = new net.minecraft.network.chat.ClickEvent(
                net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/hello");
        net.minecraft.network.chat.HoverEvent hover = new net.minecraft.network.chat.HoverEvent(
                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("greeting"));
        Style prefix = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));
        Style body = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00))
                .withClickEvent(click).withHoverEvent(hover).withInsertion("hello");
        Style sale = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));
        Style amount = Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55));
        Component source = Component.empty()
                .append(Component.literal("[VIP] Alice: ").setStyle(prefix))
                .append(Component.literal("Hello").setStyle(body))
                .append(Component.literal("\n\n"))
                .append(Component.literal("SALE ").setStyle(sale))
                .append(Component.literal("25% OFF").setStyle(amount));

        List<Component> lines = FabricTextStyle.splitStyledLines(source);
        assertEquals(3, lines.size());
        assertEquals("", lines.get(1).getString(), "the empty hard row must stay in place");

        FabricTextStyle.ChatLinePlan greeting = FabricTextStyle.prepareChatLine(lines.get(0));
        FabricTextStyle.ChatLinePlan blank = FabricTextStyle.prepareChatLine(lines.get(1));
        FabricTextStyle.ChatLinePlan promotion = FabricTextStyle.prepareChatLine(lines.get(2));
        assertEquals("Hello", greeting.content());
        assertFalse(greeting.request().contains("Alice"), "player/rank prefix must not enter the request");
        assertEquals("", blank.request());
        assertTrue(promotion.marked().marked());
        assertTrue(promotion.request().contains("CS0"));
        assertFalse(promotion.request().contains("\n"));

        Component translatedGreeting = FabricTextStyle.rebuildChatLine(greeting, "HELLO_ZH");
        Component translatedPromotion = FabricTextStyle.rebuildChatLine(
                promotion, promotion.request().replace("SALE", "SALE_ZH").replace("OFF", "OFF_ZH"));
        Component rebuilt = FabricTextStyle.joinStyledLines(List.of(
                translatedGreeting, lines.get(1), translatedPromotion));
        assertEquals("[VIP] Alice: HELLO_ZH\n\nSALE_ZH 25% OFF_ZH", rebuilt.getString());

        var greetingSegment = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("HELLO_ZH")).findFirst().orElseThrow();
        assertEquals(0xFFAA00, greetingSegment.style().getColor().getValue());
        assertEquals(click, greetingSegment.style().getClickEvent());
        assertEquals(hover, greetingSegment.style().getHoverEvent());
        assertEquals("hello", greetingSegment.style().getInsertion());
        assertEquals("[VIP] Alice: Hello\n\nSALE 25% OFF", source.getString(),
                "line rebuilding must never mutate the incoming component");
    }

    @Test
    void splitColourPaddingBecomesOneProtectedSummerStoreLayoutSlot() {
        MutableComponent source = Component.empty()
                .append(Component.literal("SUMMER STORE SALE ").setStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF))))
                .append(Component.literal("- ").setStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF))))
                .append(Component.literal("UP TO ").setStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55))))
                .append(Component.literal("25% OFF ").setStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00))))
                .append(Component.literal("- ").setStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF))))
                .append(Component.literal(" 3h").setStyle(
                        Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55))));

        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        String key = new TranslationTemplate().prepare(marked.text()).key();

        assertTrue(key.contains("⟦WS0⟧"),
                "one trailing and one leading coloured space must combine before templating");
        assertTrue(key.indexOf("⟦MT0⟧") < key.indexOf("⟦WS0⟧"));
        assertTrue(key.indexOf("⟦WS0⟧") < key.indexOf("⟦MT1⟧"));
        String moved = key.replace("⟦MT0⟧", "\u0000")
                .replace("⟦WS0⟧", "⟦MT0⟧")
                .replace("\u0000", "⟦WS0⟧");
        assertFalse(TranslationTemplate.layoutSkeletonMatches(key, moved));
    }

    @Test
    void everyChatBlockHasUniqueBlankTopAndBottomSeparators() {
        Component first = FabricTextStyle.chatBlock(
                Component.literal("[MVP+] heden1337 joined the lobby!"),
                Component.literal("[MVP+] heden1337 加入了大廳！"));
        Component second = FabricTextStyle.chatBlock(
                Component.literal("[MVP+] another joined the lobby!"),
                Component.literal("[MVP+] another 加入了大廳！"));
        String[] a = first.getString().split("\n", -1);
        String[] b = second.getString().split("\n", -1);

        assertEquals(4, a.length);
        assertEquals(4, b.length);
        assertFalse(a[0].equals(a[3]), "one block's top and bottom cannot be stack-deduplicated");
        assertFalse(a[3].equals(b[0]), "adjacent player blocks need distinct physical separators");
        assertEquals(a[0].stripTrailing(), a[3].stripTrailing(),
                "the uniqueness salt must use blank trailing spaces only");
        assertFalse(a[0].contains("\u200B") || a[0].contains("\u200C")
                        || a[3].contains("\u200B") || a[3].contains("\u200C"),
                "resource-pack fonts can render zero-width characters as missing glyphs");
    }

    @Test
    void missingChatTranslationShowsOriginalExactlyOnceWithoutFakeBlock() {
        Style style = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF)).withBold(true);
        Component source = Component.literal(
                "SP00KY FESTIVAL The event starts in 1 day!").setStyle(style);

        Component displayed = FabricTextStyle.chatBlock(source, null);

        assertEquals(source.getString(), displayed.getString());
        assertEquals(style, displayed.getStyle(), "the pass-through must preserve chat styling");
        assertFalse(displayed.getString().contains("\n"),
                "a temporary miss must not masquerade as original + identical translation");
    }

    @Test
    void literalServerSectionCodesBecomeRealChatStyleSegments() {
        Component parsed = FabricTextStyle.resolveLegacyCodes(Component.literal(
                "§b[MVP§c+§b] DragonMeow1013 §6joined the lobby!"));
        var segments = FabricTextStyle.segments(parsed);

        assertEquals("[MVP+] DragonMeow1013 joined the lobby!", parsed.getString());
        assertTrue(segments.size() >= 4);
        assertEquals(0x55FFFF, segments.get(0).style().getColor().getValue());
        assertEquals(0xFF5555, segments.get(1).style().getColor().getValue());
        assertEquals(0xFFAA00, segments.get(segments.size() - 1).style().getColor().getValue());
        assertTrue(FabricTextStyle.markChatContent(parsed, 0).marked());
    }

    @Test
    void markedBookRebuildKeepsFormattingAndInteractivePayloadOnTranslatedAction() {
        Style link = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00))
                .withBold(true).withUnderlined(true).withInsertion("claim-reward");
        MutableComponent page = Component.empty()
                .append(Component.literal("Daily Reward\n\n"))
                .append(Component.literal("Visit our website to choose a reward.\n\n"))
                .append(Component.literal("CLICK TO CLAIM").setStyle(link));
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(page, 0);
        assertTrue(marked.marked());

        String translated = marked.text()
                .replace("Daily Reward", "每日獎勵")
                .replace("Visit our website to choose a reward.", "前往網站選擇獎勵。")
                .replace("CLICK TO CLAIM", "點擊領取");
        Component rebuilt = FabricTextStyle.markedChat(page, 0, translated, marked);

        var action = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("點擊領取"))
                .findFirst().orElseThrow();
        assertNotNull(action.style().getColor());
        assertEquals(0xFFAA00, action.style().getColor().getValue());
        assertTrue(action.style().isBold());
        assertTrue(action.style().isUnderlined());
        assertEquals("claim-reward", action.style().getInsertion());
    }

    @Test
    void singleRunFtbFieldKeepsItsExactStyleAndInteractivePayload() {
        Style sourceStyle = Style.EMPTY.withColor(TextColor.fromRgb(0x45D6C8))
                .withItalic(true).withUnderlined(true).withInsertion("open-quest-link");
        Component source = Component.literal("Open linked quest").setStyle(sourceStyle);
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);

        assertFalse(marked.marked());
        Component rebuilt = FabricTextStyle.rebuildRich(source, "開啟連結任務", marked);
        var segment = FabricTextStyle.segments(rebuilt).get(0);

        assertEquals("開啟連結任務", rebuilt.getString());
        assertEquals(sourceStyle, segment.style());
        assertEquals("open-quest-link", segment.style().getInsertion());
    }

    @Test
    void scoreboardLabelAndLiveValueKeepTheirOwnColours() {
        Component source = FabricTextStyle.resolveLegacyCodes(
                Component.literal("§fPurse: §6690,364"));
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        assertTrue(marked.marked());

        Component rebuilt = FabricTextStyle.rebuildRich(
                source, marked.text().replace("Purse", "錢包"), marked);
        var label = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("錢包")).findFirst().orElseThrow();
        var amount = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("690,364")).findFirst().orElseThrow();

        assertEquals(0xFFFFFF, label.style().getColor().getValue());
        assertEquals(0xFFAA00, amount.style().getColor().getValue());
    }

    @Test
    void reorderedObjectiveTranslationKeepsYellowInstructionAndAquaDestination() {
        Component source = FabricTextStyle.resolveLegacyCodes(
                Component.literal("§eEnter the §bSecurity Hall"));
        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        String translated = marked.text()
                .replace("Enter the", "進入")
                .replace("Security Hall", "保全大廳");
        Component rebuilt = FabricTextStyle.rebuildRich(source, translated, marked);

        var instruction = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("進入")).findFirst().orElseThrow();
        var destination = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("保全大廳")).findFirst().orElseThrow();
        assertEquals(0xFFFF55, instruction.style().getColor().getValue());
        assertEquals(0x55FFFF, destination.style().getColor().getValue());
    }

    @Test
    void colouredSurfacesSendVerifiedStyleMarkersAndRestoreThem() {
        FabricTextStyle.clearRenderMemo();
        Component source = FabricTextStyle.resolveLegacyCodes(
                Component.literal("§fPurse: §6690,364"));
        java.util.concurrent.atomic.AtomicReference<String> submitted =
                new java.util.concurrent.atomic.AtomicReference<>();

        Component rebuilt = FabricTextStyle.renderTranslated("scoreboard", source, request -> {
            submitted.set(request);
            return TranslationDecision.of(
                    DisplayMode.TRANSLATION, request, request.replace("Purse", "錢包"));
        });

        assertTrue(submitted.get().contains("⟦CS"));
        var label = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("錢包")).findFirst().orElseThrow();
        var amount = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("690,364")).findFirst().orElseThrow();
        assertEquals(0xFFFFFF, label.style().getColor().getValue());
        assertEquals(0xFFAA00, amount.style().getColor().getValue());
    }

    @Test
    void scoreboardBothModeAddsTheOriginalExactlyOnce() {
        Component source = Component.literal("Purse: 12,988");

        Component rendered = FabricTextStyle.renderTranslated("scoreboard", source,
                request -> TranslationDecision.of(
                        DisplayMode.BOTH, request, request.replace("Purse", "錢包")));

        assertEquals("Purse: 12,988　錢包: 12,988", rendered.getString());
    }

    @Test
    void markerLossFallsBackToTheOriginalInsteadOfGuessingColours() {
        Component source = FabricTextStyle.resolveLegacyCodes(
                Component.literal("§eEnter the §bSecurity Hall"));
        FabricTextStyle.MarkedChat localStyles = FabricTextStyle.markChatContent(source, 0);
        Component rebuilt = FabricTextStyle.rebuildRich(source, "進入保全大廳", localStyles);

        assertEquals("Enter the Security Hall", rebuilt.getString());
        var instruction = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("Enter the")).findFirst().orElseThrow();
        var destination = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("Security Hall")).findFirst().orElseThrow();
        assertEquals(0xFFFF55, instruction.style().getColor().getValue());
        assertEquals(0x55FFFF, destination.style().getColor().getValue());
    }

    @Test
    void semanticCacheHitFillsNewStyleTopologyWithoutAnotherAiResponse() {
        Style gray = Style.EMPTY.withColor(TextColor.fromRgb(0xAAAAAA));
        Style red = Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555));
        Component source = Component.empty()
                .append(Component.literal("and exploding for ").setStyle(gray))
                .append(Component.literal("20,105.9").setStyle(red))
                .append(Component.literal(" damage.").setStyle(gray));
        FabricTextStyle.MarkedChat styles = FabricTextStyle.markChatContent(source, 0);

        Component rebuilt = FabricTextStyle.rebuildRich(source,
                TextFilter.markStyleFallback("並爆炸造成 20,105.9 傷害。"), styles);

        assertEquals("並爆炸造成 20,105.9 傷害。", rebuilt.getString());
        var amount = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("20,105.9")).findFirst().orElseThrow();
        assertEquals(red, amount.style(), "verbatim value anchors keep their exact colour");
    }

    @Test
    void anchoredFallbackKeepsAnchorStylesAndOneSingleStylePerGap() {
        Style aqua = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));
        Style gold = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00));
        Style yellow = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));
        Component source = Component.empty()
                .append(Component.literal("Earn ").setStyle(aqua))
                .append(Component.literal("500").setStyle(gold))
                .append(Component.literal(" coins from the daily quest on ").setStyle(aqua))
                .append(Component.literal("SkyBlock").setStyle(yellow));

        // Reordered translation: only "SkyBlock" and "500" survive verbatim as anchors.
        Component rebuilt = FabricTextStyle.styledAnchored(
                source, 0, "在 SkyBlock 完成每日任務可賺取 500 枚硬幣");

        assertEquals("在 SkyBlock 完成每日任務可賺取 500 枚硬幣", rebuilt.getString());
        var segments = FabricTextStyle.segments(rebuilt);
        for (var segment : segments) {
            if (segment.text().equals("SkyBlock")) {
                assertEquals(yellow, segment.style(), "verbatim anchors keep their exact style");
            } else if (segment.text().equals("500")) {
                assertEquals(gold, segment.style(), "verbatim anchors keep their exact style");
            } else {
                // Every gap takes exactly ONE style: the dominant style of the original
                // fragment between the same anchors (aqua body text) — never a
                // proportional / positional colour split inside the gap.
                assertEquals(aqua, segment.style(),
                        "gap text must carry a single semantically-derived style: " + segment.text());
            }
        }
        // Each gap is emitted as one run, so no gap can ever contain two colours.
        long anchorRuns = segments.stream()
                .filter(s -> s.text().equals("SkyBlock") || s.text().equals("500")).count();
        assertEquals(2, anchorRuns);
        assertEquals(5, segments.size(), "leading gap + anchor + middle gap + anchor + trailing gap");
    }

    @Test
    void markerlessFallbackUsesTheDominantStyleWithoutProportionalSplits() {
        Style yellow = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));
        Style aqua = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));
        Component source = Component.empty()
                .append(Component.literal("Enter the security ").setStyle(yellow))
                .append(Component.literal("Hall").setStyle(aqua));

        Component rebuilt = FabricTextStyle.styledChatContent(source, 0, "進入保全大廳");

        assertEquals("進入保全大廳", rebuilt.getString());
        var segments = FabricTextStyle.segments(rebuilt);
        assertEquals(1, segments.size(),
                "no anchors -> the whole core is ONE run; never a guessed colour boundary");
        assertEquals(yellow, segments.get(0).style(),
                "the core takes the original's dominant (highest semantic weight) style");
    }

    @Test
    void markerlessFallbackKeepsEdgeDecorationStylesAroundTheSingleStyledCore() {
        Style gray = Style.EMPTY.withColor(TextColor.fromRgb(0xAAAAAA));
        Style yellow = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));
        Component source = Component.empty()
                .append(Component.literal("»» ").setStyle(gray))
                .append(Component.literal("Enter the hall now").setStyle(yellow))
                .append(Component.literal(" ««").setStyle(gray));

        Component rebuilt = FabricTextStyle.styledChatContent(source, 0, " 立即進入大廳 ");

        assertEquals(" 立即進入大廳 ", rebuilt.getString());
        var segments = FabricTextStyle.segments(rebuilt);
        assertEquals(3, segments.size());
        assertEquals(gray, segments.get(0).style(), "leading whitespace keeps the first run's style");
        assertEquals(yellow, segments.get(1).style(), "core takes the dominant style in one run");
        assertEquals(gray, segments.get(2).style(), "trailing whitespace keeps the last run's style");
    }

    @Test
    void allDecorativeMultiRunFallbackCollapsesToTheFlatDominantColour() {
        Style red = Style.EMPTY.withColor(TextColor.fromRgb(0xFF5555));
        Style blue = Style.EMPTY.withColor(TextColor.fromRgb(0x5555FF));
        Component source = Component.empty()
                .append(Component.literal("***").setStyle(red))
                .append(Component.literal("!!").setStyle(blue));

        Component rebuilt = FabricTextStyle.styledChatContent(source, 0, "★★★");

        assertEquals("★★★", rebuilt.getString());
        var segments = FabricTextStyle.segments(rebuilt);
        assertEquals(1, segments.size(), "a purely decorative line stays one flat run");
        assertEquals(0xFF5555, segments.get(0).style().getColor().getValue(),
                "flat colour is the profile's dominant colour, not a per-character stretch");
    }

    @Test
    void wrappedLoreDetectionJoinsLowercaseContinuationButNotEnchantRows() {
        assertTrue(FabricTextStyle.continuesSentence(
                "Shoots a guided spirit bat, following your aim",
                "and exploding for 20,105.9 damage."));
        assertTrue(FabricTextStyle.continuesSentence(
                "Teleport 8 blocks ahead of you and",
                "gain +50 Speed for 3 seconds."));
        assertFalse(FabricTextStyle.continuesSentence(
                "Ultimate Wise V, Execute V, Experience III",
                "Luck V, Vampirism V"));
    }

    @Test
    void informationParagraphUsesOneRequestWithProtectedLineBreaks() {
        List<Component> paragraph = List.of(
                Component.literal("Ability: Guided Bat"),
                Component.literal("Shoots a guided spirit bat, following your aim"),
                Component.literal("and exploding for 20,105.9 damage."));

        String request = FabricTextStyle.paragraphRequestText(paragraph);
        String plain = com.dragonmeow.mctranslator.translate.TextFilter.stripTranslationMarkers(request);

        assertTrue(plain.contains("⟦PB0⟧"), "independent rows keep their protected break");
        // A lower-case continuation of a server-wrapped sentence no longer gets a PB wall:
        // it joins its sentence with one space so the model translates the sentence whole.
        assertFalse(plain.contains("⟦PB1⟧"), plain);
        assertTrue(plain.contains("your aim and exploding"), plain);
        assertFalse(plain.contains("\n"), "one paragraph must occupy one backend item");
        assertEquals(1, request.lines().count());
    }

    @Test
    void scrambledParagraphBreaksFlattenToOneBlockWithoutTokenResidue() {
        Component tooltip = Component.literal(
                "Ability: Guided Bat\nUltimate Wise V, Execute V\nLuck V, Vampirism V");

        // The model reordered the PB tokens: restoring on them would shuffle the rows.
        Component rebuilt = FabricTextStyle.renderTranslated("tooltip", tooltip, request -> {
            assertEquals(2, com.dragonmeow.mctranslator.translate.ParagraphModel
                    .countBreakTokens(request), request);
            return TranslationDecision.of(DisplayMode.TRANSLATION, request,
                    "技能：導引蝙蝠 ⟦PB1⟧ 終極智慧V、處決V ⟦PB0⟧ 幸運V、吸血V");
        });

        assertEquals("技能：導引蝙蝠終極智慧V、處決V幸運V、吸血V", rebuilt.getString(),
                "a scrambled PB sequence flattens to one flowing block");
        assertFalse(rebuilt.getString().contains("PB"), "no token residue reaches the screen");
        assertFalse(rebuilt.getString().contains("\n"), "no guessed row boundaries remain");
    }

    @Test
    void markedChatTranslationKeepsRankAndMessageColours() {
        Component source = FabricTextStyle.resolveLegacyCodes(Component.literal(
                "§b[MVP§c+§b] DragonMeow §6joined the lobby!"));
        FabricTextStyle.MarkedChat localStyles = FabricTextStyle.markChatContent(source, 0);
        Component rebuilt = FabricTextStyle.rebuildRich(
                source, localStyles.text().replace("joined the lobby!", "加入了大廳！"), localStyles);

        var translatedMessage = FabricTextStyle.segments(rebuilt).stream()
                .filter(seg -> seg.text().contains("加入了大廳")).findFirst().orElseThrow();
        assertEquals(0xFFAA00, translatedMessage.style().getColor().getValue());
    }

    @Test
    void plainBookResponseKeepsPerLineStylesAndInteractiveLinkPayload() {
        FabricTextStyle.clearRenderMemo();
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        net.minecraft.network.chat.ClickEvent click = new net.minecraft.network.chat.ClickEvent(
                net.minecraft.network.chat.ClickEvent.Action.OPEN_URL, "https://example.invalid/reward");
        net.minecraft.network.chat.HoverEvent hover = new net.minecraft.network.chat.HoverEvent(
                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal("Open reward"));
        Style title = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF)).withBold(true);
        Style body = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF));
        Style link = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00))
                .withBold(true).withUnderlined(true).withInsertion("claim-reward")
                .withClickEvent(click).withHoverEvent(hover);
        Component page = Component.empty()
                .append(Component.literal("Daily Reward").setStyle(title))
                .append(Component.literal("\n"))
                .append(Component.literal("Visit our website to choose one of three random rewards.").setStyle(body))
                .append(Component.literal("\n"))
                .append(Component.literal("CLICK TO CLAIM").setStyle(link));
        java.util.List<String> submitted = new java.util.ArrayList<>();

        Component rebuilt = FabricTextStyle.renderTranslated("book-plain-test", page, request -> {
            submitted.add(request);
            String translated = request
                    .replace("Daily Reward", "每日獎勵")
                    .replace("Visit our website to choose one of three random rewards.",
                            "前往網站，從三張隨機獎勵卡中選擇一張。")
                    .replace("CLICK TO CLAIM", "點擊領取");
            return TranslationDecision.of(DisplayMode.TRANSLATION, request, translated);
        });

        assertEquals(1, submitted.size(), submitted.toString());
        assertTrue(submitted.get(0).contains("⟦PB0⟧"), submitted.get(0));
        assertTrue(submitted.get(0).contains("⟦PB1⟧"), submitted.get(0));
        var segments = FabricTextStyle.segments(rebuilt);
        var rebuiltTitle = segments.stream().filter(s -> s.text().contains("每日獎勵"))
                .findFirst().orElseThrow();
        var rebuiltBody = segments.stream().filter(s -> s.text().contains("前往網站"))
                .findFirst().orElseThrow();
        var rebuiltLink = segments.stream().filter(s -> s.text().contains("點擊領取"))
                .findFirst().orElseThrow();
        assertEquals(title, rebuiltTitle.style());
        assertEquals(body, rebuiltBody.style());
        assertEquals(link, rebuiltLink.style());
        assertEquals(click, rebuiltLink.style().getClickEvent());
        assertEquals(hover, rebuiltLink.style().getHoverEvent());
    }

    @Test
    void reorderedVerbatimAnchorsKeepTheirOwnCompleteStyles() {
        Style amountStyle = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00))
                .withInsertion("amount");
        net.minecraft.network.chat.ClickEvent click = new net.minecraft.network.chat.ClickEvent(
                net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, "/visit Alice");
        Style nameStyle = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55))
                .withUnderlined(true).withInsertion("player").withClickEvent(click);
        Component source = Component.empty()
                .append(Component.literal("Earn ").withStyle(style -> style.withColor(0x55FFFF)))
                .append(Component.literal("100").setStyle(amountStyle))
                .append(Component.literal(" coins from ").withStyle(style -> style.withColor(0x55FFFF)))
                .append(Component.literal("Alice").setStyle(nameStyle));

        FabricTextStyle.MarkedChat marked = FabricTextStyle.markChatContent(source, 0);
        String translated = "⟦CS3⟧Alice⟦/CS3⟧"
                + "⟦CS2⟧ 提供的 ⟦/CS2⟧"
                + "⟦CS1⟧100⟦/CS1⟧"
                + "⟦CS0⟧ 枚硬幣⟦/CS0⟧";
        Component rebuilt = FabricTextStyle.rebuildRich(source, translated, marked);
        var alice = FabricTextStyle.segments(rebuilt).stream()
                .filter(s -> s.text().contains("Alice")).findFirst().orElseThrow();
        var amount = FabricTextStyle.segments(rebuilt).stream()
                .filter(s -> s.text().contains("100")).findFirst().orElseThrow();
        assertEquals(nameStyle, alice.style());
        assertEquals(click, alice.style().getClickEvent());
        assertEquals(amountStyle, amount.style());
    }

    @Test
    void legitimateCsProductNameStaysPlainAndComplete() {
        Component first = Component.literal("Play CS2").withStyle(style -> style.withColor(0x55FFFF));

        Component rebuilt = FabricTextStyle.rebuildRich(
                first, "今天玩 CS2", FabricTextStyle.markChatContent(first, 0));
        assertEquals("今天玩 CS2", rebuilt.getString());
    }

    @Test
    void binAuctionGrammarReorderKeepsActionYellowAndItemWhite() {
        Style yellow = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));
        Style white = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF));
        Component source = Component.empty()
                .append(Component.literal("BIN Auction started for ").setStyle(yellow))
                .append(Component.literal("White Gift Talisman!").setStyle(white));

        Component rebuilt = FabricTextStyle.renderTranslated("chat", source, request ->
                TranslationDecision.of(DisplayMode.TRANSLATION, request,
                        "⟦CS1⟧白色禮物護符⟦/CS1⟧"
                                + "⟦CS0⟧的 BIN 拍賣已開始！⟦/CS0⟧"));

        var item = FabricTextStyle.segments(rebuilt).stream()
                .filter(s -> s.text().contains("白色禮物護符")).findFirst().orElseThrow();
        var action = FabricTextStyle.segments(rebuilt).stream()
                .filter(s -> s.text().contains("BIN 拍賣已開始")).findFirst().orElseThrow();
        assertEquals(white, item.style());
        assertEquals(yellow, action.style());
    }

    @Test
    void collectedAuctionKeepsEveryTranslatedSemanticPhraseInItsSourceStyle() {
        Style gold = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00));
        Style yellow = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));
        Style white = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF));
        net.minecraft.network.chat.ClickEvent click = new net.minecraft.network.chat.ClickEvent(
                net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, "/visit Its_Lunith");
        Style player = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF))
                .withClickEvent(click).withInsertion("Its_Lunith");
        Component source = Component.empty()
                .append(Component.literal("You collected ").setStyle(gold))
                .append(Component.literal("2,000 coins").setStyle(yellow))
                .append(Component.literal(" from selling ").setStyle(gold))
                .append(Component.literal("White Gift Talisman").setStyle(white))
                .append(Component.literal(" to ").setStyle(gold))
                .append(Component.literal("Its_Lunith").setStyle(player))
                .append(Component.literal(" in an auction!").setStyle(gold));
        java.util.concurrent.atomic.AtomicReference<String> submitted =
                new java.util.concurrent.atomic.AtomicReference<>();

        Component rebuilt = FabricTextStyle.renderTranslated(
                "collected-auction-semantic-colours", source, request -> {
                    submitted.set(request);
                    return TranslationDecision.of(DisplayMode.TRANSLATION, request,
                            "⟦CS0⟧你已收取⟦/CS0⟧"
                                    + "⟦CS1⟧2,000枚金幣⟦/CS1⟧"
                                    + "⟦CS2⟧，來源為出售⟦/CS2⟧"
                                    + "⟦CS3⟧白色禮物護符⟦/CS3⟧"
                                    + "⟦CS4⟧給⟦/CS4⟧"
                                    + "⟦CS5⟧Its_Lunith⟦/CS5⟧"
                                    + "⟦CS6⟧，於拍賣中成交！⟦/CS6⟧");
                });

        assertNotNull(submitted.get());
        assertTrue(submitted.get().contains("⟦CS3⟧White Gift Talisman⟦/CS3⟧"));
        assertEquals("你已收取2,000枚金幣，來源為出售白色禮物護符給Its_Lunith，於拍賣中成交！",
                rebuilt.getString());
        assertFalse(rebuilt.getString().contains("CS"));

        var segments = FabricTextStyle.segments(rebuilt);
        assertEquals(gold, segments.stream().filter(s -> s.text().contains("你已收取"))
                .findFirst().orElseThrow().style());
        assertEquals(yellow, segments.stream().filter(s -> s.text().contains("2,000枚金幣"))
                .findFirst().orElseThrow().style());
        assertEquals(gold, segments.stream().filter(s -> s.text().contains("來源為出售"))
                .findFirst().orElseThrow().style());
        assertEquals(white, segments.stream().filter(s -> s.text().contains("白色禮物護符"))
                .findFirst().orElseThrow().style());
        var rebuiltPlayer = segments.stream().filter(s -> s.text().contains("Its_Lunith"))
                .findFirst().orElseThrow();
        assertEquals(player, rebuiltPlayer.style());
        assertEquals(click, rebuiltPlayer.style().getClickEvent());
        assertEquals(gold, segments.stream().filter(s -> s.text().contains("於拍賣中成交"))
                .findFirst().orElseThrow().style());
        assertEquals("You collected 2,000 coins from selling White Gift Talisman to "
                + "Its_Lunith in an auction!", source.getString());
    }

    @Test
    void collectedAuctionFallbackAggregatesRepeatedStylesInsteadOfTurningTheGapWhite() {
        Style gold = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00));
        Style yellow = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFF55));
        Style white = Style.EMPTY.withColor(TextColor.fromRgb(0xFFFFFF));
        Style player = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));
        Component source = Component.empty()
                .append(Component.literal("You collected ").setStyle(gold))
                .append(Component.literal("2,000 coins").setStyle(yellow))
                .append(Component.literal(" from selling ").setStyle(gold))
                .append(Component.literal("White Gift Talisman").setStyle(white))
                .append(Component.literal(" to ").setStyle(gold))
                .append(Component.literal("Its_Lunith").setStyle(player))
                .append(Component.literal(" in an auction!").setStyle(gold));

        Component rebuilt = FabricTextStyle.styledAnchored(source, 0,
                "你已收取2,000枚金幣，來源為出售白色禮物護符給Its_Lunith，於拍賣中成交！");

        var leadingGap = FabricTextStyle.segments(rebuilt).stream()
                .filter(s -> s.text().startsWith("你已收取")).findFirst().orElseThrow();
        assertEquals(gold, leadingGap.style(),
                "repeated gold action runs outweigh one white item run in a safe fallback");
    }

    @Test
    void alternatingDepositColoursRemainBoundToTheirTranslatedPhrases() {
        Style green = Style.EMPTY.withColor(TextColor.fromRgb(0x55FF55));
        Style orange = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00));
        Component source = Component.empty()
                .append(Component.literal("You have deposited ").setStyle(green))
                .append(Component.literal("2.3M coins! ").setStyle(orange))
                .append(Component.literal("You now have ").setStyle(green))
                .append(Component.literal("3.9M coins ").setStyle(orange))
                .append(Component.literal("in your account!").setStyle(green));

        Component rebuilt = FabricTextStyle.renderTranslated("chat", source, request ->
                TranslationDecision.of(DisplayMode.TRANSLATION, request,
                        "⟦CS0⟧您已存入⟦/CS0⟧"
                                + "⟦CS1⟧2.3M枚硬幣！⟦/CS1⟧"
                                + "⟦CS2⟧您的帳戶現在有⟦/CS2⟧"
                                + "⟦CS3⟧3.9M枚硬幣⟦/CS3⟧"
                                + "⟦CS4⟧！⟦/CS4⟧"));

        for (var segment : FabricTextStyle.segments(rebuilt)) {
            if (segment.text().contains("2.3M") || segment.text().contains("3.9M")) {
                assertEquals(orange, segment.style());
            } else {
                assertEquals(green, segment.style());
            }
        }
    }

    @Test
    void tooltipHardLinesShareOneParagraphRequestButRestoreEveryRow() {
        Component tooltip = Component.literal(
                "Fortunate Fractured Mithril Pickaxe\nBreaking Power 5");
        java.util.List<String> requests = new java.util.ArrayList<>();

        Component rebuilt = FabricTextStyle.renderTranslated("tooltip", tooltip, request -> {
            requests.add(request);
            String value = "幸運碎裂秘銀鎬 ⟦PB0⟧ 破壞力5";
            return TranslationDecision.of(DisplayMode.TRANSLATION, request, value);
        });

        assertEquals(1, requests.size(), requests.toString());
        assertTrue(requests.get(0).contains("⟦PB0⟧"), requests.get(0));
        assertEquals("幸運碎裂秘銀鎬\n破壞力5", rebuilt.getString());
    }

    @Test
    void styleFallbackCanSplitPbAcrossColourRunsWithoutLeakingTheToken() {
        Style aqua = Style.EMPTY.withColor(TextColor.fromRgb(0x55FFFF));
        Style gold = Style.EMPTY.withColor(TextColor.fromRgb(0xFFAA00));
        Component block = Component.empty()
                .append(Component.literal("Purse: ").setStyle(aqua))
                .append(Component.literal("690,364").setStyle(gold))
                .append(Component.literal("\n"))
                .append(Component.literal("Objective: ").setStyle(aqua))
                .append(Component.literal("Enter the lobby").setStyle(gold));

        Component rebuilt = FabricTextStyle.renderTranslated("screenTextBlock", block, request ->
                TranslationDecision.of(DisplayMode.TRANSLATION, request,
                        TextFilter.markStyleFallback("錢包：690,364 ⟦PB0⟧ 目標：進入大廳")));

        assertEquals("錢包：690,364\n目標：進入大廳", rebuilt.getString());
        assertFalse(rebuilt.getString().contains("PB"));
    }

    @Test
    void cacheReplacementIsVisibleOnTheVeryNextRenderOfTheSameSource() {
        FabricTextStyle.clearRenderMemo();
        String sourceText = "Aspect of the End";
        TranslationCache cache = new TranslationCache(
                (text, target) -> new TranslationResult("終界之刃", "en"),
                "zh-TW", Runnable::run, 100);
        assertEquals("終界之刃", cache.translateBlocking(sourceText));

        java.util.concurrent.atomic.AtomicInteger renderLookups =
                new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<String, TranslationDecision> cachedDecision = request -> {
            renderLookups.incrementAndGet();
            String translated = cache.getCached(request);
            return translated == null
                    ? TranslationDecision.unchanged(request)
                    : TranslationDecision.of(DisplayMode.TRANSLATION, request, translated);
        };
        Component source = Component.literal(sourceText)
                .withStyle(style -> style.withColor(0x55FFFF));

        Component before = FabricTextStyle.renderTranslated(
                "tooltip-cache-replacement-test", source, cachedDecision);
        assertEquals("終界之刃", before.getString());

        assertTrue(cache.replaceFinal(sourceText, "末影之刃"),
                "tooltip reconciliation must replace the isolated cached item name");
        Component after = FabricTextStyle.renderTranslated(
                "tooltip-cache-replacement-test", source, cachedDecision);

        assertEquals("末影之刃", after.getString(),
                "the renderer must not pin the pre-reconciliation cache value");
        assertEquals(2, renderLookups.get(),
                "the translation cache must be consulted on every render of the same source");
    }
}
