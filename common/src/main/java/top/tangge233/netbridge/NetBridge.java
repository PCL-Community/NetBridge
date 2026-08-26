package top.tangge233.netbridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * net-bridge 全局日志入口：所有模块统一使用 {@link #LOGGER}，
 * 便于在 logs/latest.log 中按 "net-bridge" 前缀过滤诊断。
 */
public final class NetBridge {
    public static final Logger LOGGER = LoggerFactory.getLogger("net-bridge");

    private NetBridge() {}
}
