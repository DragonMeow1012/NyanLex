import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Proves that an inline source-phase harness executes compiled main outputs. */
public final class SourceOutputHarnessLauncher {
    private SourceOutputHarnessLauncher() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException(
                    "usage: <class-output-roots> <anchors-separated-by-;> "
                            + "<harness-main> <runtime-dir> [harness-args...]");
        }
        List<File> outputRoots = canonicalRoots(args[0]);
        String[] anchors = args[1].split(";", -1);
        check(anchors.length > 0, "no source implementation anchors were provided");
        ClassLoader loader = SourceOutputHarnessLauncher.class.getClassLoader();
        for (String anchor : anchors) {
            check(anchor.length() > 0, "empty source implementation anchor");
            assertLoadedOnlyFromOutput(loader, anchor, outputRoots);
        }

        Class<?> harness = Class.forName(args[2], true, loader);
        File harnessSource = codeSourceFile(harness);
        check(!isInRoots(harnessSource, outputRoots),
                "inline harness was loaded as project source output: " + harnessSource);

        String[] harnessArgs = new String[args.length - 3];
        System.arraycopy(args, 3, harnessArgs, 0, harnessArgs.length);
        System.out.println("SOURCE_OUTPUT_CODE_SOURCE_OK anchors=" + anchors.length
                + " roots=" + args[0] + " harness=" + harnessSource
                + " java=" + System.getProperty("java.version"));
        Method main = harness.getMethod("main", String[].class);
        try {
            main.invoke(null, new Object[] { harnessArgs });
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw failure;
        }
    }

    private static List<File> canonicalRoots(String value) throws Exception {
        String[] paths = value.split(java.util.regex.Pattern.quote(File.pathSeparator), -1);
        List<File> roots = new ArrayList<File>();
        for (String path : paths) {
            check(path.length() > 0, "empty source class-output root");
            File root = new File(path).getCanonicalFile();
            check(root.isDirectory(), "source class-output root is missing: " + root);
            check(!root.getName().endsWith(".jar"),
                    "source class-output root is a JAR: " + root);
            roots.add(root);
        }
        check(!roots.isEmpty(), "no source class-output roots were provided");
        return roots;
    }

    private static void assertLoadedOnlyFromOutput(ClassLoader loader, String className,
                                                    List<File> roots) throws Exception {
        Class<?> type = Class.forName(className, false, loader);
        File source = codeSourceFile(type);
        check(isInRoots(source, roots), className
                + " loaded outside compiled source outputs: " + source);

        String resourceName = className.replace('.', '/') + ".class";
        Enumeration<URL> resources = loader.getResources(resourceName);
        int count = 0;
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            count++;
            check("file".equals(resource.getProtocol()), className
                    + " has a non-source class resource: " + resource);
            File classFile = new File(resource.toURI()).getCanonicalFile();
            boolean inOutput = false;
            for (File root : roots) {
                if (isSameOrChild(classFile, root)) {
                    inOutput = true;
                    break;
                }
            }
            check(inOutput, className + " is duplicated outside source output: " + resource);
        }
        check(count == 1, className + " has " + count
                + " class resources; expected exactly one compiled source class");
    }

    private static boolean isInRoots(File source, List<File> roots) throws Exception {
        File canonical = source.getCanonicalFile();
        for (File root : roots) {
            if (canonical.equals(root.getCanonicalFile())) return true;
        }
        return false;
    }

    private static boolean isSameOrChild(File candidate, File root) throws Exception {
        File current = candidate.getCanonicalFile();
        File canonicalRoot = root.getCanonicalFile();
        while (current != null) {
            if (current.equals(canonicalRoot)) return true;
            current = current.getParentFile();
        }
        return false;
    }

    private static File codeSourceFile(Class<?> type) throws Exception {
        check(type.getProtectionDomain() != null
                        && type.getProtectionDomain().getCodeSource() != null
                        && type.getProtectionDomain().getCodeSource().getLocation() != null,
                "missing CodeSource for " + type.getName());
        URL location = type.getProtectionDomain().getCodeSource().getLocation();
        URI uri = location.toURI();
        return new File(uri).getCanonicalFile();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
