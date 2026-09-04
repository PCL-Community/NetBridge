package top.tangge233.netbridge.nativebridge.internal.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FfmNativeLibrary implements AutoCloseable {

    private final Arena arena;
    private final SymbolLookup lookup;
    private final FfmApiV1 api;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private FfmNativeLibrary(
            Arena arena,
            SymbolLookup lookup,
            FfmApiV1 api
    ) {
        this.arena = arena;
        this.lookup = lookup;
        this.api = api;
    }

    public static FfmNativeLibrary load(Path libraryPath) {
        var arena = Arena.ofShared();
        try {
            var lookup = SymbolLookup.libraryLookup(libraryPath, arena);
            var getApiSymbol = lookup.find("netbridge_get_api")
                    .orElseThrow(() ->
                            new IllegalStateException("Missing required symbol: netbridge_get_api")
                    );

            var linker = Linker.nativeLinker();
            var getApiHandle = linker.downcallHandle(
                    getApiSymbol,
                    FfmApiLayouts.GET_API_DESC
            );

            var tablePtrSegment = arena.allocate(ValueLayout.ADDRESS);
            var status = (int) getApiHandle.invokeExact(1, 0, tablePtrSegment);
            FfmStatus.checkStatus(status, "netbridge_get_api");

            var tableAddr = tablePtrSegment.get(ValueLayout.ADDRESS, 0);
            if (tableAddr.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("netbridge_get_api returned null API table");
            }
            var tableSegment = tableAddr.reinterpret(
                    FfmApiLayouts.API_V1.byteSize(),
                    arena,
                    null
            );
            var api = FfmApiV1.fromSegment(tableSegment);

            return new FfmNativeLibrary(
                    arena,
                    lookup,
                    api
            );
        } catch (Throwable t) {
            arena.close();
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException(
                    "Failed to load net-bridge native library from " + libraryPath,
                    t
            );
        }
    }

    public FfmNativeContext createContext(int workerThreads) {
        ensureOpen();
        try (var localArena = Arena.ofConfined()) {
            var dispatcher = new NativeEventDispatcher();
            var mh = MethodHandles.lookup().bind(
                    dispatcher,
                    "onNativeEvent",
                    MethodType.methodType(
                            void.class,
                            int.class,
                            long.class,
                            long.class,
                            long.class
                    )
            );
            var upcallStub = Linker.nativeLinker().upcallStub(
                    mh,
                    FfmApiLayouts.EVENT_CALLBACK_DESC,
                    arena
            );

            var callbacks = localArena.allocate(FfmApiLayouts.CALLBACKS_V1);
            callbacks.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.CALLBACKS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("struct_size")
                    ),
                    (int) FfmApiLayouts.CALLBACKS_V1.byteSize()
            );
            callbacks.set(
                    ValueLayout.ADDRESS,
                    FfmApiLayouts.CALLBACKS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("on_event")
                    ),
                    upcallStub
            );

            var options = localArena.allocate(FfmApiLayouts.CONTEXT_OPTIONS_V1);
            options.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.CONTEXT_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("struct_size")
                    ),
                    (int) FfmApiLayouts.CONTEXT_OPTIONS_V1.byteSize()
            );
            options.set(
                    ValueLayout.JAVA_INT,
                    FfmApiLayouts.CONTEXT_OPTIONS_V1.byteOffset(
                            MemoryLayout.PathElement.groupElement("worker_threads")
                    ),
                    workerThreads
            );

            var outContext = localArena.allocate(ValueLayout.ADDRESS);
            var status = (int) api.contextCreate().invokeExact(
                    options,
                    callbacks,
                    outContext
            );
            FfmStatus.checkStatus(status, "context_create");

            var ctxAddr = outContext.get(ValueLayout.ADDRESS, 0);
            if (ctxAddr.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("context_create returned null context pointer");
            }
            return new FfmNativeContext(api, ctxAddr, dispatcher);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("createContext failed", t);
        }
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("FfmNativeLibrary is closed");
        }
    }

    public FfmApiV1 api() {
        return api;
    }

    public SymbolLookup lookup() {
        return lookup;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        arena.close();
    }

}
