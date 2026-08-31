package top.tangge233.netbridge.config.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import top.tangge233.netbridge.transport.KcpProfile;

import org.jspecify.annotations.Nullable;

/**
 * 将服务端原始配置解析并绑定到具体网络端口与网卡。
 */
public final class ServerSettingsResolver {

    public static final String PROP_QUIC_PORT = "netbridge.quicPort";
    private static final Logger LOGGER = LoggerFactory.getLogger(ServerSettingsResolver.class);

    private ServerSettingsResolver() {
    }

    public static ResolvedServerSettings resolve(
            ServerSettings settings,
            int mcPort,
            @Nullable String mcBindIp
    ) {
        var quic = resolveTransport(
                "quic",
                settings.quic(),
                mcPort,
                mcBindIp,
                resolveQuicPortOverride(settings.quic().port())
        );
        var kcp = resolveTransport(
                "kcp",
                settings.kcp(),
                mcPort,
                mcBindIp,
                settings.kcp().port()
        );
        return new ResolvedServerSettings(quic, kcp);
    }

    private static ResolvedTransport resolveTransport(
            String name,
            ServerTransportSettings transport,
            int mcPort,
            @Nullable String mcBindIp,
            int targetPortConfig
    ) {
        var resolvedTransport = new ResolvedTransport(
                false,
                -1,
                null,
                null,
                transport.maxConnections(),
                transport.kcpProfile()
        );

        if (!transport.enabled()) {
            LOGGER.info("{} transport disabled by config", name);
            return resolvedTransport;
        }

        var listenPort = applyFollowSemantics(name, targetPortConfig, mcPort);
        if (listenPort < 0 || listenPort > 65535) {
            LOGGER.error(
                    "{} listen port {} out of range (-1/0/1..=65535): transport disabled",
                    name,
                    targetPortConfig
            );
            return resolvedTransport;
        }

        if (listenPort == 0) {
            LOGGER.info(
                    "{} listen port 0: random assignment",
                    name
            );
        } else {
            LOGGER.info(
                    "{} listen port {} (minecraft tcp port {})",
                    name,
                    listenPort,
                    mcPort
            );
        }

        var bind = transport.bindHost() != null && !transport.bindHost().isBlank()
                ? transport.bindHost()
                : (
                        mcBindIp != null && !mcBindIp.isBlank()
                                ? mcBindIp
                                : null
                );

        return new ResolvedTransport(
                true,
                listenPort,
                bind,
                transport.advertisedHost(),
                transport.maxConnections(),
                transport.kcpProfile()
        );
    }

    private static int resolveQuicPortOverride(int configuredPort) {
        var sys = System.getProperty(PROP_QUIC_PORT);
        if (sys != null && !sys.isBlank()) {
            try {
                return Integer.parseInt(sys.trim());
            } catch (NumberFormatException e) {
                LOGGER.warn(
                        "Invalid {} '{}': not a number; using configured value",
                        PROP_QUIC_PORT,
                        sys
                );
            }
        }
        return configuredPort;
    }

    private static int applyFollowSemantics(
            String name,
            int configured,
            int mcPort
    ) {
        if (configured != -1) {
            return configured;
        }

        var target = name.equals("kcp")
                ? mcPort + 1
                : mcPort;
        if (target < 1 || target > 65535) {
            LOGGER.error(
                    "{} listen port -1 cannot follow minecraft tcp port {}: transport disabled",
                    name,
                    mcPort
            );
            return -2;
        }

        LOGGER.info("{} listen port -1: following minecraft tcp port {}", name, target);
        return target;
    }

    public record ResolvedTransport(
            boolean enabled,
            int listenPort,
            @Nullable String bindHost,
            @Nullable String advertisedHost,
            int maxConnections,
            @Nullable KcpProfile kcpProfile
    ) {

    }

    public record ResolvedServerSettings(
            ResolvedTransport quic,
            ResolvedTransport kcp
    ) {

    }

}
