package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.ProviderInfo
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

object NativeProviders {
    private val staticProviders: List<NativeProvider> = buildList {
        addProvider { NetEaseProvider() }
        addProvider { QQProvider() }
        addProvider { KugouProvider() }
        addProvider { KuwoProvider() }
        addProvider { MiguProvider() }
        addProvider { AppleProvider() }
        addProvider { JooxProvider() }
        addProvider { QianqianProvider() }
        addProvider { BilibiliProvider() }
        addProvider { FivesingProvider() }
        addProvider { JamendoProvider() }
        addProvider { SpotifyProvider() }
        addProvider { YouTubeMusicProvider() }
        addProvider { MHubProvider() }
        addProvider { SodaProvider() }
        addProvider { YuntingProvider() }
    }

    private fun MutableList<NativeProvider>.addProvider(factory: () -> NativeProvider) {
        try {
            add(factory())
        } catch (error: Throwable) {
            System.err.println("NativeProviders: provider init failed")
            error.printStackTrace()
        }
    }

    private val dynamicProviders = CopyOnWriteArrayList<NativeProvider>()

    @Synchronized
    fun register(provider: NativeProvider) {
        require(staticProviders.none { it.info.id == provider.info.id }) { "duplicate native provider: ${provider.info.id}" }
        dynamicProviders.removeAll { it.info.id == provider.info.id }
        dynamicProviders += provider
    }

    fun unregister(id: String) {
        dynamicProviders.removeAll { it.info.id == id }
    }

    fun get(id: String): NativeProvider? = all().firstOrNull { it.info.id == id }
    fun all(): List<NativeProvider> = staticProviders + dynamicProviders
    fun infos(): List<ProviderInfo> = all().map { it.info }

    fun newSessionId(providerId: String): String = "$providerId:${UUID.randomUUID()}"

    fun providerForSession(sessionId: String): NativeProvider? {
        val providerId = sessionId.substringBefore(':', "")
        if (providerId.isBlank() || !sessionId.contains(':')) return null
        return get(providerId)
    }
}
