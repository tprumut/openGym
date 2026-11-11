package ch.duartesantos.opengym.watch

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import ch.duartesantos.opengym.MainActivity
import ch.duartesantos.opengym.WatchBridgeController
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import org.json.JSONObject

@CapacitorPlugin(name = "WatchBridge")
class WatchBridgePlugin : Plugin() {

    @PluginMethod
    fun status(call: PluginCall) {
        val ctx = getContext()
        val snapshot = BridgeBus.snapshot()
        val result = JSObject()
        result.put("enabled", BridgePrefs.enabled(ctx))
        result.put("running", P2pService.running)
        result.put("link", snapshot.link)
        result.put("detail", snapshot.detail)
        result.put("lastSync", snapshot.lastSync ?: JSONObject.NULL)
        result.put("bluetooth", hasBluetooth(ctx))
        result.put("location", hasLocation(ctx))
        result.put("notifications", hasNotifications(ctx))
        call.resolve(result)
    }

    @PluginMethod
    fun start(call: PluginCall) {
        BridgePrefs.setEnabled(getContext(), true)
        onMain { (getActivity() as? MainActivity)?.let { WatchBridgeController.start(it) } }
        call.resolve()
    }

    @PluginMethod
    fun stop(call: PluginCall) {
        BridgePrefs.setEnabled(getContext(), false)
        onMain { (getActivity() as? MainActivity)?.let { WatchBridgeController.stop(it) } }
        call.resolve()
    }

    @PluginMethod
    fun syncNow(call: PluginCall) {
        BridgePrefs.setEnabled(getContext(), true)
        onMain { (getActivity() as? MainActivity)?.let { WatchBridgeController.syncNow(it) } }
        call.resolve()
    }

    @PluginMethod
    fun pushActive(call: PluginCall) {
        val json = call.getString("json") ?: "null"
        P2pService.publishActive(getContext(), json)
        call.resolve()
    }

    @PluginMethod
    fun openSettings(call: PluginCall) {
        val ctx = getContext()
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${ctx.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        call.resolve()
    }

    private fun onMain(block: () -> Unit) {
        val activity = getActivity()
        if (activity != null) activity.runOnUiThread(block) else block()
    }

    private fun hasBluetooth(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            granted(context, Manifest.permission.BLUETOOTH_CONNECT) &&
                granted(context, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            true
        }

    private fun hasLocation(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 ||
            granted(context, Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasNotifications(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            granted(context, Manifest.permission.POST_NOTIFICATIONS)

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
