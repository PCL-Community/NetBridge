package top.tangge233.qmc.net;

import static org.junit.jupiter.api.Assertions.*;

import top.tangge233.qmc.jni.QuicNative;
import org.junit.jupiter.api.Test;

class NetworksAbilityTest {
    @Test
    void emptyHasNoQuic() {
        assertFalse(NetworksAbility.empty().supportsQuicRaw());
    }

    @Test
    void quicRawAdvertised() {
        NetworksAbility n = NetworksAbility.withQuic(25565, QuicNative.RAW_FEATURE);
        assertTrue(n.supportsQuicRaw());
        assertEquals(25565, n.quic().port());
        assertEquals(NetworksAbility.PROTOCOL_V1, n.quic().protocol());
    }

    @Test
    void noFeaturesMeansNoRaw() {
        NetworksAbility n = NetworksAbility.withQuic(25565);
        assertFalse(n.supportsQuicRaw());
    }

    @Test
    void unknownFeatureIgnored() {
        NetworksAbility n = NetworksAbility.withQuic(25565, "other-feature");
        assertFalse(n.supportsQuicRaw());
    }
}
