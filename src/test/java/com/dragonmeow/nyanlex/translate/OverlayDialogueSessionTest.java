package com.dragonmeow.nyanlex.translate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OverlayDialogueSessionTest {

    private static final String SENTENCE =
            "Ah. Does this mean we have to walk all the way to Harbor from here?";

    /** Inline backend: a map of known translations and a log of every call. */
    private static final class FakeBackend implements OverlayDialogueSession.Backend {
        final Map<String, String> known = new HashMap<>();
        final List<String> lookups = new ArrayList<>();
        /** Accepted requests, in order. */
        final List<String> requests = new ArrayList<>();
        /** The accepted requests that were asked for as "on screen right now". */
        final List<String> immediateRequests = new ArrayList<>();
        /** Immediate requests turned away because the engine could not send. */
        final List<String> refused = new ArrayList<>();
        boolean engineBusy;

        @Override
        public String lookup(String source) {
            lookups.add(source);
            return known.get(source);
        }

        @Override
        public boolean request(String source, boolean immediate) {
            if (immediate && engineBusy) {
                refused.add(source);
                return false;
            }
            requests.add(source);
            if (immediate) {
                immediateRequests.add(source);
            }
            return true;
        }
    }

    private final OverlayDialogueSession session = new OverlayDialogueSession();
    private final FakeBackend backend = new FakeBackend();

    private OverlayDialogueSession.Outcome show(List<OverlayDialogue.Run> frame, long now) {
        return session.process(frame, now, OverlayFixture.MEASURE, backend);
    }

    private static List<OverlayDialogue.Run> typed(int chars, String icon) {
        String text = SENTENCE.substring(0, chars);
        if (chars <= 42) {
            return OverlayFixture.frame("Mira", icon, text);
        }
        return OverlayFixture.frame("Mira", icon, text.substring(0, 42), text.substring(43));
    }

    @Test
    void typingNeverReachesTheBackendButTheSettledSentenceDoes() {
        long now = 0;
        for (int chars = 1; chars <= SENTENCE.length(); chars++) {
            OverlayDialogueSession.Outcome o = show(typed(chars, "\ue000"), now);
            assertTrue(o.dialogue());
            now += 25;
        }
        // name and hint are asked once at the first frame, never a half sentence
        assertEquals(List.of("Mira", "to continue"), backend.requests);
    }

    @Test
    void requestsOnlyTheFullSentenceAfterHalfASecond() {
        long now = 0;
        for (int chars = 1; chars <= SENTENCE.length(); chars++) {
            show(typed(chars, "\ue000"), now);
            now += 25;
        }
        long last = now - 25;
        assertFalse(backend.requests.contains(SENTENCE));
        // blinking frames keep arriving with identical text
        show(typed(SENTENCE.length(), "\ue001"), last + 300);
        assertFalse(backend.requests.contains(SENTENCE));
        show(typed(SENTENCE.length(), "\ue000"), last + 499);
        assertFalse(backend.requests.contains(SENTENCE));
        show(typed(SENTENCE.length(), "\ue001"), last + 500);
        assertEquals(1, backend.requests.stream().filter(SENTENCE::equals).count());
        show(typed(SENTENCE.length(), "\ue000"), last + 1000);
        show(typed(SENTENCE.length(), "\ue001"), last + 1500);
        assertEquals(1, backend.requests.stream().filter(SENTENCE::equals).count());
        for (String request : backend.requests) {
            assertTrue(request.equals(SENTENCE) || request.equals("Mira") || request.equals("to continue"),
                    "unexpected request: " + request);
        }
    }

    @Test
    void aCachedTranslationIsNotShownWhileTheTextIsStillGrowing() {
        backend.known.put("Ah.", "啊。");
        long now = 0;
        show(typed(1, "\ue000"), now);
        show(typed(2, "\ue000"), now += 25);
        OverlayDialogueSession.Outcome typing = show(typed(3, "\ue000"), now += 25);
        assertNull(typing.runs());
        // the half sentences were never even looked up
        assertFalse(backend.lookups.contains("Ah."));
    }

    @Test
    void aTranslationReplacesTheBodyAndKeepsTheTotalAdvance() {
        backend.known.put(SENTENCE, "啊。這是不是表示我們得從這裡一路走到那座港口去？");
        backend.known.put("Mira", "米拉");
        backend.known.put("to continue", "繼續");
        List<OverlayDialogue.Run> frame = typed(SENTENCE.length(), "\ue001");
        OverlayDialogueSession.Outcome o = show(frame, 0);
        assertTrue(o.dialogue());
        assertNotNull(o.runs());
        List<OverlayDialogue.Run> out = OverlayFixture.toRuns(o.runs(), frame);
        assertEquals(OverlayFixture.total(frame), OverlayFixture.total(out));
        assertTrue(out.stream().anyMatch(r -> r.text().contains("米拉")));
        assertTrue(out.stream().anyMatch(r -> r.text().contains("繼續")));
        assertTrue(out.stream().anyMatch(r -> r.font().equals(OverlayFixture.BODY + 1) && r.text().contains("去")));
        assertTrue(backend.requests.isEmpty());
    }

    @Test
    void aNewPageIsNotTypingEvenWhenItsFirstFrameIsCached() {
        backend.known.put("Hello there.", "你好。");
        show(typed(10, "\ue000"), 0);
        OverlayDialogueSession.Outcome o = show(OverlayFixture.frame("Mira", "\ue000", "Hello there."), 40);
        assertNotNull(o.runs());
    }

    @Test
    void nameAndHintAreRequestedImmediatelyAndOnlyOnce() {
        show(typed(1, "\ue000"), 0);
        show(typed(2, "\ue001"), 25);
        show(typed(3, "\ue000"), 50);
        assertEquals(1, backend.requests.stream().filter("Mira"::equals).count());
        assertEquals(1, backend.requests.stream().filter("to continue"::equals).count());
    }

    @Test
    void hudNoiseBetweenFramesChangesNothing() {
        List<OverlayDialogue.Run> a = new ArrayList<>(typed(SENTENCE.length(), "\ue000"));
        List<OverlayDialogue.Run> b = new ArrayList<>(a);
        b.set(0, OverlayFixture.run(OverlayFixture.HUD, "§a296 \ue008"));
        show(a, 0);
        show(b, 300);
        show(a, 600);
        assertEquals(1, backend.requests.stream().filter(SENTENCE::equals).count());
    }

    @Test
    void anOverlayWithoutADialogueUsesTheOrdinaryPath() {
        OverlayDialogueSession.Outcome o = show(
                List.of(OverlayFixture.run("minecraft:default", "§6Coins: 5")), 0);
        assertFalse(o.dialogue());
        assertNull(o.runs());
        assertTrue(backend.lookups.isEmpty());
    }

    @Test
    void aPageThatDoesNotFitStaysOriginalButNameIsStillTranslated() {
        backend.known.put(SENTENCE, "測".repeat(300));
        backend.known.put("Mira", "米拉");
        List<OverlayDialogue.Run> frame = typed(SENTENCE.length(), "\ue000");
        OverlayDialogueSession.Outcome o = show(frame, 0);
        List<OverlayDialogue.Run> out = OverlayFixture.toRuns(o.runs(), frame);
        assertTrue(out.stream().anyMatch(r -> r.text().contains("米拉")));
        assertFalse(out.stream().anyMatch(r -> r.text().contains("測")));
        assertEquals(OverlayFixture.total(frame), OverlayFixture.total(out));
    }

    @Test
    void aLostRequestIsAskedAgainAfterAPause() {
        List<OverlayDialogue.Run> frame = typed(SENTENCE.length(), "\ue000");
        show(frame, 0);
        show(frame, 600);
        show(frame, 1500);
        assertEquals(1, backend.requests.stream().filter(SENTENCE::equals).count());
        show(frame, 600 + OverlayTextGate.REPEAT_MS);
        assertEquals(2, backend.requests.stream().filter(SENTENCE::equals).count());
    }

    @Test
    void anEngineThatCannotSendTakesNoBodyRequest() {
        backend.engineBusy = true;
        List<OverlayDialogue.Run> frame = typed(SENTENCE.length(), "\ue000");
        long now = 0;
        for (int i = 0; i < 15; i++) {
            show(frame, now);
            now += 100;
        }
        assertFalse(backend.requests.contains(SENTENCE));
        assertTrue(backend.immediateRequests.isEmpty());
        assertFalse(backend.refused.isEmpty(), "the page was due, so it was tried");
        // name and hint are not "on screen right now" requests: they went the ordinary way
        assertEquals(List.of("Mira", "to continue"), backend.requests);
    }

    @Test
    void theMomentTheEngineIsFreeTheTextOnScreenIsRequested() {
        backend.engineBusy = true;
        show(typed(SENTENCE.length(), "\ue000"), 0);
        show(typed(SENTENCE.length(), "\ue001"), 700);
        assertTrue(backend.immediateRequests.isEmpty());
        // the player turned the page while the engine was busy
        List<OverlayDialogue.Run> next = OverlayFixture.frame("Mira", "\ue000", "Another page of words.");
        show(next, 800);
        show(next, 1500);
        assertTrue(backend.immediateRequests.isEmpty());

        backend.engineBusy = false;
        show(next, 1600);
        assertEquals(List.of("Another page of words."), backend.immediateRequests,
                "asked at the very next check, about the page that is on screen now");
    }

    @Test
    void flippingTenPagesWhileBusyRequestsOnlyTheLastOne() {
        backend.engineBusy = true;
        long now = 0;
        List<String> pages = new ArrayList<>();
        for (int page = 1; page <= 10; page++) {
            String text = "Story page number " + "abcdefghij".charAt(page - 1) + " goes here.";
            pages.add(text);
            List<OverlayDialogue.Run> frame = OverlayFixture.frame("Mira", "\ue000", text);
            for (int i = 0; i < 8; i++) {
                show(frame, now);
                now += 100;
            }
        }
        assertTrue(backend.immediateRequests.isEmpty());
        for (String page : pages) {
            assertFalse(backend.requests.contains(page), page);
        }
        backend.engineBusy = false;
        show(OverlayFixture.frame("Mira", "\ue000", pages.get(9)), now);
        assertEquals(List.of(pages.get(9)), backend.immediateRequests);
        for (String page : pages.subList(0, 9)) {
            assertFalse(backend.requests.contains(page), page);
        }
    }

    @Test
    void onlyTheSpokenTextIsAskedForImmediately() {
        show(typed(SENTENCE.length(), "\ue000"), 0);
        show(typed(SENTENCE.length(), "\ue001"), 600);
        assertEquals(List.of(SENTENCE), backend.immediateRequests);
        assertTrue(backend.requests.contains("Mira"));
        assertTrue(backend.requests.contains("to continue"));
    }

    // ---------------------------------------------------------------- coloured phrases

    /**
     * Inline styler: the run whose text is "BLUE..." is the coloured style. Colours are written
     * as [[ ]] around the phrase, like the project's markers around a styled phrase.
     */
    private static final class FakeStyler implements OverlayDialogueSession.BodyStyler {
        final List<OverlayDialogue.Run> runs;
        final List<List<OverlayDialogue.BodyPiece>> keyed = new ArrayList<>();

        FakeStyler(List<OverlayDialogue.Run> runs) {
            this.runs = runs;
        }

        boolean blue(int style) {
            return style >= 0 && runs.get(style).text().startsWith("BLUE");
        }

        @Override
        public String key(List<OverlayDialogue.BodyPiece> pieces) {
            keyed.add(pieces);
            StringBuilder key = new StringBuilder();
            boolean any = false;
            for (OverlayDialogue.BodyPiece piece : pieces) {
                if (blue(piece.styleSource())) {
                    key.append("[[").append(piece.text()).append("]]");
                    any = true;
                } else {
                    key.append(piece.text());
                }
            }
            if (!any) {
                StringBuilder plain = new StringBuilder();
                for (OverlayDialogue.BodyPiece piece : pieces) {
                    plain.append(piece.text());
                }
                return plain.toString();
            }
            return key.toString();
        }

        @Override
        public List<OverlayDialogue.Segment> segments(String translated) {
            int plain = -1;
            int blue = -1;
            for (int i = 0; i < runs.size(); i++) {
                if (runs.get(i).text().startsWith("Plain") && plain < 0) {
                    plain = i;
                }
                if (runs.get(i).text().startsWith("BLUE")) {
                    blue = i;
                }
            }
            List<OverlayDialogue.Segment> out = new ArrayList<>();
            int at = 0;
            while (at < translated.length()) {
                int open = translated.indexOf("[[", at);
                if (open < 0) {
                    out.add(new OverlayDialogue.Segment(translated.substring(at), plain));
                    break;
                }
                int close = translated.indexOf("]]", open);
                if (close < 0) {
                    return null;
                }
                if (open > at) {
                    out.add(new OverlayDialogue.Segment(translated.substring(at, open), plain));
                }
                out.add(new OverlayDialogue.Segment(translated.substring(open + 2, close), blue));
                at = close + 2;
            }
            return out;
        }
    }

    private static List<OverlayDialogue.Run> blueFrame(String blueText, boolean withTail) {
        String[][] rows = withTail
                ? new String[][] {{"Plain words ", blueText}, {" go on."}}
                : new String[][] {{"Plain words ", blueText}};
        return OverlayFixture.frameRows("Mira", "\ue000", rows);
    }

    @Test
    void theKeyCarriesTheMarkersAndTheRequestIsMadeOnceForThePlainTextSettling() {
        String full = "BLUE PHRASE";
        FakeStyler styler = new FakeStyler(blueFrame(full, true));
        long now = 0;
        for (int chars = 1; chars <= full.length(); chars++) {
            session.process(blueFrame(full.substring(0, chars), false), now, OverlayFixture.MEASURE, backend, styler);
            now += 25;
        }
        session.process(blueFrame(full, true), now, OverlayFixture.MEASURE, backend, styler);
        long last = now;
        // typing is judged on the words: after the first frame no half sentence is looked up or asked for
        for (String lookup : new java.util.HashSet<>(backend.lookups)) {
            assertTrue(lookup.equals("Plain words [[B]]") || lookup.equals("Plain words [[BLUE PHRASE]] go on.")
                    || lookup.equals("Mira") || lookup.equals("to continue"), "half sentence looked up: " + lookup);
        }
        assertTrue(backend.immediateRequests.isEmpty());
        session.process(blueFrame(full, true), last + 499, OverlayFixture.MEASURE, backend, styler);
        assertTrue(backend.immediateRequests.isEmpty());
        session.process(blueFrame(full, true), last + 500, OverlayFixture.MEASURE, backend, styler);
        assertEquals(List.of("Plain words [[BLUE PHRASE]] go on."), backend.immediateRequests);
    }

    @Test
    void theTranslationComesBackWithItsColourOnTheMatchingCharacters() {
        List<OverlayDialogue.Run> frame = blueFrame("BLUE PHRASE", true);
        FakeStyler styler = new FakeStyler(frame);
        backend.known.put("Plain words [[BLUE PHRASE]] go on.", "平凡的話[[藍色片語]]繼續。");
        OverlayDialogueSession.Outcome outcome = session.process(frame, 0, OverlayFixture.MEASURE, backend, styler);
        assertNotNull(outcome.runs());
        List<OverlayDialogue.OutRun> body = new ArrayList<>();
        for (OverlayDialogue.OutRun run : outcome.runs()) {
            if (OverlayFixture.BODY.concat("0").equals(run.font())) {
                body.add(run);
            }
        }
        assertEquals(3, body.size());
        assertEquals("藍色片語", body.get(1).text());
        assertTrue(styler.blue(body.get(1).styleSource()));
        assertFalse(styler.blue(body.get(0).styleSource()));
        assertFalse(styler.blue(body.get(2).styleSource()));
        assertEquals(OverlayFixture.total(frame), OverlayFixture.total(OverlayFixture.toRuns(outcome.runs(), frame)));
    }

    @Test
    void aSingleStyleTextStillUsesThePlainKeyAndIsDrawnAsBefore() {
        List<OverlayDialogue.Run> frame = typed(SENTENCE.length(), "\ue000");
        FakeStyler styler = new FakeStyler(frame);
        backend.known.put(SENTENCE, "啊。這是不是表示我們得從這裡一路走到那座港口去？");
        OverlayDialogueSession.Outcome withStyler = session.process(frame, 0, OverlayFixture.MEASURE, backend, styler);
        assertTrue(backend.lookups.contains(SENTENCE), "the plain text is the key when nothing is coloured");

        OverlayDialogueSession plainSession = new OverlayDialogueSession();
        OverlayDialogueSession.Outcome without = plainSession.process(frame, 0, OverlayFixture.MEASURE, backend);
        assertEquals(OverlayFixture.toRuns(without.runs(), frame), OverlayFixture.toRuns(withStyler.runs(), frame));
    }

    @Test
    void aResponseTheStylerCannotUseLeavesThePageOriginal() {
        List<OverlayDialogue.Run> frame = blueFrame("BLUE PHRASE", true);
        FakeStyler styler = new FakeStyler(frame);
        backend.known.put("Plain words [[BLUE PHRASE]] go on.", "[[壞掉了");
        OverlayDialogueSession.Outcome outcome = session.process(frame, 0, OverlayFixture.MEASURE, backend, styler);
        boolean bodyChanged = false;
        if (outcome.runs() != null) {
            for (OverlayDialogue.OutRun run : outcome.runs()) {
                bodyChanged |= run.text().contains("壞");
            }
        }
        assertFalse(bodyChanged);
    }

    // ---------------------------------------------------------------- the page for the chat log

    @Test
    void aTranslatedPageIsReportedWithItsSpeakerAndBothTexts() {
        backend.known.put(SENTENCE, "啊。這是不是表示我們得從這裡一路走到那座港口去？");
        backend.known.put("Mira", "米拉");
        OverlayDialogueSession.Outcome outcome = show(typed(SENTENCE.length(), ""), 0);
        OverlayDialogueSession.Spoken spoken = outcome.spoken();
        assertNotNull(spoken);
        assertEquals("Mira", spoken.speakerOriginal());
        assertEquals("米拉", spoken.speakerTranslated());
        assertEquals(SENTENCE, spoken.bodyOriginal());
        assertEquals("啊。這是不是表示我們得從這裡一路走到那座港口去？", spoken.body().get(0).text());
    }

    @Test
    void nothingIsReportedUntilTheSpokenTextHasATranslation() {
        backend.known.put("Mira", "米拉");
        assertNull(show(typed(SENTENCE.length(), ""), 0).spoken());
    }

    @Test
    void aPageThatDoesNotFitTheBoxIsStillReported() {
        backend.known.put(SENTENCE, "測".repeat(300));
        OverlayDialogueSession.Outcome outcome = show(typed(SENTENCE.length(), ""), 0);
        assertNotNull(outcome.spoken());
        assertNull(outcome.runs(), "the box keeps the original");
    }

    @Test
    void aPageWithoutANameplateHasNoSpeaker() {
        backend.known.put("Just words here.", "只有文字。");
        OverlayDialogueSession.Outcome outcome = show(
                OverlayFixture.frame(null, "", "Just words here."), 0);
        assertNotNull(outcome.spoken());
        assertNull(outcome.spoken().speakerOriginal());
    }

    // ---------------------------------------------------------------- inline icon

    private static List<OverlayDialogue.Run> iconPage() {
        String icon = "\ue0a1";
        int row0 = OverlayFixture.MEASURE.width(OverlayFixture.BODY + 0, 0, "Go to the ") + 2 + 10 + 2
                + OverlayFixture.MEASURE.width(OverlayFixture.BODY + 0, 0, " shop now");
        int row1 = OverlayFixture.MEASURE.width(OverlayFixture.BODY + 1, 0, "Then come back to me.");
        List<OverlayDialogue.Run> r = new ArrayList<>();
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, OverlayFixture.move(-92)));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, "Go to the "));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, OverlayFixture.move(2)));
        r.add(OverlayFixture.run("minecraft:custom/icons", icon));
        r.add(OverlayFixture.run("minecraft:custom/icons", OverlayFixture.move(2)));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, " shop now"));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 1, OverlayFixture.move(-row0)));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 1, "Then come back to me."));
        r.add(OverlayFixture.run(OverlayFixture.BODY + 0, OverlayFixture.move(-row1 + 92)));
        return r;
    }

    @Test
    void aSentenceWithAnIconIsTranslatedAndTheIconIsKept() {
        List<OverlayDialogue.Run> frame = iconPage();
        String plain = "Go to the \ue0a1 shop now Then come back to me.";
        backend.known.put(plain, "請到\ue0a1商店，然後回來找我。");
        OverlayDialogueSession.Outcome outcome = show(frame, 0);
        assertNotNull(outcome.runs(), "the page is translated");
        assertNotNull(outcome.spoken());
        List<OverlayDialogue.Run> out = OverlayFixture.toRuns(outcome.runs(), frame);
        assertEquals(OverlayFixture.total(frame), OverlayFixture.total(out));
        assertTrue(out.stream().anyMatch(r -> r.font().equals("minecraft:custom/icons") && r.text().equals("\ue0a1")),
                "the icon is still drawn in its own font");
        assertTrue(out.stream().anyMatch(r -> r.text().contains("商店")));
        assertFalse(out.stream().anyMatch(r -> r.text().contains("Go to")));
    }

    @Test
    void theKeyOfAnIconSentenceIsAskedForOnceTheTextHasSettled() {
        List<OverlayDialogue.Run> frame = iconPage();
        show(frame, 0);
        show(frame, 499);
        assertTrue(backend.immediateRequests.isEmpty());
        show(frame, 500);
        assertEquals(List.of("Go to the \ue0a1 shop now Then come back to me."), backend.immediateRequests);
    }
}
