package com.dragonmeow.nyanlex.fabric;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.ChatType;
import net.minecraft.network.chat.ChatTypeDecoration;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClientReceiveMessageEvents.ALLOW_CHAT hands the mod the line vanilla is about to draw: Fabric API has already run it through
 * {@code ChatType.Bound#decorate} on every path (signed, filtered signed and disguised). The sender / label format ("&lt;name&gt; text",
 * "[Server] text", "* name text", ...) is therefore part of the delivered line; decorating it a second time printed it twice.
 * The inline fakes below are real chat types built with the vanilla decorations.
 */
class ChatReceiveDecorationTest {

    private record Kind(String id, ChatTypeDecoration decoration, Optional<Component> target, String expected) {
    }

    static {
        // The chat type decorations are built in a static initializer below, so the registries must exist before it runs.
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    private static final String SENDER = "Alice";
    private static final String CONTENT = "the market opens at dawn";

    private static final List<Kind> KINDS = List.of(
            new Kind("chat", ChatTypeDecoration.withSender("chat.type.text"), Optional.empty(),
                    "<Alice> the market opens at dawn"),
            new Kind("say", ChatTypeDecoration.withSender("chat.type.announcement"), Optional.empty(),
                    "[Alice] the market opens at dawn"),
            new Kind("emote", ChatTypeDecoration.withSender("chat.type.emote"), Optional.empty(),
                    "* Alice the market opens at dawn"),
            new Kind("msg incoming", ChatTypeDecoration.incomingDirectMessage("commands.message.display.incoming"),
                    Optional.empty(), "Alice whispers to you: the market opens at dawn"),
            new Kind("team msg", ChatTypeDecoration.teamMessage("chat.type.team.text"),
                    Optional.of(Component.literal("Red")), "Red <Alice> the market opens at dawn"));

    private static ChatType.Bound bound(Kind kind) {
        ChatType type = new ChatType(kind.decoration(), kind.decoration());
        return new ChatType.Bound(Holder.direct(type), Component.literal(SENDER), kind.target());
    }

    /** What Fabric API passes to ALLOW_CHAT: the content already wrapped by the chat type. */
    private static Component delivered(Kind kind) {
        return bound(kind).decorate(Component.literal(CONTENT));
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) count++;
        return count;
    }

    @Test
    void theDeliveredLineAlreadyCarriesTheSenderAndFormatExactlyOnce() {
        for (Kind kind : KINDS) {
            assertEquals(kind.expected(), delivered(kind).getString(), kind.id());
        }
    }

    @Test
    void decoratingTheDeliveredLineAgainIsWhatRepeatedTheSender() {
        for (Kind kind : KINDS) {
            String again = bound(kind).decorate(delivered(kind)).getString();
            assertEquals(2, occurrences(again, SENDER), kind.id() + ": " + again);
        }
    }

    @Test
    void everyChatKindIsShownWithTheSenderAndFormatOnce() {
        for (Kind kind : KINDS) {
            Component shown = NyanLexFabric.renderedChatLine(delivered(kind));
            assertEquals(kind.expected(), shown.getString(), kind.id());
            assertEquals(1, occurrences(shown.getString(), SENDER), kind.id());
        }
    }

    @Test
    void aSystemMessageHasNoChatTypeAndIsShownAsReceived() {
        Component system = Component.literal("<Steve> hello there");
        assertEquals("<Steve> hello there", NyanLexFabric.renderedChatLine(system).getString());
    }

    @Test
    void legacyColourCodesInTheDeliveredLineAreStillResolved() {
        Component shown = NyanLexFabric.renderedChatLine(Component.literal("<Alice> \u00a7ahello"));
        assertEquals("<Alice> hello", shown.getString());
    }

    @Test
    void theNameStaysOutOfTheRequestAndTheTranslationReplacesOnlyTheContent() {
        for (Kind kind : List.of(KINDS.get(0))) {
            Component shown = NyanLexFabric.renderedChatLine(delivered(kind));
            FabricTextStyle.ChatLinePlan plan = FabricTextStyle.prepareChatLine(shown);
            assertEquals(CONTENT, plan.content(), kind.id());
            assertFalse(plan.request().contains(SENDER), kind.id() + ": " + plan.request());
            Component rebuilt = FabricTextStyle.rebuildChatLine(plan, "TRANSLATED");
            assertEquals("<Alice> TRANSLATED", rebuilt.getString(), kind.id());
        }
    }

    @Test
    void translateAndInjectNeverDecoratesTheDeliveredLine() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/dragonmeow/nyanlex/fabric/NyanLexFabric.java"), StandardCharsets.UTF_8);
        int start = source.indexOf("private boolean translateAndInject(");
        int end = source.indexOf("private boolean translateHardLineMessage(", start);
        assertTrue(start > 0 && end > start, "found translateAndInject");
        String body = source.substring(start, end);
        assertFalse(body.contains("decorate("), "the delivered line is used as received");
        assertTrue(body.contains("renderedChatLine("), "the line comes from the single documented helper");
    }
}
