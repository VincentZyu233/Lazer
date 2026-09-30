package dev.naominet.lazer

import platform.Foundation.NSUserDefaults

/** iOS keeps the same keys as the other platforms; only the store behind them differs. */
fun iosPreferences(defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults): LazerPreferences =
    NsUserDefaultsPreferences(defaults)

private class NsUserDefaultsPreferences(
    private val defaults: NSUserDefaults,
) : LazerPreferences {
    override fun getString(key: String, default: String?): String? = defaults.stringForKey(key) ?: default

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)

    override fun getInt(key: String, default: Int): Int =
        if (defaults.objectForKey(key) == null) default else defaults.integerForKey(key).toInt()

    override fun getLong(key: String, default: Long): Long =
        if (defaults.objectForKey(key) == null) default else defaults.longLongForKey(key)

    override fun getFloat(key: String, default: Float): Float =
        if (defaults.objectForKey(key) == null) default else defaults.doubleForKey(key).toFloat()

    override fun putString(key: String, value: String?) {
        // setObject with nil is how UserDefaults deletes an entry.
        defaults.setObject(value, key)
    }

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, key)
    }

    override fun putInt(key: String, value: Int) {
        defaults.setInteger(value.toLong(), key)
    }

    override fun putLong(key: String, value: Long) {
        defaults.setLongLong(value, key)
    }

    override fun putFloat(key: String, value: Float) {
        defaults.setDouble(value.toDouble(), key)
    }
}
