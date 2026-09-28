package com.lib.ads.gma.ads.manager

import android.util.LruCache
import com.lib.ads.gma.ads.model.wrapper.ApInterstitialAd
import com.lib.ads.gma.ads.model.wrapper.ApRewardAd
import com.google.android.libraries.ads.mobile.sdk.appopen.AppOpenAd

/**
 * Application-wide, placement-owned cache for full-screen ads.
 *
 * The key is a placement tag, never an ad unit id. This keeps waterfall ids and
 * the decision to show an ad separate, and avoids coupling callers to NextGen's
 * global preloader pool.
 */
internal object FullScreenAdLruCache {
    private const val MAX_TAGS = 32
    private data class CachedAppOpenPreload(val ad: AppOpenAd, val loadedAt: Long)
    private val cache = object : LruCache<String, Any>(MAX_TAGS) {
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Any, newValue: Any?) {
            // Ads are one-shot objects. There is no destroy API for these wrappers;
            // removing the reference is enough to release them from our cache.
        }
    }

    @Synchronized
    fun put(tag: String, ad: Any) {
        require(tag.isNotBlank()) { "Ad tag must not be blank" }
        cache.put(cacheKey(tag, ad), ad)
    }

    @Synchronized
    fun pollInterstitial(tag: String): ApInterstitialAd? = cache.remove(cacheKey(tag, ApInterstitialAd::class.java)) as? ApInterstitialAd

    @Synchronized
    fun containsInterstitial(tag: String): Boolean =
        cache.get(cacheKey(tag, ApInterstitialAd::class.java)) != null

    @Synchronized
    fun pollReward(tag: String): ApRewardAd? = cache.remove(cacheKey(tag, ApRewardAd::class.java)) as? ApRewardAd

    @Synchronized
    fun containsReward(tag: String): Boolean = cache.get(cacheKey(tag, ApRewardAd::class.java)) != null

    @Synchronized
    fun putRewardInterstitial(tag: String, ad: ApRewardAd) {
        require(tag.isNotBlank()) { "Ad tag must not be blank" }
        cache.put(rewardInterstitialKey(tag), ad)
    }

    @Synchronized
    fun pollRewardInterstitial(tag: String): ApRewardAd? =
        cache.remove(rewardInterstitialKey(tag)) as? ApRewardAd

    @Synchronized
    fun containsRewardInterstitial(tag: String): Boolean =
        cache.get(rewardInterstitialKey(tag)) != null

    @Synchronized
    fun pollAppOpen(tag: String): AppOpenAd? = cache.remove(cacheKey(tag, AppOpenAd::class.java)) as? AppOpenAd

    @Synchronized
    fun containsAppOpen(tag: String): Boolean = cache.get(cacheKey(tag, AppOpenAd::class.java)) != null

    @Synchronized
    fun putAppOpenPreload(tag: String, ad: AppOpenAd) {
        require(tag.isNotBlank()) { "Ad tag must not be blank" }
        cache.put(cacheKey(tag, CachedAppOpenPreload::class.java), CachedAppOpenPreload(ad, System.currentTimeMillis()))
    }

    @Synchronized
    fun containsAppOpenPreload(tag: String): Boolean =
        cache.get(cacheKey(tag, CachedAppOpenPreload::class.java)) != null

    @Synchronized
    fun pollFreshAppOpenPreload(tag: String, maxAgeMs: Long): AppOpenAd? {
        val cached = cache.remove(cacheKey(tag, CachedAppOpenPreload::class.java)) as? CachedAppOpenPreload
            ?: return null
        val isFresh = maxAgeMs > 0L && System.currentTimeMillis() - cached.loadedAt < maxAgeMs
        if (!isFresh) {
            cached.ad.destroy()
            return null
        }
        return cached.ad
    }

    @Synchronized
    fun contains(tag: String): Boolean = cache.get(cacheKey(tag, ApInterstitialAd::class.java)) != null ||
        cache.get(cacheKey(tag, ApRewardAd::class.java)) != null ||
        cache.get(rewardInterstitialKey(tag)) != null ||
        cache.get(cacheKey(tag, AppOpenAd::class.java)) != null

    @Synchronized
    fun clear(tag: String) {
        cache.remove(cacheKey(tag, ApInterstitialAd::class.java))
        cache.remove(cacheKey(tag, ApRewardAd::class.java))
        cache.remove(rewardInterstitialKey(tag))
        cache.remove(cacheKey(tag, AppOpenAd::class.java))
        cache.remove(cacheKey(tag, CachedAppOpenPreload::class.java))
    }

    private fun cacheKey(tag: String, ad: Any): String = cacheKey(tag, ad::class.java)
    private fun cacheKey(tag: String, type: Class<*>): String = "${type.name}:$tag"
    private fun rewardInterstitialKey(tag: String): String = "${ApRewardAd::class.java.name}:reward-interstitial:$tag"
}
