package top.tangge233.qmc.net;

/**
 * 由 mixin 注入到 {@code ClientboundStatusResponsePacket} 的扩展接口：
 * 解码时从原始 JSON 捕获的 networks 能力（ADR-0002）。
 */
public interface QuicStatusPacket {
    /** 服务器列表 Ping 响应中解析出的传输能力；缺失时为空 Networks。 */
    Networks qmcNetworks();
}
