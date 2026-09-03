package top.tangge233.netbridge.runtime;

import top.tangge233.netbridge.NetBridge;
import top.tangge233.netbridge.nativebridge.NativeTransportBackend;
import top.tangge233.netbridge.nativebridge.internal.ffm.FfmNativeTransportBackend;
import top.tangge233.netbridge.nativebridge.internal.ffm.NativeLibraryResolver;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jspecify.annotations.Nullable;

/**
 * production native backend 单一持有者：只构造 {@link FfmNativeTransportBackend}。
 */
public final class NetBridgeNative {

    private static final String NATIVE_PATH_PROPERTY = "netbridge.native.path";

    private static volatile @Nullable NativeTransportBackend backend;
    private static volatile @Nullable String unavailableReason;
    private static volatile boolean started;

    private NetBridgeNative() {
    }

    public static synchronized boolean ensureStarted() {
        if (started) {
            return backend != null;
        }
        started = true;

        NativeTransportBackend created;
        try {
            created = createBackend();
        } catch (RuntimeException e) {
            unavailableReason = e.getMessage();
            NetBridge.LOGGER.error(
                    "net-bridge native unavailable; accelerated transports disabled: {}",
                    unavailableReason
            );
            return false;
        }

        backend = created;
        NetBridge.LOGGER.info("net-bridge FFM native backend available");
        return true;
    }

    private static NativeTransportBackend createBackend() {
        var libraryPath = resolveLibraryPath();
        return FfmNativeTransportBackend.load(libraryPath, 4);
    }

    private static Path resolveLibraryPath() {
        var prop = System.getProperty(NATIVE_PATH_PROPERTY);
        if (prop != null && !prop.isBlank()) {
            var p = Path.of(prop);
            if (!Files.exists(p)) {
                throw new IllegalStateException(
                        "netbridge.native.path points to missing library: " + p
                );
            }
            return p;
        }

        return NativeLibraryResolver.extractPackagedLibrary();
    }

    public static @Nullable NativeTransportBackend backend() {
        return backend;
    }

    public static boolean available() {
        return backend != null;
    }

    public static synchronized void close() {
        var b = backend;
        backend = null;
        if (b != null) {
            try {
                b.close();
            } catch (RuntimeException e) {
                NetBridge.LOGGER.warn("Error closing native backend: {}", e.getMessage());
            }
        }
    }

}
