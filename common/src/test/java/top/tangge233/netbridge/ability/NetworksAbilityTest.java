package top.tangge233.netbridge.ability;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * wire v2 编解码与能力模型测试：缺 enable=缺省 false、host null=跟随、 未知 protocol=本地禁用、port 恒为具体值。
 */
class NetworksAbilityTest {

    private static final String QUIC = TransportProtocol.QUIC_V1;
    private static final String KCP = TransportProtocol.KCP_V1;

    @Test
    void missingEnableMeansDisabled() {
        var json = JsonParser.parseString("""
                {"networks": {"quic": {"host": null, "port": 25565, "protocol": "%s"}}}
                """.formatted(QUIC)
        );
        var ability = StatusNetworksCodec.parse(json.getAsJsonObject());
        assertNotNull(ability.entry(NetworksAbility.KEY_QUIC), "条目应被解析保留");
        assertFalse(ability.usable(NetworksAbility.KEY_QUIC), "缺 enable 视为 false");
        assertFalse(ability.hasUsableAccelerated());
    }

    @Test
    void explicitFalseEqualsUnadvertised() {
        var json = JsonParser.parseString("""
                {"networks": {"quic": {"enable": false, "port": 25565, "protocol": "%s"}}}
                """.formatted(QUIC)
        );
        assertFalse(StatusNetworksCodec.parse(json.getAsJsonObject()).usable("quic"));
    }

    @Test
    void hostNullMeansFollowServerAddress() {
        var entry = new NetworksEntry(true, null, 25566, KCP);
        assertTrue(entry.usable());
        assertNull(entry.host());
        // 序列化时省略 host 字段。
        assertFalse(entry.toJson(true).has(NetworksEntry.KEY_HOST));
    }

    @Test
    void unknownProtocolDisablesLocally() {
        var entry = new NetworksEntry(
                true,
                "1.2.3.4",
                25565,
                "net-bri-quic/2"
        );
        assertFalse(entry.usable());
    }

    @Test
    void invalidPortDropsEntry() {
        var zero = new NetworksEntry(true, null, 0, QUIC);
        var overflow = new NetworksEntry(true, null, 65536, QUIC);
        assertFalse(zero.usable());
        assertFalse(overflow.usable());
    }

    @Test
    void parseKeepsBothTransportsAndIgnoresUnknown() {
        var json = JsonParser.parseString("""
                {"networks": {
                  "quic": {"enable": true, "host": "1.1.1.1", "port": 25565, "protocol": "%s"},
                  "kcp":  {"enable": true, "host": null,     "port": 25566, "protocol": "%s"},
                  "sctp": {"enable": true, "port": 25567, "protocol": "net-bri-sctp/1"}
                }}
                """
                .formatted(QUIC, KCP)
        );
        var ability = StatusNetworksCodec.parse(json.getAsJsonObject());
        assertTrue(ability.usable("quic"));
        assertTrue(ability.usable("kcp"));
        assertTrue(ability.hasUsableAccelerated());
        assertEquals(2, ability.entries().size(), "未知传输条目应被忽略");
    }

    @Test
    void malformedJsonDegradesToEmpty() {
        assertEquals(NetworksAbility.empty(), StatusNetworksCodec.parse("{not json"));
        assertEquals(NetworksAbility.empty(), StatusNetworksCodec.parse("{}"));
    }

    @Test
    void injectRoundTrip() {
        Map<String, NetworksEntry> entries = new LinkedHashMap<>();
        entries.put(
                "quic",
                new NetworksEntry(true, null, 25565, QUIC)
        );
        entries.put(
                "kcp",
                new NetworksEntry(true, "kcp.example.org", 25566, KCP)
        );
        var injected = StatusNetworksCodec.addNetworks(
                "{\"version\":{\"name\":\"srv\"}}",
                StatusNetworksCodec.buildNetworks(entries)
        );
        var root = JsonParser.parseString(injected).getAsJsonObject();

        assertTrue(root.has("version"), "原字段保持不变");

        var parsed = StatusNetworksCodec.parse(root);
        var quicEntry = parsed.entry("quic");

        assertNotNull(quicEntry);
        assertEquals(25565, quicEntry.port());

        var kcpEntry = parsed.entry("kcp");

        assertNotNull(kcpEntry);
        assertEquals("kcp.example.org", kcpEntry.host());
        assertNull(quicEntry.host(), "wire 上 host 缺省=跟随，不应出现空串");
    }

    @Test
    void injectSkippedWhenNoEntries() {
        var original = "{\"a\":1}";
        assertEquals(
                original,
                StatusNetworksCodec.addNetworks(original, null)
        );
        assertEquals(
                original,
                StatusNetworksCodec.addNetworks(original, new JsonObject())
        );

        // 已含 networks 时不覆盖。
        var withNetworks = "{\"networks\":{},\"a\":1}";
        assertEquals(
                withNetworks,
                StatusNetworksCodec.addNetworks(
                        withNetworks,
                        StatusNetworksCodec.buildNetworks(entries(new NetworksEntry(
                                true,
                                null,
                                1,
                                QUIC
                        )))
                )
        );
    }

    private Map<String, NetworksEntry> entries(NetworksEntry... list) {
        Map<String, NetworksEntry> map = new LinkedHashMap<>();
        Arrays.stream(list).forEach(e -> {
            var proto = e.protocol();
            map.put(
                    proto != null && proto.contains("kcp")
                            ? "kcp"
                            : "quic", e
            );
        });
        return map;
    }

}
