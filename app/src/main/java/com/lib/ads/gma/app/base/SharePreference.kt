package com.lib.ads.gma.app.base

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.edit
import androidx.lifecycle.LiveData
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import java.lang.reflect.Type
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import androidx.compose.runtime.State as ComposeState

@Composable
fun <T : Any> collectSharePreferenceWithLifecycle(
    default: T,
    preference: () -> SharedPreference.ObservablePreference<T>,
): ComposeState<T> {
    if (LocalInspectionMode.current) {
        return remember { mutableStateOf(default) }
    }
    val pref = remember { preference() }
    return pref.flow.collectAsStateWithLifecycle(initialValue = pref.value)
}

@Composable
fun <T : Any> collectSharePreference(
    default: T,
    preference: () -> SharedPreference.ObservablePreference<T>,
): ComposeState<T> {
    if (LocalInspectionMode.current) {
        return remember { mutableStateOf(default) }
    }
    val pref = remember { preference() }
    return pref.flow.collectAsState(pref.value)
}


class SharedPreference(
    context: Context,
    @PublishedApi internal val gson: Gson = Gson(),
) {
    companion object {
        @Volatile
        private var instance: SharedPreference? = null
        operator fun invoke(context: Context) = instance ?: synchronized(this) {
            instance ?: SharedPreference(context).also { instance = it }
        }
    }
    @PublishedApi
    internal val prefs: SharedPreferences =
        context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)

    @PublishedApi
    internal val cache = ConcurrentHashMap<String, Any>()

    private val subscribers = ConcurrentHashMap<String, CopyOnWriteArraySet<() -> Unit>>()

    private var listenerRegistered = false
    private val masterListener = OnSharedPreferenceChangeListener { _, changedKey ->
        if (changedKey != null) {
            subscribers[changedKey]?.forEach { it() }
        } else {
            subscribers.values.forEach { set -> set.forEach { it() } }
        }
    }

    private fun subscribe(key: String, callback: () -> Unit): () -> Unit {
        subscribers.getOrPut(key) { CopyOnWriteArraySet() }.add(callback)
        ensureRegistered()
        return {
            subscribers[key]?.let { set ->
                set.remove(callback)
                if (set.isEmpty()) subscribers.remove(key, set)
            }
            maybeUnregister()
        }
    }

    @Synchronized
    private fun ensureRegistered() {
        if (!listenerRegistered) {
            prefs.registerOnSharedPreferenceChangeListener(masterListener)
            listenerRegistered = true
        }
    }

    @Synchronized
    private fun maybeUnregister() {
        if (listenerRegistered && subscribers.isEmpty()) {
            prefs.unregisterOnSharedPreferenceChangeListener(masterListener)
            listenerRegistered = false
        }
    }

    fun clear() {
        prefs.edit { clear() }
        cache.clear()
    }

    fun remove(key: String) {
        prefs.edit { remove(key) }
        cache.remove(key)
    }

    fun contains(key: String): Boolean = prefs.contains(key)

    fun boolean(key: String, default: Boolean = false) =
        delegate({ prefs.getBoolean(key, default) }, { prefs.edit { putBoolean(key, it) } })

    fun string(key: String, default: String = "") =
        delegate(
            { prefs.getString(key, default) ?: default },
            { prefs.edit { putString(key, it) } })

    fun int(key: String, default: Int = 0) =
        delegate({ prefs.getInt(key, default) }, { prefs.edit { putInt(key, it) } })

    fun long(key: String, default: Long = 0L) =
        delegate({ prefs.getLong(key, default) }, { prefs.edit { putLong(key, it) } })

    fun float(key: String, default: Float = 0f) =
        delegate({ prefs.getFloat(key, default) }, { prefs.edit { putFloat(key, it) } })

    inline fun <reified T : Enum<T>> enum(key: String, default: T) = delegate(
        get = {
            prefs.getString(key, null)
                ?.let { runCatching { enumValueOf<T>(it) }.getOrNull() }
                ?: default
        },
        set = { prefs.edit { putString(key, it.name) } }
    )

    inline fun <reified T : Any> json(key: String, default: T): ReadWriteProperty<Any?, T> {
        val type = object : TypeToken<T>() {}.type
        return delegate({ readRuntime(key, default, type) }, { writeRuntime(key, it) })
    }

    inline fun <reified T : Any> value(key: String, default: T): ReadWriteProperty<Any?, T> {
        val type = object : TypeToken<T>() {}.type
        return delegate({ readRuntime(key, default, type) }, { writeRuntime(key, it) })
    }

    inline fun <reified T : Any> get(key: String, default: T): T =
        readRuntime(key, default, object : TypeToken<T>() {}.type)

    inline fun <reified T : Any> set(key: String, value: T) = writeRuntime(key, value)

    inline fun <reified T : Any> observable(key: String, default: T): ObservablePreference<T> =
        ObservablePreference(key, default, object : TypeToken<T>() {}.type)

    @Suppress("UNCHECKED_CAST")
    @PublishedApi
    internal fun <T : Any> readRuntime(key: String, default: T, type: Type? = null): T {
        return when (default) {
            is Boolean -> runCatching { prefs.getBoolean(key, default) as? T }.getOrNull()
                ?: default

            is String -> runCatching { (prefs.getString(key, default) ?: default) as T }.getOrNull()
                ?: default

            is Int -> runCatching { prefs.getInt(key, default) as? T }.getOrNull() ?: default
            is Long -> runCatching { prefs.getLong(key, default) as? T }.getOrNull() ?: default
            is Float -> runCatching { prefs.getFloat(key, default) as? T }.getOrNull() ?: default
            is Double -> runCatching {
                (prefs.getString(key, null)?.toDoubleOrNull() ?: default) as? T
            }.getOrNull() ?: default

            else -> {
                cache[key]?.let { return it as T }
                val json = prefs.getString(key, null) ?: return default
                val t: Type = type ?: default::class.java
                val parsed = runCatching { gson.fromJson<T>(json, t) }.getOrNull() ?: return default
                cache[key] = parsed
                parsed
            }
        }
    }

    @PublishedApi
    internal fun writeRuntime(key: String, value: Any) {
        val isPrimitive = value is Boolean || value is String || value is Int ||
                value is Long || value is Float || value is Double

        if (!isPrimitive && cache[key] == value) return // không đổi -> bỏ ghi

        prefs.edit {
            when (value) {
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Double -> putString(key, value.toString())
                else -> putString(key, gson.toJson(value))
            }
        }

        if (isPrimitive) cache.remove(key) else cache[key] = value
    }

    @PublishedApi
    internal fun <T> delegate(
        get: () -> T,
        set: (T) -> Unit,
    ): ReadWriteProperty<Any?, T> = object : ReadWriteProperty<Any?, T> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = get()
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) = set(value)
    }

    @Stable
    inner class ObservablePreference<T : Any> @PublishedApi internal constructor(
        private val key: String,
        val default: T,
        private val type: Type,
    ) {
        private fun current(): T = readRuntime(key, default, type)

        var value: T
            get() = current()
            set(newValue) = writeRuntime(key, newValue)

        fun update(transform: (T) -> T) = synchronized(prefs) {
            value = transform(value)
        }

        val liveData: LiveData<T> = object : LiveData<T>() {
            private var dispose: (() -> Unit)? = null

            override fun onActive() {
                postValue(current())
                dispose = subscribe(key) {
                    val newValue = current()
                    if (newValue != getValue()) postValue(newValue)
                }
            }

            override fun onInactive() {
                dispose?.invoke()
                dispose = null
            }
        }

        val flow: Flow<T> = callbackFlow {
            trySend(current())
            val dispose = subscribe(key) { trySend(current()) }
            awaitClose { dispose() }
        }.distinctUntilChanged()

        fun stateFlow(
            scope: CoroutineScope,
            started: SharingStarted = SharingStarted.Eagerly,
        ): StateFlow<T> = flow.stateIn(scope, started, value)
    }
}
