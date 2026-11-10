package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.PluginRepository
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.UserProfile
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.Buffer

/**
 * A preferences store persisted in DataStore's own wire format, written straight to its file. The
 * real DataStore renames a temp file over the target, which Windows refuses; the format, and so what
 * a restart reads back, is the same.
 */
private class FileBackedPreferencesStore(private val file: File) : TestPreferencesDataStore(load(file)) {
    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
        super.updateData(transform).also { prefs ->
            val buffer = Buffer()
            PreferencesSerializer.writeTo(prefs, buffer)
            file.writeBytes(buffer.readByteArray())
        }

    private companion object {
        fun load(file: File): Preferences =
            if (file.isFile && file.length() > 0) {
                runBlocking { PreferencesSerializer.readFrom(Buffer().apply { write(file.readBytes()) }) }
            } else {
                emptyPreferences()
            }
    }
}

/**
 * The plugin store over real DataStore files: what is written survives a "restart" (every store
 * closed, new ones opened on the same files), per profile, with the primary-profile sharing rule.
 */
class PluginDataStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val activeProfileId = MutableStateFlow(1)
    private val profiles = MutableStateFlow(
        listOf(
            UserProfile(id = 1, name = "Primary", avatarColorHex = "#000000"),
            UserProfile(id = 2, name = "Own", avatarColorHex = "#000000"),
            UserProfile(id = 3, name = "Shares", avatarColorHex = "#000000", usesPrimaryPlugins = true)
        )
    )
    private val stores = mutableMapOf<Int, DataStore<Preferences>>()
    private lateinit var filesDir: File

    private fun open(): PluginDataStore {
        filesDir = File(tmp.root, "files").apply { mkdirs() }
        val dir = File(filesDir, "datastore").apply { mkdirs() }
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } answers {
            val profileId = firstArg<Int>()
            synchronized(stores) {
                stores.getOrPut(profileId) {
                    FileBackedPreferencesStore(File(dir, "plugin_settings_p$profileId.preferences_pb"))
                }
            }
        }
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns activeProfileId
        every { profileManager.profiles } returns profiles
        every { profileManager.activeProfile } answers { profiles.value.firstOrNull { it.id == activeProfileId.value } }
        val context = mockk<Context>()
        every { context.filesDir } returns filesDir
        return PluginDataStore(context, factory, profileManager)
    }

    /** Process death: nothing kept in memory, everything read back from the files. */
    private fun restart(): PluginDataStore {
        stores.clear()
        return open()
    }

    private fun repo(id: String = "r1") = PluginRepository(
        id = id, name = "Repo $id", url = "https://$id.example/manifest.json", description = "d",
        enabled = true, lastUpdated = 1234L, scraperCount = 1, version = "1.0"
    )

    private fun scraper(id: String = "r1:s1", repo: String = "r1", enabled: Boolean = true) = ScraperInfo(
        id = id, name = "S", description = "desc", version = "1.0", filename = "s.js",
        supportedTypes = listOf("movie", "tv"), enabled = enabled, manifestEnabled = true,
        logo = "https://l.example/s.png", contentLanguage = listOf("en"), repositoryId = repo, formats = listOf("mp4")
    )

    @Test
    fun `repositories, scrapers, switches, settings and scripts survive a restart`() = runBlocking {
        val first = open()
        first.addRepository(repo())
        first.saveScrapers(listOf(scraper(), scraper(id = "r1:s2", enabled = false)))
        first.setPluginsEnabled(false)
        first.setGroupStreamsByRepository(true)
        first.setThirdPartyCodeAcknowledged(true)
        first.setScraperSettings("r1:s1", mapOf("quality" to "1080"))
        first.saveScraperCode("r1:s1", "module.exports = {};")

        val second = restart()

        assertEquals(listOf(repo()), second.repositories.first())
        assertEquals(listOf(scraper(), scraper(id = "r1:s2", enabled = false)), second.scrapers.first())
        assertFalse(second.pluginsEnabled.first())
        assertTrue(second.groupStreamsByRepository.first())
        assertTrue(second.thirdPartyCodeAcknowledged.first())
        assertEquals(mapOf("quality" to "1080"), second.getScraperSettings("r1:s1"))
        assertEquals("module.exports = {};", second.getScraperCode("r1:s1"))
    }

    @Test
    fun `defaults for a store that was never written`() = runBlocking {
        val store = open()
        assertTrue(store.repositories.first().isEmpty())
        assertTrue(store.scrapers.first().isEmpty())
        assertTrue(store.pluginsEnabled.first())
        assertFalse(store.groupStreamsByRepository.first())
        assertFalse(store.thirdPartyCodeAcknowledged.first())
        assertNull(store.getScraperCode("none"))
        assertTrue(store.getScraperSettings("none").isEmpty())
    }

    @Test
    fun `updating and removing a repository touch only that repository`() = runBlocking {
        val store = open()
        store.addRepository(repo("a"))
        store.addRepository(repo("b"))
        store.updateRepository(repo("a").copy(enabled = false, name = "Renamed"))
        store.updateRepository(repo("missing"))

        assertEquals(listOf("Renamed", "Repo b"), store.repositories.first().map { it.name })
        assertFalse(store.repositories.first().first().enabled)

        store.removeRepository("a")
        assertEquals(listOf("b"), store.repositories.first().map { it.id })
    }

    @Test
    fun `script files are named by a digest of the id, never by the id`() = runBlocking {
        val store = open()
        val hostile = "repo:../../../databases/secret"
        store.saveScraperCode(hostile, "// x")

        val codeDir = File(filesDir, "plugin_code")
        val written = codeDir.listFiles().orEmpty().map { it.name }
        assertEquals(listOf(pluginCodeFileName(hostile)), written)
        assertTrue(written.single().matches(Regex("[0-9a-f]{32}\\.js")))
        assertNotEquals(pluginCodeFileName(hostile), pluginCodeFileName("repo:other"))
        assertEquals("// x", store.getScraperCode(hostile))
        assertTrue(File(filesDir, "databases").listFiles().isNullOrEmpty())

        store.deleteScraperCode(hostile)
        assertNull(store.getScraperCode(hostile))
    }

    @Test
    fun `each profile has its own list and its own script directory`() = runBlocking {
        val store = open()
        store.addRepository(repo("primary"))
        store.saveScraperCode("primary:s", "// primary")

        activeProfileId.value = 2
        assertTrue(store.repositories.first().isEmpty())
        assertNull(store.getScraperCode("primary:s"))
        store.addRepository(repo("own"))
        store.saveScraperCode("own:s", "// own")
        assertTrue(File(filesDir, "plugin_code_p2").isDirectory)

        activeProfileId.value = 1
        assertEquals(listOf("primary"), store.repositories.first().map { it.id })
        assertNull(store.getScraperCode("own:s"))
    }

    @Test
    fun `a profile that uses the primary's plugins reads them and cannot write`() = runBlocking {
        val store = open()
        store.addRepository(repo("primary"))
        store.saveScraperCode("primary:s", "// primary")

        activeProfileId.value = 3
        assertTrue(store.isReadOnlyProfile())
        assertEquals(listOf("primary"), store.repositories.first().map { it.id })
        assertEquals("// primary", store.getScraperCode("primary:s"))

        store.addRepository(repo("intruder"))
        store.setPluginsEnabled(false)
        store.saveScraperCode("x:y", "// no")
        store.resetAll()

        activeProfileId.value = 1
        assertFalse(store.isReadOnlyProfile())
        assertEquals(listOf("primary"), store.repositories.first().map { it.id })
        assertTrue(store.pluginsEnabled.first())
        assertNull(store.getScraperCode("x:y"))
        assertEquals("// primary", store.getScraperCode("primary:s"))
    }

    @Test
    fun `reset removes this profile's plugin data and scripts and leaves other profiles alone`() = runBlocking {
        val store = open()
        store.addRepository(repo("primary"))
        store.saveScrapers(listOf(scraper()))
        store.saveScraperCode("r1:s1", "// a")
        store.setPluginsEnabled(false)
        activeProfileId.value = 2
        store.addRepository(repo("own"))
        store.saveScraperCode("own:s", "// own")

        store.resetAll()
        assertTrue(store.repositories.first().isEmpty())
        assertTrue(store.pluginsEnabled.first())
        assertFalse(File(filesDir, "plugin_code_p2").exists())

        activeProfileId.value = 1
        assertEquals(listOf("primary"), store.repositories.first().map { it.id })
        assertEquals("// a", store.getScraperCode("r1:s1"))
        assertFalse(store.pluginsEnabled.first())
    }

    @Test
    fun `scraper settings are per scraper and an empty map removes them`() = runBlocking {
        val store = open()
        store.setScraperSettings("a", mapOf("k" to "1"))
        store.setScraperSettings("b", mapOf("k" to "2"))
        store.setScraperSettings("a", emptyMap())

        assertTrue(store.getScraperSettings("a").isEmpty())
        assertEquals(mapOf("k" to "2"), store.getScraperSettings("b"))
    }

    @Test
    fun `unreadable stored data reads as empty instead of crashing`() = runBlocking {
        val store = open()
        store.repositories.first() // opens the profile 1 store
        stores.getValue(1).edit {
            it[stringPreferencesKey("repositories")] = "{ this is not json"
            it[stringPreferencesKey("scrapers")] = "[1,2,"
            it[stringPreferencesKey("scraper_settings")] = "nope"
        }

        assertTrue(store.repositories.first().isEmpty())
        assertTrue(store.scrapers.first().isEmpty())
        assertTrue(store.getScraperSettings("a").isEmpty())

        store.addRepository(repo())
        assertEquals(listOf(repo()), store.repositories.first())
    }

    @Test
    fun `plugin profile resolution follows the sharing flag`() {
        assertEquals(1, effectivePluginProfileId(usesPrimaryPlugins = true, activeProfileId = 4))
        assertEquals(4, effectivePluginProfileId(usesPrimaryPlugins = false, activeProfileId = 4))
        assertEquals("plugin_code", pluginCodeDirName(1))
        assertEquals("plugin_code_p4", pluginCodeDirName(4))
    }
}
