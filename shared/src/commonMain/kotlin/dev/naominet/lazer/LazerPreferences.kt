package dev.naominet.lazer

/**
 * The key/value persistence every setting and cache reads, backed by the host platform's own store.
 * Keeping the surface this small is what lets one settings class serve every platform, so a switch
 * flipped on Android is the same switch with the same key on iOS.
 */
interface LazerPreferences {
    fun getString(key: String, default: String?): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun getFloat(key: String, default: Float): Float

    fun putString(key: String, value: String?)
    fun putBoolean(key: String, value: Boolean)
    fun putInt(key: String, value: Int)
    fun putLong(key: String, value: Long)
    fun putFloat(key: String, value: Float)
}
