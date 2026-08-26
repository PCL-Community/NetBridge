package top.tangge233.netbridge.transport;

import java.net.InetSocketAddress;

/**
 * 客户端加速连接目标：模式、宣告解析出的传输端点。
 *
 * <p>TCP 降级内建于 QUIC/KCP 模式，无独立开关字段；
 * 端点地址由服务端宣告条目与 ping 目标合成（host 缺省跟随）。
 */
public record TransportTarget(TransportMode mode, InetSocketAddress endpoint) {}
