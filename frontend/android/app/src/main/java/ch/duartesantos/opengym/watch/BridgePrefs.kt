package ch.duartesantos.opengym.watch

import android.content.Context

object BridgePrefs {
    private const val FILE = "watch_bridge"
    private const val KEY_ENABLED = "enabled"

    // Off until the user turns the watch card on: the bridge asks for Bluetooth and a
    // foreground service, which no one should get on first launch without asking for it.
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
