package top.tangge233.netbridge.jni;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import top.tangge233.netbridge.NetBridge;

/**
 * 从打包 jar 中解出原生库并加载（按 {@code native/<os>-<arch>/} 选择
 * .so/.dylib/.dll，覆盖 linux/macos/windows × x86_64/aarch64）。
 */
public final class NativeLoader {
    private static boolean loaded;

    private NativeLoader() {}

    /** 确定当前平台原生库文件名。 */
    public static String nativeResourceName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return "net_bridge_native.dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            return "libnet_bridge_native.dylib";
        }
        return "libnet_bridge_native.so";
    }

    /**
     * 当前平台目录名（{@code <os>-<arch>}，与 Gradle buildCdylib、CI
     * matrix 的 stage 目录一致）：linux/macos/windows + x86_64/aarch64。
     */
    public static String platformDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String osDir = os.contains("win") ? "windows"
                : (os.contains("mac") || os.contains("darwin")) ? "macos" : "linux";
        String arch = System.getProperty("os.arch", "").toLowerCase();
        String archDir = (arch.equals("amd64") || arch.equals("x86_64")) ? "x86_64"
                : (arch.equals("aarch64") || arch.equals("arm64")) ? "aarch64" : arch;
        return osDir + "-" + archDir;
    }

    /** jar 内原生库资源全路径：{@code native/<os>-<arch>/<文件名>}。 */
    public static String nativeResourcePath() {
        return "native/" + platformDir() + "/" + nativeResourceName();
    }

    /** 加载打包在 classpath 中的原生库（LD_LIBRARY_PATH 不满足时使用）。 */
    public static synchronized void loadFromClasspath() {
        if (loaded) {
            return;
        }
        String resource = nativeResourcePath();
        try (InputStream in = NativeLoader.class.getResourceAsStream("/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("native resource not found in classpath: " + resource);
            }
            Path tmp = Files.createTempFile("net_bridge_native_", nativeResourceName());
            tmp.toFile().deleteOnExit();
            try (OutputStream out = Files.newOutputStream(tmp)) {
                in.transferTo(out);
            }
            System.load(tmp.toAbsolutePath().toString());
            loaded = true;
        } catch (IOException e) {
            throw new RuntimeException("Failed to load net-bridge native from classpath", e);
        }
    }

    /** 尝试直接从 java.library.path 或系统路径加载（Gradle test 用 -Djava.library.path 时）。 */
    public static synchronized void loadSystem() {
        if (loaded) {
            return;
        }
        System.loadLibrary("net_bridge_native");
        loaded = true;
    }

    /**
     * 优先 classpath 内置库（打包进 jar 的可信版本），失败回退
     * java.library.path（Gradle test 用 -Djava.library.path）。
     * 先加载 system 会优先命中库路径中被移植的同名文件（二进制种植面），
     * 内置版本不可被外部替换。
     *
     * <p>加载成功后校验 ABI 版本（{@link NativeBridge#EXPECTED_ABI_VERSION}）：
     * 不匹配即记录日志。库不可用或 ABI 不匹配返回 false（调用方降级纯 TCP，
     * 不得让客户端初始化/服务器启动崩溃）。
     *
     * @return 加载成功且 ABI 匹配返回 true；幂等，已成功加载亦返回 true
     */
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
                        "net-bridge native library unavailable: {}", t.toString());
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
        return true;
    }

    /** 注册数据到达回调；失败仅记日志（轮询兜底仍在，不阻断启动）。 */
    private static void registerNotify() {
        try {
            NativeBridge.registerNotifyCallback();
        } catch (Throwable t) {
            NetBridge.LOGGER.warn("net-bridge notify callback registration failed: {}", t.toString());
        }
    }

    /** 校验 native ABI 版本；不匹配即抛出并记录双方版本号。 */
    private static void verifyAbi() {
        String actual;
        try {
            actual = NativeBridge.version();
        } catch (Throwable t) {
            throw new RuntimeException(
                    "net-bridge native library loaded but version query failed: " + t, t);
        }
        if (!NativeBridge.EXPECTED_ABI_VERSION.equals(actual)) {
            throw new IllegalStateException("net-bridge native ABI mismatch: expected "
                    + NativeBridge.EXPECTED_ABI_VERSION + ", found " + actual
                    + "; rebuild or update the net-bridge-native library");
        }
    }
}
