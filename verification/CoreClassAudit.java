import com.google.gson.GsonBuilder;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InnerClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/** Read-only class inventory for the core-only release rebuild. Never packaged in a mod JAR. */
public final class CoreClassAudit {
    private static final Pattern DESCRIPTOR_CLASS = Pattern.compile("L([^;<:]+)");
    private CoreClassAudit() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("<jar-or-classes-directory> <report.json>");
        Path input = Path.of(args[0]);
        Map<String, Object> classes = new TreeMap<>();
        if (Files.isDirectory(input)) {
            try (var stream = Files.walk(input)) {
                for (Path path : stream.filter(p -> p.toString().endsWith(".class")).sorted().toList()) {
                    classes.put(input.relativize(path).toString().replace('\\', '/'), audit(Files.readAllBytes(path)));
                }
            }
        } else {
            try (ZipFile zip = new ZipFile(input.toFile())) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.getName().endsWith(".class")) {
                        classes.put(entry.getName(), audit(zip.getInputStream(entry).readAllBytes()));
                    }
                }
            }
        }
        Files.writeString(Path.of(args[1]), new GsonBuilder().setPrettyPrinting().create().toJson(classes),
                StandardCharsets.UTF_8);
    }

    private static Map<String, Object> audit(byte[] data) throws Exception {
        ClassReader reader = new ClassReader(data);
        ClassNode node = new ClassNode();
        reader.accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", node.name);
        result.put("parent", node.superName);
        result.put("interfaces", node.interfaces);
        result.put("access", node.access);
        result.put("major", node.version & 0xffff);
        result.put("sha256", sha256(data));
        Integer innerAccess = null;
        for (InnerClassNode inner : node.innerClasses) {
            if (node.name.equals(inner.name)) innerAccess = inner.access;
        }
        if (innerAccess != null) result.put("inner_access", innerAccess);
        List<Map<String, Object>> fields = new ArrayList<>();
        TreeSet<String> descriptorRefs = new TreeSet<>();
        descriptorRefs(node.signature, descriptorRefs);
        for (FieldNode field : node.fields) {
            Map<String, Object> member = member(field.name, field.desc, field.access);
            if (field.value != null) member.put("constant", field.value);
            fields.add(member);
            descriptorRefs(field.desc, descriptorRefs);
            descriptorRefs(field.signature, descriptorRefs);
        }
        result.put("fields", fields);
        List<Map<String, Object>> methods = new ArrayList<>();
        for (MethodNode method : node.methods) {
            methods.add(member(method.name, method.desc, method.access));
            descriptorRefs(method.desc, descriptorRefs);
            descriptorRefs(method.signature, descriptorRefs);
        }
        result.put("methods", methods);

        // Constant-pool references include method handles used by lambdas/bootstrap methods.
        // This inventory is stricter than merely looking for explicit invoke instructions.
        char[] buffer = new char[reader.getMaxStringLength()];
        TreeSet<String> classRefs = new TreeSet<>();
        TreeMap<String, Map<String, Object>> memberRefs = new TreeMap<>();
        for (int index = 1; index < reader.getItemCount(); index++) {
            int offset = reader.getItem(index);
            if (offset == 0) continue; // second slot of long/double constants
            int tag = reader.readByte(offset - 1);
            if (tag == 7) classRefs.add(reader.readUTF8(offset, buffer));
            if (tag == 12) descriptorRefs(reader.readUTF8(offset + 2, buffer), descriptorRefs);
            if (tag == 16) descriptorRefs(reader.readUTF8(offset, buffer), descriptorRefs);
            if (tag == 9 || tag == 10 || tag == 11) {
                String owner = reader.readClass(offset, buffer);
                int pair = reader.getItem(reader.readUnsignedShort(offset + 2));
                String name = reader.readUTF8(pair, buffer);
                String descriptor = reader.readUTF8(pair + 2, buffer);
                Map<String, Object> ref = new LinkedHashMap<>();
                ref.put("owner", owner);
                ref.put("name", name);
                ref.put("descriptor", descriptor);
                ref.put("kind", tag == 9 ? "fields" : "methods");
                memberRefs.put(tag + ":" + owner + ":" + name + ":" + descriptor, ref);
            }
        }
        result.put("class_refs", classRefs);
        result.put("descriptor_class_refs", descriptorRefs);
        result.put("member_refs", memberRefs.values());

        // Rewriting with a fresh pool canonicalizes pool indices and ldc width/offsets.
        // Debug tables, verifier-frame encoding, and unordered inner/nest inventories
        // do not express source behavior. Actual artifact bytes are never transformed.
        node.innerClasses.sort(Comparator.comparing(inner -> inner.name));
        if (node.nestMembers != null) node.nestMembers.sort(Comparator.naturalOrder());
        ClassWriter normalized = new ClassWriter(0);
        node.accept(normalized);
        result.put("normalized_sha256", sha256(normalized.toByteArray()));
        return result;
    }

    private static void descriptorRefs(String descriptor, Set<String> output) {
        if (descriptor == null) return;
        var matcher = DESCRIPTOR_CLASS.matcher(descriptor);
        while (matcher.find()) output.add(matcher.group(1));
    }

    private static Map<String, Object> member(String name, String descriptor, int access) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("descriptor", descriptor);
        result.put("access", access);
        return result;
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }
}
