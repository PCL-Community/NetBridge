package top.tangge233.netbridge.client;

import org.junit.jupiter.api.Test;
import top.tangge233.netbridge.ability.NetworksAbility;
import top.tangge233.netbridge.ability.NetworksEntry;
import top.tangge233.netbridge.config.client.ClientSettings;
import top.tangge233.netbridge.transport.KcpProfile;
import top.tangge233.netbridge.transport.TransportMode;
import top.tangge233.netbridge.transport.TransportTarget;

import java.net.InetSocketAddress;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionPlannerTest {

    private final ConnectionPlanner planner = new ConnectionPlanner();

    @Test
    void tcpModeNeverPlansNative() {
        var settings = new ClientSettings(
                TransportMode.TCP,
                KcpProfile.BALANCE
        );
        var plan = planner.plan(
                addr(25565),
                settings,
                NetworksAbility.empty(),
                Optional.empty(),
                true
        );
        assertTrue(plan.nativeAttempt().isEmpty());
    }

    private static InetSocketAddress addr(int port) {
        return new InetSocketAddress("203.0.113.1", port);
    }

    @Test
    void nativeUnavailablePlansTcpOnly() {
        var settings = new ClientSettings(
                TransportMode.QUIC,
                KcpProfile.BALANCE
        );
        var plan = planner.plan(
                addr(25565),
                settings,
                NetworksAbility.empty(),
                Optional.empty(),
                false
        );
        assertTrue(plan.nativeAttempt().isEmpty());
    }

    @Test
    void unadvertisedServerPlansTcp() {
        var settings = new ClientSettings(
                TransportMode.QUIC,
                KcpProfile.BALANCE
        );
        assertTrue(planner.plan(
                addr(25565),
                settings,
                NetworksAbility.empty(),
                Optional.empty(),
                true
        ).nativeAttempt().isEmpty());

        var wrongProtocol = NetworksAbility.of(new NetworksEntry(
                true,
                null,
                25565,
                "net-bri-quic/99"
        ));
        assertTrue(planner.plan(
                addr(25565),
                settings,
                wrongProtocol,
                Optional.empty(),
                true
        ).nativeAttempt().isEmpty());
    }

    @Test
    void advertisedEndpointFollowsAddressHostWhenMissing() {
        var settings = new ClientSettings(
                TransportMode.QUIC,
                KcpProfile.BALANCE
        );
        var advertised = NetworksAbility.of(new NetworksEntry(
                true,
                null,
                2443,
                "net-bri-quic/1"
        ));
        var plan = planner.plan(
                addr(25565),
                settings,
                advertised,
                Optional.empty(),
                true
        );
        var attempt = plan.nativeAttempt().orElseThrow();
        assertEquals(
                TransportMode.QUIC,
                attempt.mode()
        );
        assertEquals(
                2443,
                attempt.endpoint().getPort()
        );
        assertTrue(attempt.endpoint().getHostString().startsWith("203.0.113."));
    }

    @Test
    void kcpModeUsesKcpEntryOnly() {
        var settings = new ClientSettings(
                TransportMode.KCP,
                KcpProfile.BALANCE
        );
        var advertised = NetworksAbility.of(
                new NetworksEntry(
                        true,
                        null,
                        2443,
                        "net-bri-quic/1"
                ),
                new NetworksEntry(
                        true,
                        null,
                        2444,
                        "net-bri-kcp/1"
                )
        );
        var attempt = planner.plan(
                addr(25565),
                settings,
                advertised,
                Optional.empty(),
                true
        ).nativeAttempt().orElseThrow();
        assertEquals(
                2444,
                attempt.endpoint().getPort()
        );
    }

    @Test
    void recentSuccessTakesPriorityOverAdvertisement() {
        var settings = new ClientSettings(
                TransportMode.QUIC,
                KcpProfile.BALANCE
        );
        var advertised = NetworksAbility.of(new NetworksEntry(
                true,
                null,
                2443,
                "net-bri-quic/1"
        ));
        var recent = Optional.of(new TransportTarget(
                TransportMode.QUIC,
                new InetSocketAddress("1.2.3.4", 9999)
        ));
        var attempt = planner.plan(
                addr(25565),
                settings,
                advertised,
                recent,
                true
        ).nativeAttempt().orElseThrow();
        assertEquals(
                9999,
                attempt.endpoint().getPort()
        );
        assertEquals(
                "1.2.3.4",
                attempt.endpoint().getHostString()
        );
    }

    @Test
    void recentSuccessIgnoredWhenModeDiffers() {
        var settings = new ClientSettings(
                TransportMode.KCP,
                KcpProfile.BALANCE
        );
        var advertised = NetworksAbility.of(new NetworksEntry(
                true,
                null,
                2444,
                "net-bri-kcp/1"
        ));
        var recent = Optional.of(new TransportTarget(
                TransportMode.QUIC,
                new InetSocketAddress("1.2.3.4", 9999)
        ));
        var attempt = planner.plan(
                addr(25565),
                settings,
                advertised,
                recent,
                true
        ).nativeAttempt().orElseThrow();
        assertEquals(
                TransportMode.KCP,
                attempt.mode()
        );
        assertEquals(
                2444,
                attempt.endpoint().getPort()
        );
    }

    @Test
    void kcpAttemptCarriesProfile() {
        var settings = new ClientSettings(
                TransportMode.KCP,
                KcpProfile.AGGRESSIVE
        );
        var advertised = NetworksAbility.of(new NetworksEntry(
                true,
                null,
                2444,
                "net-bri-kcp/1"
        ));
        var attempt = planner.plan(
                addr(25565),
                settings,
                advertised,
                Optional.empty(),
                true
        ).nativeAttempt().orElseThrow();
        assertEquals(
                KcpProfile.AGGRESSIVE,
                attempt.kcpProfile()
        );
    }

    @Test
    void modeParsingAcceptsThreeValuesOnly() {
        assertEquals(
                TransportMode.TCP,
                TransportMode.parse("tcp")
        );
        assertEquals(
                TransportMode.QUIC,
                TransportMode.parse("QUIC")
        );
        assertEquals(
                TransportMode.KCP,
                TransportMode.parse(" kcp ")
        );
        assertNull(TransportMode.parse("quic-fallback"));
        assertNull(TransportMode.parse(null));
    }

    @Test
    void profileParsingWithAlias() {
        assertEquals(
                KcpProfile.BALANCE,
                KcpProfile.parse("balance")
        );
        assertEquals(
                KcpProfile.BALANCE,
                KcpProfile.parse("balanced")
        );
        assertEquals(
                KcpProfile.AGGRESSIVE,
                KcpProfile.parse(" Aggressive ")
        );
        assertNull(KcpProfile.parse("turbo"));
        assertEquals(
                "balance",
                KcpProfile.BALANCE.configValue()
        );
    }

}
