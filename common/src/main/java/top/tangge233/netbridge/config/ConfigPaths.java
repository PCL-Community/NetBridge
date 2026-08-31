package top.tangge233.netbridge.config;

import java.nio.file.Path;

/**
 * NetBridge 配置文件路径解析。
 *
 * @param directory 配置根目录（例如 .minecraft/config/net-bridge）
 */
public record ConfigPaths(
        Path directory
) {

    public Path clientFile() {
        return directory.resolve("client.toml");
    }

    public Path serverFile() {
        return directory.resolve("server.toml");
    }

}
