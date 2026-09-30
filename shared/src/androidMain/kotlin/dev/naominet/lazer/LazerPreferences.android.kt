package dev.naominet.lazer

import android.content.Context
import android.content.SharedPreferences

/** The file Android has kept settings in since the first release; reading anything else loses them. */
const val ANDROID_PREFERENCES_NAME = "lazer.android.settings"

fun androidPreferences(context: Context, name: String = ANDROID_PREFERENCES_NAME): LazerPreferences =
    SharedPreferencesPreferences(
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE),
    )

private class SharedPreferencesPreferences(
    private val store: SharedPreferences,
) : LazerPreferences {
    override fun getString(key: String, default: String?): String? = store.getString(key, default)

    override fun getBoolean(key: String, default: Boolean): Boolean = store.getBoolean(key, default)

    override fun getInt(key: String, default: Int): Int = store.getInt(key, default)

    override fun getLong(key: String, default: Long): Long = store.getLong(key, default)

    override fun getFloat(key: String, default: Float): Float = store.getFloat(key, default)

    override fun putString(key: String, value: String?) = edit {
        if (value == null) remove(key) else putString(key, value)
    }

    override fun putBoolean(key: String, value: Boolean) = edit { putBoolean(key, value) }

    override fun putInt(key: String, value: Int) = edit { putInt(key, value) }

    override fun putLong(key: String, value: Long) = edit { putLong(key, value) }

    override fun putFloat(key: String, value: Float) = edit { putFloat(key, value) }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        store.edit().apply(block).apply()
    }
}
