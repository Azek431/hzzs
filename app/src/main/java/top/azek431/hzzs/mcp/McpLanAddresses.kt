package top.azek431.hzzs.mcp

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 枚举设备当前可用于展示的 IPv4 局域网地址（不含 loopback / 链路本地）。
 *
 * 仅供 UI 复制连接串；**不**决定服务绑定地址。绑定由 [top.azek431.hzzs.core.model.McpConfig.bindLocalhostOnly] 控制。
 * 失败时返回空列表，不抛异常。
 */
fun listLanIpv4Addresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces()
        ?.toList()
        .orEmpty()
        .asSequence()
        .filter { iface ->
            runCatching { iface.isUp && !iface.isLoopback && !iface.isVirtual }.getOrDefault(false)
        }
        .flatMap { iface ->
            iface.inetAddresses.toList().asSequence()
        }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { addr ->
            val host = addr.hostAddress ?: return@mapNotNull null
            if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) {
                null
            } else {
                host
            }
        }
        .distinct()
        .sorted()
        .toList()
}.getOrDefault(emptyList())
