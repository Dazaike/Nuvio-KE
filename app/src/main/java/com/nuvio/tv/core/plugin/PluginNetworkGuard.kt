package com.nuvio.tv.core.plugin

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private const val MAX_FETCH_URL_CHARS = 8192

/**
 * Network rules for third-party plugin code.
 *
 * A scraper's `fetch` may reach the public internet only: it cannot probe the user's router, a NAS,
 * a TorrServer or any other service on the home network, or the device itself. The check runs on the
 * resolved address, so redirects and DNS names that point inward are caught too.
 * Repository downloads follow a different rule: https, or plain http only for a LAN / loopback host
 * (a repository served from the user's own machine).
 */
internal object PluginNetworkGuard {

    /** Throws [IllegalArgumentException] unless [url] is an http(s) URL with a host. */
    fun requireFetchableUrl(url: String) {
        require(url.length <= MAX_FETCH_URL_CHARS) { "URL too long" }
        val parsed = url.trim().toHttpUrlOrNull()
        require(parsed != null) { "Only http(s) URLs can be fetched" }
        require(parsed.host.isNotEmpty()) { "URL has no host" }
    }

    /** Scheme and host only: a URL's path and query can carry tokens, the log never gets them. */
    fun hostForLog(url: String): String =
        url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "<invalid-url>"

    /** Wraps [delegate] so a name that resolves to a non-public address fails like an unknown host. */
    fun guardedDns(delegate: Dns): Dns = Dns { hostname ->
        val addresses = delegate.lookup(hostname)
        if (addresses.any { !isPublicAddress(it) }) {
            throw UnknownHostException("Blocked non-public address for $hostname")
        }
        addresses
    }

    /** True for an address on the public internet. */
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        return when (address) {
            is Inet4Address -> {
                val b = address.address
                val first = b[0].toInt() and 0xff
                val second = b[1].toInt() and 0xff
                // 0.0.0.0/8 "this network", 100.64.0.0/10 carrier-grade NAT, 192.0.0.0/24 protocol assignments,
                // 198.18.0.0/15 benchmarking, 240.0.0.0/4 reserved and broadcast.
                !(first == 0 ||
                    (first == 100 && second in 64..127) ||
                    (first == 192 && second == 0 && (b[2].toInt() and 0xff) == 0) ||
                    (first == 198 && second in 18..19) ||
                    first >= 240)
            }
            is Inet6Address -> {
                val b = address.address
                // fc00::/7 unique local, fec0::/10 deprecated site-local.
                !((b[0].toInt() and 0xfe) == 0xfc || ((b[0].toInt() and 0xff) == 0xfe && (b[1].toInt() and 0xc0) == 0xc0))
            }
            else -> false
        }
    }

    /** Repository and script downloads: https, or http for a host on the user's own network. */
    fun isAllowedRepositoryUrl(url: HttpUrl): Boolean {
        if (url.host.isEmpty()) return false
        if (url.isHttps) return true
        return isLocalHost(url.host)
    }

    private fun isLocalHost(host: String): Boolean {
        val lower = host.lowercase(Locale.ROOT)
        if (lower == "localhost" || lower.endsWith(".local") || lower.endsWith(".lan") || lower.endsWith(".home.arpa")) {
            return true
        }
        // Only an IP literal is judged here; a DNS name is never resolved just to decide this.
        val literal = ipLiteralOrNull(lower) ?: return false
        return !isPublicAddress(literal)
    }

    private fun ipLiteralOrNull(host: String): InetAddress? {
        val v4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(host)
        val v6 = host.startsWith("[") || host.contains(':')
        if (!v4 && !v6) return null
        return runCatching { InetAddress.getByName(host.removePrefix("[").removeSuffix("]")) }.getOrNull()
    }
}
