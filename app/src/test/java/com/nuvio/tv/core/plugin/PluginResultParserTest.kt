package com.nuvio.tv.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginResultParserTest {
    @Test
    fun `a full result keeps its facts`() {
        val parsed = PluginResultParser.parse(
            """[{"name":"Server 1","title":"Movie (2020)","url":"https://cdn.example/v.m3u8","quality":"1080p",
               "size":"2.1 GB","language":"en","seeders":12,"peers":3,"infoHash":"abc123",
               "headers":{"Referer":"https://site.example/","User-Agent":"UA"},
               "subtitles":[{"url":"https://subs.example/en.vtt","language":"en","name":"Plugin subs"}]}]"""
        ).single()

        assertEquals("Movie (2020)", parsed.title)
        assertEquals("Server 1", parsed.name)
        assertEquals("https://cdn.example/v.m3u8", parsed.url)
        assertEquals("1080p", parsed.quality)
        assertEquals("2.1 GB", parsed.size)
        assertEquals(12, parsed.seeders)
        assertEquals(mapOf("Referer" to "https://site.example/", "User-Agent" to "UA"), parsed.headers)
        assertEquals("https://subs.example/en.vtt", parsed.subtitles.single().url)
        assertEquals("en", parsed.subtitles.single().lang)
    }

    @Test
    fun `the url may be an object, and the title falls back to the name`() {
        val parsed = PluginResultParser.parse("""[{"name":"Only name","url":{"url":"https://cdn.example/a.mp4"}}]""").single()
        assertEquals("https://cdn.example/a.mp4", parsed.url)
        assertEquals("Only name", parsed.title)
    }

    @Test
    fun `urls the player cannot or must not open are dropped`() {
        val urls = listOf(
            "file:///data/data/com.nuvio.tv.ke/databases/x", "content://media/external/1", "javascript:alert(1)",
            "intent://x#Intent;end", "ftp://x.example/a.mp4", "", "[object Object]", "https://x.example/\nInjected: 1"
        )
        urls.forEach { url ->
            val json = "[{\"title\":\"t\",\"url\":${kotlinx.serialization.json.JsonPrimitive(url)}}]"
            assertTrue("should drop: $url", PluginResultParser.parse(json).isEmpty())
        }
        assertEquals(
            listOf("https://a.example/v", "http://a.example/v", "magnet:?xt=urn:btih:abc"),
            PluginResultParser.parse(
                """[{"url":"https://a.example/v"},{"url":"http://a.example/v"},{"url":"magnet:?xt=urn:btih:abc"}]"""
            ).map { it.url }
        )
    }

    @Test
    fun `header names and values that could split a request are removed`() {
        val parsed = PluginResultParser.parse(
            """[{"url":"https://a.example/v","headers":{"Good":"ok","Bad Name":"x","X-Evil":"a\r\nInjected: 1","":"empty","Num":5}}]"""
        ).single()
        assertEquals(mapOf("Good" to "ok"), parsed.headers)
    }

    @Test
    fun `non-http subtitles are dropped`() {
        val parsed = PluginResultParser.parse(
            """[{"url":"https://a.example/v","subtitles":[{"url":"file:///x.srt"},{"url":"https://s.example/a.srt","lang":"fr"}]}]"""
        ).single()
        assertEquals(listOf("https://s.example/a.srt"), parsed.subtitles.map { it.url })
        assertEquals("fr", parsed.subtitles.single().lang)
    }

    @Test
    fun `garbage in gives an empty list, not an exception`() {
        listOf("", "null", "{}", "[1,2,3]", "[null]", "not json", "[{\"url\":", "\"string\"").forEach {
            assertTrue(it, PluginResultParser.parse(it).isEmpty())
        }
    }

    @Test
    fun `fields are bounded and the number of results is capped`() {
        val long = "x".repeat(10_000)
        val parsed = PluginResultParser.parse("""[{"url":"https://a.example/v","title":"$long","quality":"$long"}]""").single()
        assertEquals(2048, parsed.title.length)
        assertEquals(2048, parsed.quality!!.length)

        val many = (1..500).joinToString(",", "[", "]") { """{"url":"https://a.example/$it"}""" }
        assertEquals(MAX_RESULTS_PER_SCRAPER, PluginResultParser.parse(many).size)

        val tooLongUrl = "https://a.example/" + "a".repeat(9000)
        assertTrue(PluginResultParser.parse("""[{"url":"$tooLongUrl"}]""").isEmpty())
        assertNull(PluginResultParser.parse("""[{"url":"https://a.example/v","infoHash":"${"f".repeat(100)}"}]""").single().infoHash)
    }
}
