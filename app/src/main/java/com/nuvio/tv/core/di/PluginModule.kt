package com.nuvio.tv.core.di

import com.nuvio.tv.core.plugin.PluginRepositoryHttp
import com.nuvio.tv.core.plugin.PluginRuntime
import com.nuvio.tv.core.plugin.PluginScriptRunner
import com.nuvio.tv.core.plugin.PluginStore
import com.nuvio.tv.data.local.PluginDataStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Singleton
import okhttp3.OkHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class PluginBindingsModule {
    @Binds
    @Singleton
    abstract fun bindPluginStore(impl: PluginDataStore): PluginStore

    @Binds
    @Singleton
    abstract fun bindPluginScriptRunner(impl: PluginRuntime): PluginScriptRunner
}

@Module
@InstallIn(SingletonComponent::class)
object PluginHttpModule {
    /**
     * Repository manifest and script downloads: TLS-validating (the code is executed, so a
     * man-in-the-middle must not be able to swap it), bounded in time, no cache.
     */
    @Provides
    @Singleton
    @PluginRepositoryHttp
    fun providePluginRepositoryHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .dns(com.nuvio.tv.core.network.IPv4FirstDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .build()
}
