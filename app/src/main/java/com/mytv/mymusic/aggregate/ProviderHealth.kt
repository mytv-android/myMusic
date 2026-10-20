package com.mytv.mymusic.aggregate

import java.util.concurrent.ConcurrentHashMap

class ProviderHealth(
    private val failureThreshold: Int = 3,
    private val openDurationMs: Long = 60_000,
) {
    private data class State(
        var failures: Int = 0,
        var openUntil: Long = 0,
    )

    private val states = ConcurrentHashMap<String, State>()

    fun canRequest(providerId: String): Boolean {
        val state = states[providerId] ?: return true
        val now = System.currentTimeMillis()
        if (state.openUntil > now) return false
        if (state.openUntil > 0 && state.openUntil <= now) {
            state.failures = 0
            state.openUntil = 0
        }
        return true
    }

    fun success(providerId: String) {
        states[providerId] = State()
    }

    fun failure(providerId: String) {
        val state = states.getOrPut(providerId) { State() }
        state.failures += 1
        if (state.failures >= failureThreshold) {
            state.openUntil = System.currentTimeMillis() + openDurationMs
        }
    }

    fun snapshot(): Map<String, Boolean> = states.mapValues { (_, state) ->
        state.openUntil > System.currentTimeMillis()
    }
}
