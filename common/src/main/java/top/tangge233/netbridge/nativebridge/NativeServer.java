package top.tangge233.netbridge.nativebridge;

public interface NativeServer extends AutoCloseable {

    long id();

    NativeTransportKind transport();

    int localPort();

    void setListener(NativeServerListener listener);

    @Override
    void close();

}
