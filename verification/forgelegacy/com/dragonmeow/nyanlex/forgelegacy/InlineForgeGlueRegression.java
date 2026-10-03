package com.dragonmeow.nyanlex.forgelegacy;

import com.google.gson.Gson;
import net.minecraft.util.text.ChatType;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exhaustive Java-8 inline checks for the two legacy Forge chat queues/configs. */
public final class InlineForgeGlueRegression {
    private static final int ENTRY_COUNT = 8;
    private static long scenarios;

    private static final class Entry {
        final int id;
        Entry(int id) { this.id = id; }
    }

    private static final class Model {
        final List<Entry> received = new ArrayList<Entry>();
        final List<Entry> readyOrder = new ArrayList<Entry>();
        final Set<Entry> queued = identitySet();
        final Set<Entry> ready = identitySet();

        void add(Entry entry) {
            check(queued.add(entry), "model duplicate add");
            received.add(entry);
        }

        void markReady(Entry entry) {
            if (!queued.contains(entry) || !ready.add(entry)) return;
            readyOrder.add(entry);
        }

        List<Entry> drain(boolean ordered) {
            List<Entry> result = new ArrayList<Entry>();
            if (ordered) {
                while (!received.isEmpty() && ready.contains(received.get(0))) {
                    retire(received.remove(0), result);
                }
                return result;
            }
            while (!readyOrder.isEmpty()) {
                Entry entry = readyOrder.remove(0);
                if (!ready.remove(entry) || !queued.remove(entry)) continue;
                removeIdentity(received, entry);
                result.add(entry);
            }
            return result;
        }

        private void retire(Entry entry, List<Entry> result) {
            queued.remove(entry);
            if (ready.remove(entry)) removeIdentity(readyOrder, entry);
            result.add(entry);
        }
    }

    public static void main(String[] args) {
        exhaustiveModeSwitches();
        removalAndClearSemantics();
        configDefaultsAndRoundTrip();
        templateAdmissionBoundaries();
        loaderRichComponentSnapshots();
        loaderHardCapTimeoutAndLateFinality();
        loaderSessionAndProfileBoundaries();
        check(scenarios == 725760L, "wrong scenario count: " + scenarios);
        System.out.println("INLINE_FORGE_GLUE_OK scenarios=" + scenarios
                + " rich=true hardCap=512 timeoutFinal=true sessionProfile=true");
    }

    private static void exhaustiveModeSwitches() {
        Entry[] entries = new Entry[ENTRY_COUNT];
        int[] order = new int[ENTRY_COUNT];
        for (int i = 0; i < ENTRY_COUNT; i++) {
            entries[i] = new Entry(i);
            order[i] = i;
        }
        int permutations = 0;
        do {
            for (int split = 0; split <= ENTRY_COUNT; split++) {
                verify(entries, order, split, true);
                verify(entries, order, split, false);
            }
            permutations++;
        } while (nextPermutation(order));
        check(permutations == 40320, "wrong permutation count: " + permutations);
    }

    private static void verify(Entry[] entries, int[] order, int split, boolean initialOrdered) {
        LegacyChatDeliveryQueue<Entry> actual = new LegacyChatDeliveryQueue<Entry>();
        Model expected = new Model();
        for (Entry entry : entries) {
            actual.addLast(entry);
            expected.add(entry);
        }
        for (int step = 0; step < order.length; step++) {
            Entry completed = entries[order[step]];
            actual.markReady(completed);
            expected.markReady(completed);
            if ((step & 1) == 0) {
                actual.markReady(completed);
                expected.markReady(completed);
            }
            boolean ordered = step < split ? initialOrdered : !initialOrdered;
            List<Entry> actualDrain = actual.drainReady(ordered);
            List<Entry> expectedDrain = expected.drain(ordered);
            checkSameIdentityOrder(actualDrain, expectedDrain, split, initialOrdered, step);
            check(actual.size() == expected.received.size(), "size divergence");
        }
        check(actual.isEmpty() && actual.size() == 0, "actual queue leak");
        check(expected.received.isEmpty() && expected.queued.isEmpty(), "model queue leak");
        scenarios++;
    }

    private static void removalAndClearSemantics() {
        LegacyChatDeliveryQueue<Entry> queue = new LegacyChatDeliveryQueue<Entry>();
        Entry[] entries = new Entry[512];
        for (int i = 0; i < entries.length; i++) {
            entries[i] = new Entry(i);
            queue.addLast(entries[i]);
        }
        check(queue.size() == 512, "size did not expose hard-cap boundary");
        queue.markReady(entries[1]);
        check(queue.removeFirst() == entries[0], "removeFirst lost receive order");
        check(queue.drainReady(true).size() == 1, "removing head did not unlock ready prefix");
        check(queue.size() == 510, "unexpected size after head/prefix retirement");
        queue.clear();
        check(queue.isEmpty() && queue.size() == 0, "clear leaked queue state");
        queue.markReady(entries[2]);
        check(queue.drainReady(false).isEmpty(), "stale completion survived clear");
    }

    private static void configDefaultsAndRoundTrip() {
        Gson gson = new Gson();
        LegacyConfig missingField = LegacyConfig.normalizeLoaded(
                gson.fromJson("{\"enabled\":true}", LegacyConfig.class));
        check(missingField != null && missingField.deliverChatTranslationsInOrder,
                "missing delivery field did not default to ordered");
        LegacyConfig changed = new LegacyConfig();
        changed.deliverChatTranslationsInOrder = false;
        LegacyConfig roundTrip = LegacyConfig.normalizeLoaded(
                gson.fromJson(gson.toJson(changed), LegacyConfig.class));
        check(roundTrip != null && !roundTrip.deliverChatTranslationsInOrder,
                "ready-first config did not round-trip");
    }

    private static void templateAdmissionBoundaries() {
        Object loader = allocate(NyanLexForge.class);
        check(!invokeHasLetters(loader, "x100"), "standalone x100 was admitted for translation");
        check(!invokeHasLetters(loader, "X101"), "standalone X101 was admitted for translation");
        check(!invokeHasLetters(loader, "100"), "standalone number was admitted for translation");
        check(invokeHasLetters(loader, "Reward x100"), "text plus x100 was not admitted");
        check(invokeHasLetters(loader, "Reward x101"), "text plus x101 was not admitted");
        check(invokeHasLetters(loader, "0x1F"), "hex literal was incorrectly normalized away");
        check(invokeHasLetters(loader, "2x2"), "dimension was incorrectly normalized away");
        check(invokeHasLetters(loader, "x100kg"), "glued quantity was incorrectly normalized away");
    }

    private static void loaderRichComponentSnapshots() {
        LoaderFixture fixture = new LoaderFixture();
        ITextComponent original = new TextComponentString("root");
        styleBoolean(componentStyle(original), Boolean.TRUE,
                "setBold", "func_150227_a");
        ITextComponent child = new TextComponentString("child");
        styleBoolean(componentStyle(child), Boolean.TRUE,
                "setItalic", "func_150217_b");
        appendSibling(original, child);
        ITextComponent snapshot = fixture.copy(original);
        check(snapshot != original && componentStyle(snapshot) != componentStyle(original),
                "admission snapshot aliased the incoming rich root/style");
        check(componentSiblings(snapshot).size() == 1
                        && componentSiblings(snapshot).get(0) != child
                        && componentStyle(componentSiblings(snapshot).get(0)) !=
                                componentStyle(child),
                "admission snapshot did not deeply copy rich siblings/styles");
        check(plain(snapshot).equals("rootchild"), "admission snapshot lost rich text");

        Object combinedChat = fixture.pending(1L, snapshot, "rootchild", true,
                1L, fixture.connection, fixture.world, fixture.epoch, fixture.profile);
        fixture.config.showOriginal = false;
        ITextComponent combined = fixture.output(combinedChat, "translated");
        check(combined != snapshot, "rich combined output reused and mutated the stored root");
        check(componentSiblings(snapshot).size() == 1,
                "rich output mutated stored original siblings");
        check(componentSiblings(combined).size() == 3,
                "rich output lost original child/newline/translation"
                + " originalSiblings=" + componentSiblings(snapshot).size()
                + " outputSiblings=" + componentSiblings(combined).size()
                + " plain=" + plain(combined)
                + " api=" + ITextComponent.class.getProtectionDomain().getCodeSource().getLocation());
        check(plain(combined).equals("rootchild\ntranslated"), "rich output text order changed");
        check(componentStyle(combined).equals(componentStyle(snapshot)),
                "rich root style was lost");
        check(componentStyle(componentSiblings(combined).get(0)).equals(
                        componentStyle(componentSiblings(snapshot).get(0))),
                "rich child style was lost");
        ITextComponent translatedSibling = componentSiblings(combined).get(2);
        check(componentStyle(translatedSibling).equals(componentStyle(snapshot)),
                "translated sibling did not inherit the original root style");
        check(componentStyle(translatedSibling) != componentStyle(snapshot),
                "translated sibling aliased the mutable original style");

        Object translatedOnlyChat = fixture.pending(2L, snapshot, "rootchild", false,
                1L, fixture.connection, fixture.world, fixture.epoch, fixture.profile);
        fixture.config.showOriginal = true;
        ITextComponent translatedOnly = fixture.output(translatedOnlyChat, "translated");
        check(plain(translatedOnly).equals("translated"),
                "show-original admission snapshot followed a later config mutation");
        check(fixture.output(combinedChat, null) == snapshot,
                "failed translation did not fall back to the rich original component");
        check(fixture.output(combinedChat, "rootchild") == snapshot,
                "identity translation did not fall back to the rich original component");
    }

    private static void loaderHardCapTimeoutAndLateFinality() {
        LoaderFixture cap = new LoaderFixture();
        Object first = null;
        for (int i = 0; i < 512; i++) {
            Object chat = cap.pending(i + 1L, new TextComponentString("message " + i),
                    "message " + i, true, 10L, cap.connection, cap.world,
                    cap.epoch, cap.profile);
            if (i == 0) first = chat;
            cap.track(i + 1L, chat);
        }
        cap.invoke("makeRoomForPendingChat", new Class<?>[] { minecraftClass() },
                new Object[] { null });
        check(cap.queue.size() == 511 && cap.byId.size() == 511,
                "loader hard cap did not immediately retire exactly one oldest entry");
        check(cap.queue.peekFirst() != first && !cap.byId.containsKey(Long.valueOf(1L)),
                "loader hard cap did not retire the receive-order head");
        check(getBoolean(first, "displayed"), "hard-cap fallback was not terminal");
        cap.queue.markReady(first);
        check(cap.queue.drainReady(false).isEmpty(), "late hard-cap completion resurrected an entry");

        LoaderFixture timeout = new LoaderFixture();
        long limit = getStaticLong(NyanLexForge.class, "CHAT_MAX_WAIT_NANOS");
        Object expired = timeout.pending(1L, new TextComponentString("old"), "old", true,
                100L, timeout.connection, timeout.world, timeout.epoch, timeout.profile);
        Object later = timeout.pending(2L, new TextComponentString("later"), "later", true,
                limit + 200L, timeout.connection, timeout.world, timeout.epoch, timeout.profile);
        timeout.track(1L, expired);
        timeout.track(2L, later);
        timeout.queue.markReady(later);
        timeout.invoke("expireTimedOutChats",
                new Class<?>[] { minecraftClass(), long.class },
                new Object[] { null, Long.valueOf(limit + 150L) });
        check(timeout.queue.size() == 1 && timeout.queue.peekFirst() == later,
                "timeout did not retire only the expired receive-order head");
        check(!timeout.byId.containsKey(Long.valueOf(1L)) && getBoolean(expired, "displayed"),
                "timeout original fallback was not terminal");
        timeout.queue.markReady(expired);
        check(timeout.queue.drainReady(true).equals(Collections.singletonList(later)),
                "timeout did not unlock the ready receive-order prefix or allowed resurrection");
        check(timeout.byId.containsKey(Long.valueOf(2L)),
                "queue-only drain unexpectedly forged a loader retirement");
        timeout.invokeRetire(later, timeout.output(later, "translated"));
        check(timeout.byId.isEmpty() && getBoolean(later, "displayed"),
                "successful completion did not retire the pending map identity");
        timeout.invokeRetire(later, new TextComponentString("late"));
        check(timeout.byId.isEmpty(), "late completion resurrected a displayed entry");

        LoaderFixture disabled = new LoaderFixture();
        Object one = disabled.pending(1L, new TextComponentString("one"), "one", true,
                1L, disabled.connection, disabled.world, disabled.epoch, disabled.profile);
        Object two = disabled.pending(2L, new TextComponentString("two"), "two", true,
                2L, disabled.connection, disabled.world, disabled.epoch, disabled.profile);
        disabled.track(1L, one);
        disabled.track(2L, two);
        disabled.queue.markReady(two);
        disabled.invoke("flushPendingChatOriginals", new Class<?>[] { minecraftClass() },
                new Object[] { null });
        check(disabled.queue.isEmpty() && disabled.byId.isEmpty(),
                "disable flush left pending/history state behind");
        check(getBoolean(one, "displayed") && getBoolean(two, "displayed"),
                "disable flush did not terminally retire all originals");
    }

    private static void loaderSessionAndProfileBoundaries() {
        LoaderFixture session = new LoaderFixture();
        Object chat = session.pending(1L, new TextComponentString("session"), "session", true,
                1L, session.connection, session.world, session.epoch, session.profile);
        session.track(1L, chat);
        session.invoke("syncChatSession", new Class<?>[] { minecraftClass() }, new Object[] { null });
        check(session.queue.isEmpty() && session.byId.isEmpty(),
                "connection/world transition leaked an old-session entry");
        check(getLong(session.loader, "chatSessionEpoch") == session.epoch + 1L,
                "connection/world transition did not advance epoch");
        check(get(session.loader, "chatRequestProfile") == null,
                "connection/world transition retained the old request profile");

        LoaderFixture presentation = new LoaderFixture();
        Object retained = presentation.pending(1L, new TextComponentString("retained"),
                "retained", true, 1L, presentation.connection, presentation.world,
                presentation.epoch, presentation.profile);
        presentation.track(1L, retained);
        presentation.config.showOriginal = !presentation.config.showOriginal;
        presentation.config.deliverChatTranslationsInOrder =
                !presentation.config.deliverChatTranslationsInOrder;
        presentation.invoke("syncChatRequestProfile", new Class<?>[] { minecraftClass() },
                new Object[] { null });
        check(presentation.queue.contains(retained) && presentation.byId.size() == 1,
                "presentation-only mode change invalidated an in-flight request");
        check(getLong(presentation.loader, "chatSessionEpoch") == presentation.epoch,
                "presentation-only mode change advanced request epoch");

        presentation.config.sourceLang = "de";
        presentation.invoke("syncChatRequestProfile", new Class<?>[] { minecraftClass() },
                new Object[] { null });
        check(presentation.queue.isEmpty() && presentation.byId.isEmpty(),
                "semantic request-profile change did not flush old originals");
        check(getLong(presentation.loader, "chatSessionEpoch") == presentation.epoch + 1L,
                "semantic request-profile change did not advance epoch");
        presentation.queue.markReady(retained);
        check(presentation.queue.drainReady(false).isEmpty(),
                "late old-profile completion survived profile invalidation");
    }

    private static final class LoaderFixture {
        final Object loader = allocate(NyanLexForge.class);
        final LegacyChatDeliveryQueue<Object> queue = new LegacyChatDeliveryQueue<Object>();
        final Map<Long, Object> byId = new LinkedHashMap<Long, Object>();
        final LegacyConfig config = new LegacyConfig();
        final Object connection = new Object();
        final Object world = new Object();
        final long epoch = 41L;
        final LegacyChatRequestProfile profile;
        final Constructor<?> pendingConstructor;
        final Method output;

        LoaderFixture() {
            config.enabled = true;
            config.translationRequestsEnabled = true;
            config.followGameLanguage = false;
            config.targetLang = "en";
            profile = LegacyChatRequestProfile.capture(config, config.targetLang);
            set(loader, "pendingChats", queue);
            set(loader, "pendingChatById", byId);
            set(loader, "config", config);
            set(loader, "chatConnection", connection);
            set(loader, "chatWorld", world);
            setLong(loader, "chatSessionEpoch", epoch);
            set(loader, "chatRequestProfile", profile);
            setStatic(NyanLexForge.class, "instance", loader);
            try {
                Class<?> pending = Class.forName(NyanLexForge.class.getName() + "$PendingChat");
                pendingConstructor = pending.getDeclaredConstructor(long.class, ChatType.class,
                        ITextComponent.class, String.class, boolean.class, long.class,
                        Object.class, Object.class, long.class, LegacyChatRequestProfile.class);
                pendingConstructor.setAccessible(true);
                output = NyanLexForge.class.getDeclaredMethod(
                        "translatedChatMessage", pending, String.class);
                output.setAccessible(true);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        Object pending(long id, ITextComponent original, String source, boolean showOriginal,
                       long queuedAtNanos, Object connection, Object world, long epoch,
                       LegacyChatRequestProfile profile) {
            try {
                return pendingConstructor.newInstance(Long.valueOf(id), ChatType.CHAT, original,
                        source, Boolean.valueOf(showOriginal), Long.valueOf(queuedAtNanos),
                        connection, world, Long.valueOf(epoch), profile);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void track(long id, Object chat) {
            queue.addLast(chat);
            byId.put(Long.valueOf(id), chat);
        }

        ITextComponent output(Object chat, String translated) {
            try {
                return (ITextComponent) output.invoke(loader, chat, translated);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        ITextComponent copy(ITextComponent source) {
            try {
                Method method = NyanLexForge.class.getDeclaredMethod(
                        "copyComponent", ITextComponent.class);
                method.setAccessible(true);
                return (ITextComponent) method.invoke(null, source);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }

        void invokeRetire(Object chat, ITextComponent message) {
            invoke("retireAndDeliver",
                    new Class<?>[] { minecraftClass(), chat.getClass(), ITextComponent.class },
                    new Object[] { null, chat, message });
        }

        Object invoke(String name, Class<?>[] types, Object[] values) {
            try {
                Method method = NyanLexForge.class.getDeclaredMethod(name, types);
                method.setAccessible(true);
                return method.invoke(loader, values);
            } catch (Exception failure) {
                throw new AssertionError(name, failure);
            }
        }
    }

    private static boolean invokeHasLetters(Object loader, String text) {
        try {
            Method method = NyanLexForge.class.getDeclaredMethod("hasLetters", String.class);
            method.setAccessible(true);
            return ((Boolean) method.invoke(loader, text)).booleanValue();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object allocate(Class<?> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return ((Unsafe) field.get(null)).allocateInstance(type);
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static Class<?> minecraftClass() {
        try {
            return Class.forName("net.minecraft.client.Minecraft");
        } catch (ClassNotFoundException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String plain(ITextComponent component) {
        for (String methodName : new String[] {
                "getString", "getUnformattedText", "func_150260_c" }) {
            try {
                return (String) component.getClass().getMethod(methodName).invoke(component);
            } catch (NoSuchMethodException ignored) {
                // Try the mapped name used by the other Forge generation.
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
        throw new AssertionError("No MCP/SRG plain-text component method");
    }

    private static Object componentStyle(ITextComponent component) {
        return invokeMinecraftMethod(component, new String[] {
                "getStyle", "func_150256_b" }, new Class<?>[0], new Object[0]);
    }

    @SuppressWarnings("unchecked")
    private static List<ITextComponent> componentSiblings(ITextComponent component) {
        return (List<ITextComponent>) invokeMinecraftMethod(component, new String[] {
                "getSiblings", "func_150253_a" }, new Class<?>[0], new Object[0]);
    }

    private static void appendSibling(ITextComponent component, ITextComponent sibling) {
        invokeMinecraftMethod(component, new String[] {
                "appendSibling", "func_150257_a" },
                new Class<?>[] { ITextComponent.class }, new Object[] { sibling });
    }

    private static void styleBoolean(Object style, Boolean value, String mcp, String srg) {
        invokeMinecraftMethod(style, new String[] { mcp, srg },
                new Class<?>[] { Boolean.class }, new Object[] { value });
    }

    private static Object invokeMinecraftMethod(Object target, String[] names,
                                                 Class<?>[] types, Object[] arguments) {
        for (String name : names) {
            try {
                return target.getClass().getMethod(name, types).invoke(target, arguments);
            } catch (NoSuchMethodException ignored) {
                // Source verification uses MCP names; final Forge JARs use SRG names.
            } catch (Exception failure) {
                throw new AssertionError(name, failure);
            }
        }
        throw new AssertionError("No MCP/SRG method " + java.util.Arrays.toString(names)
                + " on " + target.getClass().getName());
    }

    private static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static long getLong(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getLong(target);
        } catch (Exception failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static boolean getBoolean(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(target);
        } catch (Exception failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static long getStaticLong(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.getLong(null);
        } catch (Exception failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static void setLong(Object target, String name, long value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setLong(target, value);
        } catch (Exception failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static void setStatic(Class<?> type, String name, Object value) {
        try {
            Field field = type.getDeclaredField(name);
            field.setAccessible(true);
            field.set(null, value);
        } catch (Exception failure) {
            throw new AssertionError(name, failure);
        }
    }

    private static void checkSameIdentityOrder(List<Entry> actual, List<Entry> expected,
                                               int split, boolean initialOrdered, int step) {
        check(actual.size() == expected.size(), "drain size mismatch at split=" + split
                + " initialOrdered=" + initialOrdered + " step=" + step);
        for (int i = 0; i < actual.size(); i++) {
            check(actual.get(i) == expected.get(i), "drain order mismatch at split=" + split
                    + " initialOrdered=" + initialOrdered + " step=" + step + " index=" + i);
        }
    }

    private static boolean nextPermutation(int[] values) {
        int pivot = values.length - 2;
        while (pivot >= 0 && values[pivot] >= values[pivot + 1]) pivot--;
        if (pivot < 0) return false;
        int successor = values.length - 1;
        while (values[successor] <= values[pivot]) successor--;
        int swap = values[pivot];
        values[pivot] = values[successor];
        values[successor] = swap;
        for (int left = pivot + 1, right = values.length - 1; left < right; left++, right--) {
            swap = values[left];
            values[left] = values[right];
            values[right] = swap;
        }
        return true;
    }

    private static <T> void removeIdentity(List<T> values, T target) {
        Iterator<T> iterator = values.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() != target) continue;
            iterator.remove();
            return;
        }
    }

    private static <T> Set<T> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<T, Boolean>());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
