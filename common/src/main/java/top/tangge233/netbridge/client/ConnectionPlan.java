package top.tangge233.netbridge.client;

import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;

import java.net.InetSocketAddress;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

public record ConnectionPlan(
        InetSocketAddress tcpAddress,
        Optional<NativeAttemptPlan> nativeAttempt
) {

    public static ConnectionPlan tcpOnly(InetSocketAddress tcpAddress) {
        return new ConnectionPlan(tcpAddress, Optional.empty());
    }

    public static ConnectionPlan withNativeAttempt(
            InetSocketAddress tcpAddress,
            NativeAttemptPlan attempt
    ) {
        return new ConnectionPlan(tcpAddress, Optional.of(attempt));
    }

    public record NativeAttemptPlan(
            TransportMode mode,
            InetSocketAddress endpoint,
            @Nullable KcpProfile kcpProfile
    ) {

    }

}
