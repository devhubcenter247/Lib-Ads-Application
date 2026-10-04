package com.lib.ads.gma.ads.util

import java.util.concurrent.ConcurrentHashMap

object AdConfigKeyBlocklist {
    private val blocked = ConcurrentHashMap.newKeySet<String>()
    private val observed = ConcurrentHashMap.newKeySet<String>()
    private val logged = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun observeKey(configKey: String?) {
        normalize(configKey)?.let(observed::add)
    }

    @JvmStatic
    fun block(configKey: String?) {
        normalize(configKey)?.let(blocked::add)
    }

    @JvmStatic
    fun unblock(configKey: String?) {
        normalize(configKey)?.let {
            blocked.remove(it)
            logged.remove(it)
        }
    }

    @JvmStatic
    fun isBlocked(configKey: String?): Boolean =
        normalize(configKey)?.let(blocked::contains) == true

    @JvmStatic
    fun logBlockedOnce(configKey: String?, where: String): Boolean {
        val key = normalize(configKey) ?: return false
        if (!blocked.contains(key) || !logged.add("$where:$key")) return false
        AppLogger.w("AdConfigKeyBlocklist", "blocked configKey=$key at $where")
        return true
    }

    @JvmStatic
    fun resetForTest() {
        blocked.clear()
        observed.clear()
        logged.clear()
    }

    private fun normalize(configKey: String?): String? =
        configKey?.trim()?.takeIf { it.isNotEmpty() }
}
