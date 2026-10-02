package com.dragonmeow.nyanlex;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the structural rules the Mixin framework enforces when a mixin class is APPLIED (which for
 * a mixin aimed at another mod's class happens lazily, the first time that class is loaded - so an
 * ordinary start-up never notices a violation).
 *
 * <ul>
 *   <li>no non-private static field (unless {@code @Shadow} or compiler-synthetic) - a violation
 *       throws {@code InvalidMixinException: contains non-private static field} and crashes the game;</li>
 *   <li>no non-private static method (unless {@code @Unique} or compiler-synthetic);</li>
 *   <li>a {@code @Pseudo} mixin (aimed at classes of other mods) may only be listed in a mixin config
 *       declared {@code "required": false}, so a failed apply is logged instead of fatal.</li>
 * </ul>
 *
 * <p>The mixin class lists come from the mixin configs registered in {@code fabric.mod.json}; the
 * class files are parsed directly (no ASM, no Minecraft classes needed). Keep this file identical in
 * every module that has tests.</p>
 */
class MixinRulesTest {

    private static final int ACC_PRIVATE = 0x0002;
    private static final int ACC_STATIC = 0x0008;
    private static final int ACC_SYNTHETIC = 0x1000;
    private static final int ACC_INTERFACE = 0x0200;

    private static final Pattern CONFIG_NAME = Pattern.compile("\"([^\"]+\\.mixins\\.json)\"");
    private static final Pattern PACKAGE = Pattern.compile("\"package\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern REQUIRED = Pattern.compile("\"required\"\\s*:\\s*(true|false)");
    private static final Pattern DEFAULT_REQUIRE = Pattern.compile("\"defaultRequire\"\\s*:\\s*(\\d+)");
    private static final Pattern CLASS_ARRAY =
            Pattern.compile("\"(?:mixins|client|server)\"\\s*:\\s*\\[([^\\]]*)]");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    private record Member(String name, int access, Set<String> annotations) {}

    private record ClassFacts(int access, Set<String> annotations, List<Member> fields, List<Member> methods) {}

    private record MixinClass(String config, boolean configRequired, String name, ClassFacts facts) {}

    @Test
    void mixinClassesFollowTheMixinStructuralRules() throws IOException {
        List<MixinClass> mixins = loadAllMixinClasses();
        assertTrue(mixins.size() >= 8, "expected the mixin configs to list the mod's mixin classes, got " + mixins.size());

        List<String> violations = new ArrayList<>();
        for (MixinClass mixin : mixins) {
            ClassFacts facts = mixin.facts();
            boolean isInterface = (facts.access() & ACC_INTERFACE) != 0;
            for (Member field : facts.fields()) {
                boolean isStatic = isInterface || (field.access() & ACC_STATIC) != 0;
                boolean isPrivate = (field.access() & ACC_PRIVATE) != 0;
                boolean synthetic = (field.access() & ACC_SYNTHETIC) != 0;
                if (isStatic && !isPrivate && !synthetic
                        && !field.annotations().contains("Lorg/spongepowered/asm/mixin/Shadow;")) {
                    violations.add(mixin.name() + ": non-private static field " + field.name());
                }
            }
            for (Member method : facts.methods()) {
                boolean isStatic = (method.access() & ACC_STATIC) != 0;
                boolean isPrivate = (method.access() & ACC_PRIVATE) != 0;
                boolean synthetic = (method.access() & ACC_SYNTHETIC) != 0;
                if (isStatic && !isPrivate && !synthetic && !method.name().equals("<clinit>")
                        && !method.annotations().contains("Lorg/spongepowered/asm/mixin/Unique;")) {
                    violations.add(mixin.name() + ": non-private static method " + method.name()
                            + " without @Unique");
                }
            }
        }
        assertTrue(violations.isEmpty(), "Mixin would fail to apply (InvalidMixinException): " + violations);
    }

    @Test
    void pseudoMixinsLiveOnlyInOptionalConfigs() throws IOException {
        List<String> violations = new ArrayList<>();
        int pseudo = 0;
        for (MixinClass mixin : loadAllMixinClasses()) {
            if (mixin.facts().annotations().contains("Lorg/spongepowered/asm/mixin/Pseudo;")) {
                pseudo++;
                if (mixin.configRequired()) {
                    violations.add(mixin.name() + " is @Pseudo but listed in required config " + mixin.config());
                }
            }
        }
        assertTrue(pseudo >= 1, "expected at least one @Pseudo compatibility mixin");
        assertTrue(violations.isEmpty(), "compat mixins must not be able to crash the game: " + violations);
    }

    @Test
    void optionalConfigsDoNotRequireTheirInjectors() throws IOException {
        for (String config : registeredConfigs()) {
            String text = readText("/" + config);
            Matcher required = REQUIRED.matcher(text);
            if (required.find() && required.group(1).equals("false")) {
                Matcher defaultRequire = DEFAULT_REQUIRE.matcher(text);
                assertTrue(defaultRequire.find() && defaultRequire.group(1).equals("0"),
                        config + " is optional and must set injectors.defaultRequire to 0");
            }
        }
    }

    // ------------------------------------------------------------------------------ discovery

    private static Set<String> registeredConfigs() throws IOException {
        Set<String> configs = new java.util.TreeSet<>();
        Matcher matcher = CONFIG_NAME.matcher(readText("/fabric.mod.json"));
        while (matcher.find()) {
            configs.add(matcher.group(1));
        }
        assertFalse(configs.isEmpty(), "fabric.mod.json registers no *.mixins.json");
        return configs;
    }

    private static List<MixinClass> loadAllMixinClasses() throws IOException {
        List<MixinClass> all = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String config : registeredConfigs()) {
            String text = readText("/" + config);
            Matcher pkg = PACKAGE.matcher(text);
            assertTrue(pkg.find(), config + " has no package");
            Matcher required = REQUIRED.matcher(text);
            // Mixin's default for a missing "required" is false; being strict here only ever flags more.
            boolean configRequired = !required.find() || required.group(1).equals("true");
            Matcher arrays = CLASS_ARRAY.matcher(text);
            while (arrays.find()) {
                Matcher names = QUOTED.matcher(arrays.group(1));
                while (names.find()) {
                    String className = pkg.group(1) + "." + names.group(1);
                    assertTrue(seen.add(className), className + " is listed twice");
                    String resource = "/" + className.replace('.', '/') + ".class";
                    try (InputStream in = MixinRulesTest.class.getResourceAsStream(resource)) {
                        assertNotNull(in, config + " lists " + className + " but its class file is missing");
                        all.add(new MixinClass(config, configRequired, className, parse(in)));
                    }
                }
            }
        }
        return all;
    }

    private static String readText(String resource) throws IOException {
        try (InputStream in = MixinRulesTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "missing classpath resource " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ------------------------------------------------------------------- minimal class-file parser

    private static ClassFacts parse(InputStream raw) throws IOException {
        DataInputStream in = new DataInputStream(raw);
        in.readInt();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int poolCount = in.readUnsignedShort();
        String[] utf8 = new String[poolCount];
        for (int i = 1; i < poolCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
                case 5, 6 -> {
                    in.skipNBytes(8);
                    i++;
                }
                case 7, 8, 16, 19, 20 -> in.skipNBytes(2);
                case 15 -> in.skipNBytes(3);
                default -> throw new IOException("unknown constant pool tag " + tag);
            }
        }
        int access = in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        in.skipNBytes(2L * interfaces);
        List<Member> fields = readMembers(in, utf8);
        List<Member> methods = readMembers(in, utf8);
        Set<String> classAnnotations = new HashSet<>();
        readAttributes(in, utf8, classAnnotations);
        return new ClassFacts(access, classAnnotations, fields, methods);
    }

    private static List<Member> readMembers(DataInputStream in, String[] utf8) throws IOException {
        int count = in.readUnsignedShort();
        List<Member> members = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int access = in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            in.readUnsignedShort();
            Set<String> annotations = new HashSet<>();
            readAttributes(in, utf8, annotations);
            members.add(new Member(name, access, annotations));
        }
        return members;
    }

    private static void readAttributes(DataInputStream in, String[] utf8, Set<String> annotations) throws IOException {
        int count = in.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            String name = utf8[in.readUnsignedShort()];
            int length = in.readInt();
            if (name.equals("RuntimeVisibleAnnotations") || name.equals("RuntimeInvisibleAnnotations")) {
                int n = in.readUnsignedShort();
                for (int a = 0; a < n; a++) {
                    annotations.add(readAnnotation(in, utf8));
                }
            } else {
                in.skipNBytes(length);
            }
        }
    }

    private static String readAnnotation(DataInputStream in, String[] utf8) throws IOException {
        String type = utf8[in.readUnsignedShort()];
        int pairs = in.readUnsignedShort();
        for (int p = 0; p < pairs; p++) {
            in.readUnsignedShort();
            skipElementValue(in, utf8);
        }
        return type;
    }

    private static void skipElementValue(DataInputStream in, String[] utf8) throws IOException {
        int tag = in.readUnsignedByte();
        switch (tag) {
            case 'e' -> in.skipNBytes(4);
            case '@' -> readAnnotation(in, utf8);
            case '[' -> {
                int n = in.readUnsignedShort();
                for (int i = 0; i < n; i++) {
                    skipElementValue(in, utf8);
                }
            }
            default -> in.skipNBytes(2);
        }
    }
}
