package top.tangge233.qmc.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TransportDeciderTest {
    private static Networks quicNetworks() {
        return Networks.withQuic(1, "quic-raw");
    }

    @Test
    void tcpOnlyIgnoresQuic() {
        var d = TransportDecider.decide(TransportMode.TCP_ONLY, quicNetworks());
        assertFalse(d.useQuic());
        assertTrue(d.allowTcpFallback());
    }

    @Test
    void quicOnlyRequiresSupport() {
        assertTrue(TransportDecider.decide(TransportMode.QUIC_ONLY, quicNetworks()).useQuic());
        assertFalse(TransportDecider.decide(TransportMode.QUIC_ONLY, Networks.empty()).useQuic());
    }

    @Test
    void fallbackPrefersQuicButAllowsTcp() {
        assertTrue(TransportDecider.decide(TransportMode.QUIC_WITH_TCP_FALLBACK, quicNetworks()).useQuic());
        var noQuic = TransportDecider.decide(TransportMode.QUIC_WITH_TCP_FALLBACK, Networks.empty());
        assertFalse(noQuic.useQuic());
        assertTrue(noQuic.allowTcpFallback());
    }
}
