package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class NativeLibraryResolver {

    private static final String NATIVE_PATH_PROPERTY = "netbridge.native.path";
    private static final Pattern SHA256_JSON = Pattern.compile(
            "\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\""
    );

    private NativeLibraryResolver() {
    }

    public static @Nullable String overrideProperty() {
        return System.getProperty(NATIVE_PATH_PROPERTY);
    }

    public static Path extractPackagedLibrary() {
        var resource = nativeResourcePath();
        var url = NativeLibraryResolver.class.getResource("/" + resource);
        if (url == null) {
            throw new IllegalStateException(
                    "native resource not found in classpath: " + resource
            );
        }

        var expectedSha = packagedManifestSha256(platformDir());

        var cacheRoot = Path.of(System.getProperty("user.home"))
                .resolve(".netbridge")
                .resolve("native");

        try {
            Files.createDirectories(cacheRoot);
            var tmp = Files.createTempFile(
                    cacheRoot,
                    "net-bridge-",
                    ".tmp"
            );
            var actualSha = copyAndHash(url, tmp);

            if (expectedSha != null && !expectedSha.equalsIgnoreCase(actualSha)) {
                Files.deleteIfExists(tmp);
                throw new IllegalStateException(
                        "net-bridge native checksum mismatch for %s: expected %s, got %s".formatted(
                                resource,
                                expectedSha,
                                actualSha
                        )
                );
            }

            var target = cacheRoot.resolve(actualSha).resolve(nativeResourceName());
            if (Files.notExists(target)) {
                Files.createDirectories(target.getParent());
                try {
                    Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.deleteIfExists(tmp);
            }
            tmp.toFile().deleteOnExit();
            return target;
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

    private static @Nullable String packagedManifestSha256(String platform) {
        var manifestUrl = NativeLibraryResolver.class.getResource(
                "/native/" + platform + "/manifest.json"
        );
        if (manifestUrl == null) {
            return null;
        }

        try (var in = manifestUrl.openStream()) {
            var json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var matcher = SHA256_JSON.matcher(json);
            return matcher.find()
                    ? matcher.group(1)
                    : null;
        } catch (IOException e) {
            return null;
        }
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

    private static String copyAndHash(
            URL url,
            Path target
    ) throws IOException {
        var digest = newDigest();
        try (
                var in = url.openStream();
                var out = Files.newOutputStream(target)
        ) {
            var buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        var hex = new StringBuilder(64);
        for (var b : digest.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        return hex.toString();
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

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

}
