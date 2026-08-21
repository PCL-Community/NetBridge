package top.tangge233.qmc.jni;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 从打包 jar 中解出原生库并加载（跨平台选择 .so/.dylib/.dll）。
 *
 * 当前仅实现 Linux .so；Windows/macOS 后续补充。
 */
public final class NativeLoader {
    private static boolean loaded;

    private NativeLoader() {}

    /** 确定当前平台原生库文件名。 */
    public static String nativeResourceName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "qmc_native.dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "libqmc_native.dylib";
        }
        return "libqmc_native.so";
    }

    /** 加载打包在 classpath 中的原生库（LD_LIBRARY_PATH 不满足时使用）。 */
    public static synchronized void loadFromClasspath() {
        if (loaded) {
            return;
        }
        String resource = "native/" + nativeResourceName();
        try (InputStream in = NativeLoader.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("native resource not found in classpath: " + resource);
            }
            Path tmp = Files.createTempFile("qmc_native_", nativeResourceName());
            tmp.toFile().deleteOnExit();
            try (OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load qmc native from classpath", e);
        }
    }

    /** 尝试直接从 java.library.path 或系统路径加载（Gradle test 用 -Djava.library.path 时）。 */
    public static synchronized void loadSystem() {
        if (loaded) {
            return;
        }
        System.loadLibrary("qmc_native");
        loaded = true;
    }

    /** 优先 system load，失败后 fallback classpath；测试与运行时通用。 */
    public static synchronized void load() {
        try {
            loadSystem();
        } catch (UnsatisfiedLinkError e) {
            loadFromClasspath();
        }
    }
}
