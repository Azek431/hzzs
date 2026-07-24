package top.azek431.hzzs.mcp

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 枚举设备当前可用于展示的 IPv4 地址。
 *
 * 仅供 UI 复制连接串；**不**决定服务绑定地址。绑定由 [top.azek431.hzzs.core.model.McpConfig.bindLocalhostOnly] 控制。
 *
 * [includeLoopback]=true 时列表**最前**固定含 `127.0.0.1`（同机客户端）；其余为非 loopback 私网地址。
 *
 * 排序偏好（同网段电脑最可能连上的在前）：
 * 1. 常见私网 Wi‑Fi / 以太网：`192.168/16`、`10/8`、`172.16–31/12`
 * 2. Tailscale / CGNAT：`100.64/10`
 * 3. 其它
 *
 * 失败时返回空列表（若 includeLoopback 则仍可能只有 127.0.0.1），不抛异常。
 */
fun listLanIpv4Addresses(includeLoopback: Boolean = false): List<String> {
    val lan = runCatching {
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
            .sortedWith(compareBy<String> { lanAddressRank(it) }.thenBy { it })
            .toList()
    }.getOrDefault(emptyList())
    return if (includeLoopback) {
        listOf("127.0.0.1") + lan.filter { it != "127.0.0.1" }
    } else {
        lan
    }
}

/**
 * 越小越优先展示。
 *
 * - 0：`192.168/16`（家用 Wi‑Fi 最常见）
 * - 1：`172.16–31/12`
 * - 2：`10/8`（含部分蜂窝运营商内网；仍可能被电脑直连失败）
 * - 3：`100.64/10`（Tailscale / CGNAT）
 * - 9：其它
 */
internal fun lanAddressRank(host: String): Int {
    val parts = host.split('.')
    if (parts.size != 4) return 9
    val a = parts[0].toIntOrNull() ?: return 9
    val b = parts[1].toIntOrNull() ?: return 9
    return when {
        a == 192 && b == 168 -> 0
        a == 172 && b in 16..31 -> 1
        a == 10 -> 2
        a == 100 && b in 64..127 -> 3
        else -> 9
    }
}
