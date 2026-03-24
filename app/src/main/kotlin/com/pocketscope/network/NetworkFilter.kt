package com.pocketscope.network

import java.net.InetAddress

/**
 * Utility for classifying IP addresses and extracting IPs from socket address strings.
 *
 * Uses [InetAddress] for RFC 1918 / loopback detection rather than regex,
 * which is more robust and handles edge cases correctly.
 */
object NetworkFilter {

    /**
     * Extracts a bare IP address from a Ktor remote-address string.
     *
     * Ktor formats addresses as "/192.168.1.5:54321". This strips the leading
     * slash and trailing port to return just the IP.
     */
    fun extractIp(rawAddress: String): String =
        rawAddress.substringAfter("/").substringBefore(":")

    /**
     * Returns `true` if [ipStr] is a private (RFC 1918) or loopback address.
     *
     * Private ranges: 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16.
     * Loopback: 127.0.0.0/8.
     */
    fun isPrivateIp(ipStr: String): Boolean = try {
        val address = InetAddress.getByName(ipStr)
        address.isSiteLocalAddress || address.isLoopbackAddress
    } catch (_: Exception) {
        false
    }
}
