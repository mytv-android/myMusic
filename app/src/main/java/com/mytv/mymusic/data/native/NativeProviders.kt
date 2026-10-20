package com.mytv.mymusic.data.native

import com.mytv.mymusic.data.ProviderInfo
import java.util.UUID

object NativeProviders {
    private val providers: List<NativeProvider> = buildList {
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
        addProvider { SodaProvider() }
    }

    private fun MutableList<NativeProvider>.addProvider(factory: () -> NativeProvider) {
        try {
            add(factory())
        } catch (error: Throwable) {
            System.err.println("NativeProviders: provider init failed")
            error.printStackTrace()
        }
    }

    fun get(id: String): NativeProvider? = providers.firstOrNull { it.info.id == id }
    fun all(): List<NativeProvider> = providers
    fun infos(): List<ProviderInfo> = providers.map { it.info }

    fun newSessionId(providerId: String): String = "$providerId:${UUID.randomUUID()}"

    fun providerForSession(sessionId: String): NativeProvider? {
        val providerId = sessionId.substringBefore(':', "")
        if (providerId.isBlank() || !sessionId.contains(':')) return null
        return get(providerId)
    }
}
