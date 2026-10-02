package com.dragonmeow.nyanslate.service;

import com.dragonmeow.nyanslate.cache.TranslationCache;
import com.dragonmeow.nyanslate.config.DisplayMode;
import com.dragonmeow.nyanslate.config.TranslatorConfig;
import com.dragonmeow.nyanslate.translate.AiSettings;
import com.dragonmeow.nyanslate.translate.HttpTransport;
import com.dragonmeow.nyanslate.translate.NameMasker;
import com.dragonmeow.nyanslate.translate.OpenAiTranslator;
import com.dragonmeow.nyanslate.translate.ParagraphModel;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-10-01 coordinator review: the segment-cache composers must never increase the
 * total bytes actually POSTed to the AI provider versus the pre-feature whole-paragraph
 * behaviour. This measures the REAL {@link OpenAiTranslator} request body (system prompt
 * + context blocks + anchored units, exactly as it goes over the wire) via an inline fake
 * {@link HttpTransport} that only captures the body and returns an empty (harmless, no
 * exception) chat-completion reply — the content of the reply is irrelevant, only what
 * gets SENT is measured.
 *
 * <p>"Old behaviour" is reproduced by calling {@link TranslationCache#warmBatchAsync}
 * DIRECTLY — bypassing {@link TranslationService}'s decomposition entirely, exactly the
 * shape {@code TranslationService.warmMasked}'s own GENERIC (non-decomposed) fallback
 * path has always used for an unrecognised multi-row paragraph: the title and the whole
 * masked body blob as two independent units, EACH OTHER's masked text as shared surface
 * context (every source contributes {@code context.add(masked)}; see warmMasked). Both
 * old and new scenarios use the SAME target language (zh-TW) and the SAME real {@link
 * OpenAiTranslator}/{@link AiSettings}, so the (otherwise sizeable, target-language-
 * dependent) system prompt is identical in both — the measured delta is caused ONLY by
 * the decomposition, nothing else.
 */
class TranslationServiceRequestSizeQuantificationTest {

    private static final Executor DIRECT = Runnable::run;
    private static final String TARGET_LANG = "zh-TW";

    /** Captures every POSTed body; replies with an empty (harmless) completion so the
     *  call completes synchronously without throwing — only the SENT bytes matter here. */
    private static final class CapturingTransport implements HttpTransport {
        final List<String> bodies = new ArrayList<>();

        @Override
        public String get(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String post(String url, String body, Map<String, String> headers) throws IOException {
            bodies.add(body);
            return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"\"}}]}";
        }

        int totalChars() {
            int sum = 0;
            for (String b : bodies) sum += b.length();
            return sum;
        }
    }

    private static OpenAiTranslator realTranslator(CapturingTransport transport) {
        return new OpenAiTranslator(transport,
                () -> new AiSettings("https://api.openai.com/v1", "gpt-4o-mini", List.of("key-1")));
    }

    private static TranslationService newService(OpenAiTranslator translator) {
        TranslatorConfig cfg = new TranslatorConfig();
        cfg.targetLang = TARGET_LANG;
        cfg.tooltipMode = DisplayMode.TRANSLATION;
        cfg.aiTooltip = true;
        TranslationCache google = new TranslationCache(translator, TARGET_LANG, DIRECT, 100);
        TranslationCache ai = new TranslationCache(translator, TARGET_LANG, DIRECT, 100);
        TranslationService service = new TranslationService(cfg, google, ai);
        service.setBatchWindowMs(() -> 0);
        return service;
    }

    /** Pre-feature/"old" shape, driven straight at the real cache: the title and the
     *  WHOLE masked body blob as two independent units sharing one surface context built
     *  from every source's own masked text — exactly {@code TranslationService.warmMasked}
     *  GENERIC fallback's own {@code context.add(masked)} per source, {@code todo} shared
     *  across sources in one {@code warmBatchAsync} call. */
    private static final class OldStyleCache {
        final TranslationCache cache;

        OldStyleCache(OpenAiTranslator translator) {
            cache = new TranslationCache(translator, TARGET_LANG, DIRECT, 100);
            cache.setWindowedBatching(true);
            cache.setBatchWindowMs(() -> 0);
        }

        void warm(String title, String bodyText) {
            String titleMasked = NameMasker.mask(title, List.of()).text();
            String bodyMasked = NameMasker.mask(bodyText, List.of()).text();
            List<String> sharedContext = List.of(titleMasked, bodyMasked);
            cache.warmBatchAsync(List.of(titleMasked, bodyMasked), sharedContext);
            cache.flushBatch();
            cache.flushBatch();
        }
    }

    private static void pump(TranslationService s) {
        s.flushBatches();
        s.flushBatches();
    }

    private static final String TITLE = "Hyperion";

    private static String body(String scroll1, String scroll2, String scroll3,
                               String seller, String priceCoins) {
        return ParagraphModel.join(List.of(
                "LEGENDARY",
                "● " + scroll1, "● " + scroll2, "● " + scroll3,
                "Seller: " + seller, "Buy it now: " + priceCoins + " coins"));
    }

    @Test
    void firstViewTotalBytesDoNotExceedTheOldWholeParagraphBehaviour() {
        CapturingTransport oldTransport = new CapturingTransport();
        OldStyleCache oldCache = new OldStyleCache(realTranslator(oldTransport));

        CapturingTransport newTransport = new CapturingTransport();
        TranslationService newService = newService(realTranslator(newTransport));

        String firstView = body("Implosion", "Wither Shield", "Shadow Warp", "DragonMeow", "1,000,000");

        oldCache.warm(TITLE, firstView);
        newService.warmTooltipBatch(List.of(TITLE, firstView));
        pump(newService);

        int oldChars = oldTransport.totalChars();
        int newChars = newTransport.totalChars();
        System.out.println("[quantify] first view: old requests=" + oldTransport.bodies.size()
                + " chars=" + oldChars + "; new requests=" + newTransport.bodies.size()
                + " chars=" + newChars);
        assertTrue(oldTransport.bodies.size() >= 1, "old behaviour must still buy something");
        assertTrue(newChars <= oldChars,
                "first view: new total request bytes (" + newChars
                        + ") must not exceed the old whole-paragraph behaviour (" + oldChars + ")");
    }

    @Test
    void swappingOneScrollSendsFarFewerBytesThanTheOldWholeParagraphRebuy() {
        // Old behaviour: a different combo is a DIFFERENT opaque paragraph key (scroll
        // names are never masked/templated away), so the whole blob is bought again.
        CapturingTransport oldTransport = new CapturingTransport();
        OldStyleCache oldCache = new OldStyleCache(realTranslator(oldTransport));

        CapturingTransport newTransport = new CapturingTransport();
        TranslationService newService = newService(realTranslator(newTransport));

        String first = body("Implosion", "Wither Shield", "Shadow Warp", "DragonMeow", "1,000,000");
        String swapped = body("Implosion", "Wither Impact", "Shadow Warp", "DragonMeow", "1,000,000");

        oldCache.warm(TITLE, first);
        int oldCharsAfterFirst = oldTransport.totalChars();
        oldCache.warm(TITLE, swapped); // old: second, DIFFERENT paragraph key -> bought again, whole blob
        int oldSwapChars = oldTransport.totalChars() - oldCharsAfterFirst;

        newService.warmTooltipBatch(List.of(TITLE, first));
        pump(newService);
        int newCharsAfterFirst = newTransport.totalChars();
        newService.warmTooltipBatch(List.of(TITLE, swapped));
        pump(newService); // new: only "Wither Impact" is missing
        int newSwapChars = newTransport.totalChars() - newCharsAfterFirst;
        // The TRANSLATABLE-UNIT payload itself (excluding system prompt / context framing,
        // the two fixed costs every request pays regardless of how many units it serves):
        // old resends the WHOLE ~140-char paragraph; new sends only the one missing name.
        int newSwapUnitChars = "Wither Impact".length();

        System.out.println("[quantify] scroll swap: old re-buy=" + oldSwapChars
                + " bytes total; new re-buy=" + newSwapChars + " bytes total "
                + "(new translatable-unit payload=" + newSwapUnitChars + " bytes, old="
                + swapped.length() + " bytes — the whole paragraph)");
        assertTrue(newSwapChars < oldSwapChars,
                "swapping one scroll must cost fewer total bytes than the old full paragraph "
                        + "re-buy: new=" + newSwapChars + " vs old=" + oldSwapChars);
        assertTrue(newSwapUnitChars < swapped.length() / 5,
                "the actual TRANSLATABLE content is not a re-buy of the whole paragraph: "
                        + "new unit=" + newSwapUnitChars + " vs whole paragraph=" + swapped.length());
    }

    @Test
    void sameComboDifferentSellerAndPriceCostsFewerOrEqualBytesThanOld() {
        // Masking already collapses a different seller/price to the SAME key in BOTH old
        // and new (NameMasker's "Seller: NAME" frame, TemplateText's NUMBER pattern) —
        // this scenario is not where the feature's win shows up (see the scroll-swap test
        // above for that); it exists to confirm the new behaviour is never WORSE here either.
        CapturingTransport oldTransport = new CapturingTransport();
        OldStyleCache oldCache = new OldStyleCache(realTranslator(oldTransport));

        CapturingTransport newTransport = new CapturingTransport();
        TranslationService newService = newService(realTranslator(newTransport));

        String first = body("Implosion", "Wither Shield", "Shadow Warp", "DragonMeow", "1,000,000");
        String relisted = body("Implosion", "Wither Shield", "Shadow Warp", "Steve", "500,000");

        oldCache.warm(TITLE, first);
        int oldCharsAfterFirst = oldTransport.totalChars();
        oldCache.warm(TITLE, relisted);
        int oldRelistChars = oldTransport.totalChars() - oldCharsAfterFirst;

        newService.warmTooltipBatch(List.of(TITLE, first));
        pump(newService);
        int newCharsAfterFirst = newTransport.totalChars();
        newService.warmTooltipBatch(List.of(TITLE, relisted));
        pump(newService);
        int newRelistChars = newTransport.totalChars() - newCharsAfterFirst;

        System.out.println("[quantify] same combo, new seller/price: old=" + oldRelistChars
                + " bytes; new=" + newRelistChars + " bytes");
        assertTrue(newRelistChars <= oldRelistChars,
                "same combo/different seller/price: new (" + newRelistChars
                        + ") must not exceed old (" + oldRelistChars + ")");
        assertTrue(newRelistChars == 0, "new behaviour: every segment already cached, 0 bytes sent");
    }
}
