package com.xbertz.livecam

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Best-effort local Wi-Fi/LAN IPv4 address, so the on-screen URL points to something
 * reachable from another device on the same network. Prefers a wlan-like interface name,
 * falls back to any private (RFC 1918) address, ignoring loopback/VPN/cellular interfaces.
 */
fun getLocalIpAddress(): String? {
    val candidates = mutableListOf<Pair<String, String>>() // interfaceName to address

    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        for (iface in interfaces.toList()) {
            if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
            for (addr in iface.inetAddresses.toList()) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    candidates += iface.name.lowercase() to (addr.hostAddress ?: continue)
                }
            }
        }
    }

    candidates.firstOrNull { (name, _) -> "wlan" in name || "ap" in name }?.let { return it.second }
    candidates.firstOrNull { (_, ip) -> isPrivateLanAddress(ip) }?.let { return it.second }
    return candidates.firstOrNull()?.second
}

private fun isPrivateLanAddress(ip: String): Boolean {
    val parts = ip.split(".").mapNotNull { it.toIntOrNull() }
    if (parts.size != 4) return false
    val (a, b) = parts[0] to parts[1]
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
}

private fun <T> java.util.Enumeration<T>.toList(): List<T> {
    val list = mutableListOf<T>()
    while (hasMoreElements()) list += nextElement()
    return list
}
