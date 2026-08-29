import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Enumeration;

/**
 * Starts an inline harness only after proving that every implementation anchor
 * was loaded from the exact release JAR under test.  This class is deliberately
 * Java-8 compatible so the same launcher can verify every release in the matrix.
 */
public final class FinalJarHarnessLauncher {
    private FinalJarHarnessLauncher() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            throw new IllegalArgumentException(
                    "usage: <final-jar> <anchor-classes-separated-by-;> "
                            + "<harness-main> <runtime-dir> [harness-args...]");
        }

        File expectedJar = new File(args[0]).getCanonicalFile();
        check(expectedJar.isFile(), "final JAR does not exist: " + expectedJar);
        check(expectedJar.getName().endsWith(".jar"),
                "final implementation source is not a JAR: " + expectedJar);

        String[] anchors = args[1].split(";", -1);
        check(anchors.length > 0, "no implementation anchors were provided");
        ClassLoader loader = FinalJarHarnessLauncher.class.getClassLoader();
        for (String anchor : anchors) {
            check(anchor.length() > 0, "empty implementation anchor");
            assertLoadedOnlyFromJar(loader, anchor, expectedJar);
        }

        String harnessMain = args[2];
        Class<?> harness = Class.forName(harnessMain, true, loader);
        File harnessSource = codeSourceFile(harness);
        check(!harnessSource.equals(expectedJar),
                "inline harness was unexpectedly packaged in the target JAR: " + harnessMain);

        String[] harnessArgs = new String[args.length - 3];
        System.arraycopy(args, 3, harnessArgs, 0, harnessArgs.length);
        System.out.println("FINAL_JAR_CODE_SOURCE_OK anchors=" + anchors.length
                + " jar=" + expectedJar + " harness=" + harnessSource
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

    private static void assertLoadedOnlyFromJar(ClassLoader loader, String className,
                                                 File expectedJar) throws Exception {
        Class<?> type = Class.forName(className, false, loader);
        File actual = codeSourceFile(type);
        check(actual.equals(expectedJar), className + " loaded from " + actual
                + " instead of final JAR " + expectedJar);

        String resourceName = className.replace('.', '/') + ".class";
        Enumeration<URL> resources = loader.getResources(resourceName);
        int count = 0;
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            count++;
            check("jar".equals(resource.getProtocol()), className
                    + " has a non-JAR class resource on the test classpath: " + resource);
            JarURLConnection connection = (JarURLConnection) resource.openConnection();
            connection.setUseCaches(false);
            File resourceJar = new File(connection.getJarFileURL().toURI()).getCanonicalFile();
            check(resourceJar.equals(expectedJar), className
                    + " is duplicated outside the final JAR: " + resource);
        }
        check(count == 1, className + " has " + count
                + " class resources; expected exactly one from " + expectedJar);
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
