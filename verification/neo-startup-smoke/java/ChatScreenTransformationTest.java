import java.nio.file.Path;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.startup.StartupArgs;

/** Exercises CLIENT FML/Mixin transformation only, not a full title-screen/gameplay launch. */
public class ChatScreenTransformationTest {
    public static void main(String[] args) throws Exception {
        var startup = new StartupArgs(Path.of("."), true, Dist.CLIENT, false,
                new String[0], Set.of(), List.of(), ChatScreenTransformationTest.class.getClassLoader());
        try (FMLLoader fml = FMLLoader.create(startup)) {
            assertTrue(fml.getDist() == Dist.CLIENT, "FML must run with CLIENT distribution");
            assertTrue(!fml.getLoadingModList().hasErrors(), "FML discovery must succeed");
            ClassLoader loader = fml.getCurrentClassLoader();
            Class<?> chat = Class.forName("net.minecraft.client.gui.screens.ChatScreen", false, loader);
            Class<?> host = Class.forName("com.dragonmeow.nyanlex.translate.ChatComposerPanel$Host", false, loader);
            assertTrue(host.isAssignableFrom(chat), "CLIENT ChatComposerMixin must actually have transformed ChatScreen");
            assertTrue(Arrays.stream(chat.getDeclaredMethods()).anyMatch(m -> m.getName().equals("draft")),
                    "Transformed composer Host methods must be present");
            String expectedJar = Path.of(System.getProperty("nyanlex.smoke.jar")).toRealPath().toString();
            var location = host.getProtectionDomain().getCodeSource().getLocation();
            String loadedFrom = location.toString();
            assertTrue(Path.of(location.toURI()).toRealPath().equals(Path.of(expectedJar)),
                    "Core must load from packaged JAR, not loose classes: " + loadedFrom);
            System.out.println("NYANLEX_CLIENT_TRANSFORM_OK: " + chat.getName() + " loader=" + chat.getClassLoader());
            System.out.println("NYANLEX_PACKAGED_JAR: " + expectedJar);
            System.out.println("NYANLEX_LOADED_FROM: " + loadedFrom);
            System.out.println("NYANLEX_PACKAGED_SHA256: " + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(expectedJar)))));
        }
    }
    private static void assertTrue(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
