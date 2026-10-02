package com.dragonmeow.nyanlex.translate;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * NeoForge closes the mod class loader before JVM shutdown hooks run. The Codex shutdown hook must
 * therefore not need to load any class for the first time: this closes a private loader and checks
 * that close() still works when preloadForShutdown() ran first.
 */
class CodexShutdownPreloadTest {

    private static Throwable closeAfterLoaderIsGone(boolean preload) throws Exception {
        URL mod = CodexAppServerClient.class.getProtectionDomain().getCodeSource().getLocation();
        URL gson = Gson.class.getProtectionDomain().getCodeSource().getLocation();
        URLClassLoader loader = new URLClassLoader(new URL[] {mod, gson}, ClassLoader.getPlatformClassLoader());
        Class<?> clientClass = loader.loadClass(CodexAppServerClient.class.getName());
        Path home = Path.of("build", "tmp-codex-shutdown-home");
        Object client = clientClass.getConstructor(Path.class, Path.class).newInstance(home, home);
        if (preload) clientClass.getMethod("preloadForShutdown").invoke(null);
        loader.close();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread hook = new Thread(() -> {
            try {
                ((AutoCloseable) client).close();
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "codex-shutdown-test");
        hook.start();
        hook.join();
        return failure.get();
    }

    @Test
    void closeWorksAfterTheLoaderIsClosedWhenPreloaded() throws Exception {
        assertNull(closeAfterLoaderIsGone(true));
    }

    @Test
    void preloadIsHarmlessWhenCalledRepeatedly() {
        CodexAppServerClient.preloadForShutdown();
        CodexAppServerClient.preloadForShutdown();
    }
}
