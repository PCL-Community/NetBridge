package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class NativeLibraryResolver {

    private NativeLibraryResolver() {
    }

    public static Path extractPackagedLibrary() {
        var resource = nativeResourcePath();
        var url = NativeLibraryResolver.class.getResource("/" + resource);
        if (url == null) {
            throw new IllegalStateException(
                    "native resource not found in classpath: " + resource
            );
        }

        try {
            var tmp = Files.createTempFile("net_bridge_native_", nativeResourceName());
            tmp.toFile().deleteOnExit();
            try (
                    var in = url.openStream();
                    var out = Files.newOutputStream(tmp)
            ) {
                in.transferTo(out);
            }
            return tmp;
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to extract net-bridge native from classpath: " + resource,
                    e
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
