package top.tangge233.netbridge.server;

import top.tangge233.netbridge.nativebridge.NativeConnection;

public interface NativeConnectionAdopter {

    void adopt(NativeConnection connection);

}
