package top.tangge233.qmc.net;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** {@link StatusNetworks} 注入与解析行为测试（含 enable/port 边界）。 */
class StatusNetworksTest {
    @Test
    void injectsNetworksAndKeepsOriginalFields() {
        String input = "{\"version\":{\"name\":\"1.21.1\",\"protocol\":767},\"players\":{\"max\":10,\"online\":1},\"description\":{\"text\":\"Hello\"}}";
        String out = StatusNetworks.addNetworks(input, 25565, "quic-raw");
        assertTrue(out.contains("\"networks\""), out);
        assertTrue(out.contains("\"quic-raw\""), out);
        assertTrue(out.contains("\"protocol\":\"quic-mc/1\""), out);
        assertTrue(out.contains("\"1.21.1\""), out);
        NetworksAbility n = StatusNetworks.parse(out);
        assertTrue(n.supportsQuicRaw());
        assertEquals(25565, n.quic().port());
        assertEquals(NetworksAbility.PROTOCOL_V1, n.quic().protocol());
    }

    @Test
    void missingNetworksGivesEmpty() {
        assertFalse(StatusNetworks.parse("{\"description\":{\"text\":\"x\"}}").supportsQuicRaw());
    }

    @Test
    void explicitlyDisabledQuicGivesEmpty() {
        String input = "{\"networks\":{\"quic\":{\"enable\":false,\"port\":25565,\"features\":[\"quic-raw\"],\"protocol\":\"quic-mc/1\"}}}";
        assertFalse(StatusNetworks.parse(input).supportsQuicRaw());
        assertTrue(StatusNetworks.parse(input).quicInfo().isEmpty());
    }

    @Test
    void missingPortGivesEmpty() {
        String input = "{\"networks\":{\"quic\":{\"enable\":true,\"features\":[\"quic-raw\"]}}}";
        assertTrue(StatusNetworks.parse(input).quicInfo().isEmpty());
    }

    @Test
    void badJsonGivesEmpty() {
        assertFalse(StatusNetworks.parse("not-json").supportsQuicRaw());
    }
}
