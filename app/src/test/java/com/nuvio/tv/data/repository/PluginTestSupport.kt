package com.nuvio.tv.data.repository

import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.plugin.PluginSnapshot
import io.mockk.coEvery
import io.mockk.mockk

/** A plugin manager with nothing installed: a stream search then involves addons only. */
internal fun pluginManagerWithoutPlugins(): PluginManager = mockk {
    coEvery { snapshot() } returns PluginSnapshot(
        enabledScrapers = emptyList(),
        groupByRepository = false,
        repositories = emptyList()
    )
}
