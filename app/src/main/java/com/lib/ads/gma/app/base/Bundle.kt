@file:Suppress("DEPRECATION")

package com.lib.ads.gma.app.base

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.result.ActivityResult
import androidx.core.os.BundleCompat
import androidx.fragment.app.Fragment
import java.io.Serializable

@Suppress("UNCHECKED_CAST")
fun Intent.putExtraSmart(key: String, v: Any?) {
    when (v) {
        null -> putExtra(key, null as Serializable?)
        is Int -> putExtra(key, v)
        is Long -> putExtra(key, v)
        is Boolean -> putExtra(key, v)
        is Float -> putExtra(key, v)
        is Double -> putExtra(key, v)
        is String -> putExtra(key, v)
        is CharSequence -> putExtra(key, v)
        is Bundle -> putExtra(key, v)
        is Parcelable -> putExtra(key, v)
        is List<*> -> when {
            v.isEmpty() -> putParcelableArrayListExtra(key, arrayListOf())
            v.all { it is Parcelable } ->
                putParcelableArrayListExtra(key, ArrayList(v as List<Parcelable>))

            v.all { it is String } ->
                putStringArrayListExtra(key, ArrayList(v as List<String>))

            else -> throw IllegalArgumentException("Unsupported List for key=\"$key\"")
        }

        is Serializable -> putExtra(key, v)
        else -> throw IllegalArgumentException(
            "Unsupported extra type for key=\"$key\": ${v::class.java.name}"
        )
    }
}

inline fun <reified T : Any> Activity.getData(key: String): T? = intent?.safeGet<T>(key)
inline fun <reified T : Any> Fragment.getData(key: String): T? = arguments?.safeGet<T>(key)
inline fun <reified T : Any> ActivityResult.getResultData(key: String): T? = data?.safeGet<T>(key)

inline fun <reified T : Any> Activity.getData(key: String, default: T): T =
    intent?.safeGet<T>(key) ?: default

inline fun <reified T1 : Any, reified T2 : Any> Activity.getData(
    first: Pair<String, T1>,
    second: Pair<String, T2>,
    block: (T1, T2) -> Unit
) {
    val value1 = intent?.safeGet<T1>(first.first) ?: first.second
    val value2 = intent?.safeGet<T2>(second.first) ?: second.second

    block(value1, value2)
}

inline fun <reified T1 : Any, reified T2 : Any, reified T3 : Any> Activity.getData(
    first: Pair<String, T1>,
    second: Pair<String, T2>,
    third: Pair<String, T3>,
    block: (T1, T2, T3) -> Unit
) {
    val value1 = intent?.safeGet<T1>(first.first) ?: first.second
    val value2 = intent?.safeGet<T2>(second.first) ?: second.second
    val value3 = intent?.safeGet<T3>(third.first) ?: third.second

    block(value1, value2, value3)
}

inline fun <reified T : Any> Fragment.getData(key: String, default: T): T =
    arguments?.safeGet<T>(key) ?: default

inline fun <reified T : Any> Intent.safeGet(key: String): T? =
    extras?.safeGet<T>(key)

@Suppress("UNCHECKED_CAST")
inline fun <reified T : Any> Bundle.safeGet(key: String): T? {
    if (!containsKey(key)) return null
    return when (T::class) {
        String::class -> getString(key) as? T
        Int::class -> getInt(key) as T
        Boolean::class -> getBoolean(key) as T
        Long::class -> getLong(key) as T
        Float::class -> getFloat(key) as T
        Double::class -> getDouble(key) as T
        else -> when {
            Parcelable::class.java.isAssignableFrom(T::class.java) ->
                BundleCompat.getParcelable(this, key, T::class.java as Class<Parcelable>) as? T

            Serializable::class.java.isAssignableFrom(T::class.java) ->
                BundleCompat.getSerializable(this, key, T::class.java as Class<Serializable>) as? T

            else -> get(key) as? T
        }
    }
}

fun Boolean?.orFalse() = this == true