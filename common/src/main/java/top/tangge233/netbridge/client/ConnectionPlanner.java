package top.tangge233.netbridge.client;

import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.config.client.ClientSettings;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.InetSocketAddress;
import java.util.Optional;

public final class ConnectionPlanner {

    public ConnectionPlan plan(
            InetSocketAddress tcpAddress,
            ClientSettings settings,
            NetworksAbility advertised,
            Optional<TransportTarget> recentSuccess,
            boolean nativeAvailable
    ) {
        var mode = settings.mode();
        if (mode == TransportMode.TCP || !nativeAvailable) {
            return ConnectionPlan.tcpOnly(tcpAddress);
        }

        if (recentSuccess.isPresent() && recentSuccess.get().mode() == mode) {
            var target = recentSuccess.get();
            return ConnectionPlan.withNativeAttempt(
                    tcpAddress,
                    new ConnectionPlan.NativeAttemptPlan(
                            target.mode(),
                            target.endpoint(),
                            mode == TransportMode.KCP
                                    ? settings.kcpProfile()
                                    : null
                    )
            );
        }

        var key = mode == TransportMode.QUIC
                ? NetworksAbility.KEY_QUIC
                : NetworksAbility.KEY_KCP;
        var entry = advertised.entry(key);
        if (entry == null || !entry.usable()) {
            return ConnectionPlan.tcpOnly(tcpAddress);
        }

        var host = entry.host() != null
                ? entry.host()
                : tcpAddress.getHostString();
        var endpoint = InetSocketAddress.createUnresolved(host, entry.port());
        return ConnectionPlan.withNativeAttempt(
                tcpAddress,
                new ConnectionPlan.NativeAttemptPlan(
                        mode,
                        endpoint,
                        mode == TransportMode.KCP
                                ? settings.kcpProfile()
                                : KcpProfile.BALANCE
                )
        );
    }

}
