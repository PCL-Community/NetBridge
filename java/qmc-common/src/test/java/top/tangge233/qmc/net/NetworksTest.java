package top.tangge233.qmc.net;

import static org.junit.jupiter.api.Assertions.*;

import top.tangge233.qmc.jni.QuicNative;
import org.junit.jupiter.api.Test;

class NetworksTest {
    @Test
    void emptyHasNoQuic() {
        assertFalse(Networks.empty().supportsQuicRaw());
    }

    @Test
    void quicRawAdvertised() {
        Networks n = Networks.withQuic(25565, QuicNative.RAW_FEATURE);
        assertTrue(n.supportsQuicRaw());
        assertEquals(25565, n.quic().port());
        assertEquals(Networks.PROTOCOL_V1, n.quic().protocol());
    }

    @Test
    void noFeaturesMeansNoRaw() {
        Networks n = Networks.withQuic(25565);
        assertFalse(n.supportsQuicRaw());
    }

    @Test
    void unknownFeatureIgnored() {
        Networks n = Networks.withQuic(25565, "other-feature");
        assertFalse(n.supportsQuicRaw());
    }
}
