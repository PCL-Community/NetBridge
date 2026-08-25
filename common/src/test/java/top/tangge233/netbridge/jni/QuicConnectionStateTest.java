package top.tangge233.netbridge.jni;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class QuicConnectionStateTest {
    @Test
    void codesMatchNativeConstants() {
        assertEquals(QuicNative.STATE_CONNECTING, QuicConnectionState.CONNECTING.code());
        assertEquals(QuicNative.STATE_CONNECTED, QuicConnectionState.CONNECTED.code());
        assertEquals(QuicNative.STATE_CLOSED, QuicConnectionState.CLOSED.code());
        assertEquals(QuicNative.STATE_FAILED, QuicConnectionState.FAILED.code());
        assertEquals(QuicNative.STATE_UNKNOWN, QuicConnectionState.UNKNOWN.code());
    }

    @Test
    void fromCodeMapsAndFallsBackToUnknown() {
        assertEquals(QuicConnectionState.CONNECTED, QuicConnectionState.fromCode(1));
        assertEquals(QuicConnectionState.UNKNOWN, QuicConnectionState.fromCode(42));
    }
}
