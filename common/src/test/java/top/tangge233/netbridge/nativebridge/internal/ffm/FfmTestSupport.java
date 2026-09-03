package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public final class FfmTestSupport {

    private FfmTestSupport() {
    }

    public static Path findNativeLibrary() {
        var prop = System.getProperty("netbridge.native.path");
        if (prop != null && !prop.isBlank()) {
            var p = Path.of(prop);
            if (Files.exists(p)) {
                return p.toAbsolutePath();
            }
        }

        var libName = NativeLibraryResolver.nativeResourceName();
        var platformDir = NativeLibraryResolver.platformDir();

        var candidates = new Path[]{
                Path.of("build/native", platformDir, libName),
                Path.of("../build/native", platformDir, libName),
                Path.of("../../build/native", platformDir, libName),
                Path.of("rust/target/debug", libName),
                Path.of("../rust/target/debug", libName),
                Path.of("../../rust/target/debug", libName),
                Path.of("rust/target/release", libName),
                Path.of("../rust/target/release", libName),
                Path.of("../../rust/target/release", libName),
        };

        for (var c : candidates) {
            if (Files.exists(c)) {
                return c.toAbsolutePath();
            }
        }

        throw new IllegalStateException(
                "Could not find native library for tests. Checked: " + Arrays.toString(candidates)
        );
    }

}
