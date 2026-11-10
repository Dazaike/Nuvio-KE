package com.nuvio.tv.core.plugin

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginNetworkGuardTest {
    private fun ip(text: String) = InetAddress.getByName(text)

    @Test
    fun `public addresses pass, private loopback and link-local ones do not`() {
        listOf("8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111").forEach {
            assertTrue(it, PluginNetworkGuard.isPublicAddress(ip(it)))
        }
        listOf(
            "127.0.0.1", "10.0.0.5", "172.16.4.4", "172.31.255.255", "192.168.1.1", "169.254.169.254",
            "0.0.0.0", "100.64.0.1", "198.18.0.1", "224.0.0.1", "255.255.255.255",
            "::1", "fe80::1", "fc00::1", "fd12:3456::1", "::"
        ).forEach {
            assertFalse(it, PluginNetworkGuard.isPublicAddress(ip(it)))
        }
    }

    @Test
    fun `an ipv4-mapped private address is judged by its ipv4 part`() {
        assertFalse(PluginNetworkGuard.isPublicAddress(ip("::ffff:192.168.0.1")))
        assertTrue(PluginNetworkGuard.isPublicAddress(ip("::ffff:8.8.8.8")))
    }

    @Test
    fun `a name that resolves to a private address fails like an unknown host`() {
        val dns = PluginNetworkGuard.guardedDns(Dns { listOf(ip("192.168.1.10")) })
        assertThrows(UnknownHostException::class.java) { dns.lookup("nas.example") }

        val mixed = PluginNetworkGuard.guardedDns(Dns { listOf(ip("8.8.8.8"), ip("10.0.0.1")) })
        assertThrows(UnknownHostException::class.java) { mixed.lookup("rebind.example") }

        val ok = PluginNetworkGuard.guardedDns(Dns { listOf(ip("8.8.8.8")) })
        assertEquals(listOf(ip("8.8.8.8")), ok.lookup("public.example"))
    }

    @Test
    fun `fetch accepts http and https urls only`() {
        PluginNetworkGuard.requireFetchableUrl("https://api.example.com/x?y=1")
        PluginNetworkGuard.requireFetchableUrl("http://api.example.com/x")
        listOf("file:///etc/passwd", "ftp://x.example/a", "javascript:alert(1)", "content://x/y", "", "not a url", "//x.example")
            .forEach { assertThrows(it, IllegalArgumentException::class.java) { PluginNetworkGuard.requireFetchableUrl(it) } }
        assertThrows(IllegalArgumentException::class.java) {
            PluginNetworkGuard.requireFetchableUrl("https://x.example/" + "a".repeat(9000))
        }
    }

    @Test
    fun `the log line carries scheme and host only`() {
        assertEquals("https://api.example.com", PluginNetworkGuard.hostForLog("https://api.example.com/path?token=SECRET"))
        assertEquals("<invalid-url>", PluginNetworkGuard.hostForLog("nonsense"))
    }

    @Test
    fun `repositories need https unless the host is on the users own network`() {
        fun allowed(url: String) = PluginNetworkGuard.isAllowedRepositoryUrl(url.toHttpUrl())
        assertTrue(allowed("https://raw.githubusercontent.com/o/r/main/manifest.json"))
        assertFalse(allowed("http://plugins.example.com/manifest.json"))
        assertFalse(allowed("http://8.8.8.8/manifest.json"))
        assertTrue(allowed("http://192.168.1.20:8080/manifest.json"))
        assertTrue(allowed("http://10.0.0.2/manifest.json"))
        assertTrue(allowed("http://127.0.0.1:3000/manifest.json"))
        assertTrue(allowed("http://localhost:3000/manifest.json"))
        assertTrue(allowed("http://my-pc.local/manifest.json"))
        assertTrue(allowed("http://[::1]:3000/manifest.json"))
        assertFalse(allowed("http://localhost.evil.com/manifest.json"))
    }
}
