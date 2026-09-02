package top.tangge233.netbridge.nativebridge.internal.ffm;

import top.tangge233.netbridge.jni.NativeLoader;

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

        var libName = NativeLoader.nativeResourceName();
        var platformDir = NativeLoader.platformDir();

        // 尝试常见构建产物路径
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
