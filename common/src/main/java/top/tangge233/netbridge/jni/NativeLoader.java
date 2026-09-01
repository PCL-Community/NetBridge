package top.tangge233.netbridge.jni;

import top.tangge233.netbridge.NetBridge;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Locale;

public final class NativeLoader {

    private static boolean loaded;

    private NativeLoader() {
    }

    public static synchronized boolean load() {
        if (loaded) {
            return true;
        }

        try {
            loadFromClasspath();
        } catch (RuntimeException | UnsatisfiedLinkError e) {
            try {
                loadSystem();
            } catch (Throwable t) {
                NetBridge.LOGGER.error(
                        "net-bridge native library unavailable: {}", t.toString()
                );
                return false;
            }
        }

        try {
            verifyAbi();
            registerNotify();
        } catch (RuntimeException e) {
            NetBridge.LOGGER.error("net-bridge native ABI check failed: {}", e.getMessage());
            return false;
        }

        loaded = true;
        return true;
    }

    public static synchronized void loadFromClasspath() {
        if (loaded) {
            return;
        }

        var resource = nativeResourcePath();
        try (var in = NativeLoader.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException(
                        "native resource not found in classpath: " + resource
                );
            }

            var tmp = Files.createTempFile("net_bridge_native_", nativeResourceName());
            tmp.toFile().deleteOnExit();
            try (var out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }

            System.load(tmp.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to load net-bridge native from classpath", e);
        }
    }

    public static synchronized void loadSystem() {
        if (loaded) {
            return;
        }

        System.loadLibrary("net_bridge_native");
    }

    private static void verifyAbi() {
        String actual;
        try {
            actual = NativeBridge.version();
        } catch (Throwable t) {
            throw new RuntimeException(
                    "net-bridge native library loaded but version query failed: " + t, t
            );
        }

        if (!NativeBridge.EXPECTED_ABI_VERSION.equals(actual)) {
            throw new IllegalStateException(
                    "net-bridge native ABI mismatch: expected %s, found %s; rebuild or update the net-bridge-native library".formatted(
                            NativeBridge.EXPECTED_ABI_VERSION,
                            actual
                    )
            );
        }
    }

    private static void registerNotify() {
        try {
            NativeBridge.registerNotifyCallback();
        } catch (Throwable t) {
            NetBridge.LOGGER.warn(
                    "net-bridge notify callback registration failed: {}",
                    t.toString()
            );
        }
    }

    public static String nativeResourcePath() {
        return "native/" + platformDir() + "/" + nativeResourceName();
    }

    public static String nativeResourceName() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

        return switch (os) {
            case String s when s.contains("win") -> "net_bridge_native.dll";
            case String s when s.contains("mac") || s.contains("darwin") ->
                    "libnet_bridge_native.dylib";
            default -> "libnet_bridge_native.so";
        };
    }

    public static String platformDir() {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        var arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);

        var osDir = switch (os) {
            case String s when s.contains("win") -> "windows";
            case String s when s.contains("mac") || s.contains("darwin") -> "macos";
            default -> "linux";
        };

        var archDir = switch (arch) {
            case "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };

        return osDir + "-" + archDir;
    }

}
