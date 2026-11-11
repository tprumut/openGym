package ch.duartesantos.opengym

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import ch.duartesantos.opengym.watch.BridgeBus
import ch.duartesantos.opengym.watch.BridgePrefs
import ch.duartesantos.opengym.watch.P2pService
import ch.duartesantos.opengym.watch.WatchState
import ch.duartesantos.opengym.watch.describeWearFailure
import com.huawei.wearengine.HiWear
import com.huawei.wearengine.auth.AuthCallback
import com.huawei.wearengine.auth.AuthClient
import com.huawei.wearengine.auth.Permission

/**
 * Lifecycle glue between [MainActivity] and the Huawei Wear Engine bridge
 * ([P2pService], [WatchBridgePlugin]). Kept out of the activity itself so the
 * watch feature is one file to read instead of a diff spread over the activity.
 * Nothing here holds the activity: every entry point gets it from the caller.
 */
object WatchBridgeController {
    private const val TAG = "WatchBridge"
    private const val PERMISSIONS_REQUEST = 4201

    private var afterPermissions: (() -> Unit)? = null
    private var wearAuthPending: (() -> Unit)? = null
    private var wearAuthInFlight = false

    fun onCreate(host: MainActivity) {
        WatchState.init(host)
        WatchState.attach(host.bridgeWebView())
        if (BridgePrefs.enabled(host)) {
            ensurePermissions(host) { ensureWearEngine(host) { startWatchService(host) } }
        } else {
            BridgeBus.publish("disabled", "Watch sync off")
        }
    }

    fun onResume(host: MainActivity) {
        WatchState.attach(host.bridgeWebView())
        if (BridgePrefs.enabled(host)) {
            if (bluetoothReady(host)) startWatchService(host)
        } else {
            host.stopService(Intent(host, P2pService::class.java))
        }
    }

    fun start(host: MainActivity) {
        BridgePrefs.setEnabled(host, true)
        ensurePermissions(host) { ensureWearEngine(host) { startWatchService(host) } }
    }

    fun syncNow(host: MainActivity) {
        BridgePrefs.setEnabled(host, true)
        ensurePermissions(host) { ensureWearEngine(host) { startWatchService(host, P2pService.ACTION_SYNC_NOW) } }
    }

    fun stop(host: MainActivity) {
        BridgePrefs.setEnabled(host, false)
        BridgeBus.publish("disabled", "Watch sync off")
        host.stopService(Intent(host, P2pService::class.java))
    }

    fun onRequestPermissionsResult(host: MainActivity, requestCode: Int, grantResults: IntArray) {
        if (requestCode != PERMISSIONS_REQUEST) return
        val action = afterPermissions
        afterPermissions = null
        if (action == null) return
        if (bluetoothReady(host)) {
            action()
        } else {
            Toast.makeText(host, "Bluetooth permission is required to reach the watch", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startWatchService(host: MainActivity, action: String? = null) {
        try {
            val intent = Intent(host, P2pService::class.java)
            if (action != null) intent.action = action
            ContextCompat.startForegroundService(host, intent)
        } catch (t: Throwable) {
            Toast.makeText(host, "Watch sync: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensurePermissions(host: MainActivity, onReady: () -> Unit) {
        val missing = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 &&
            !hasPermission(host, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            missing.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            if (!hasPermission(host, Manifest.permission.BLUETOOTH_CONNECT)) {
                missing.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (!hasPermission(host, Manifest.permission.BLUETOOTH_SCAN)) {
                missing.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else if (!hasPermission(host, Manifest.permission.ACCESS_FINE_LOCATION)) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (missing.isEmpty()) {
            onReady()
            return
        }
        afterPermissions = onReady
        host.requestPermissions(missing.toTypedArray(), PERMISSIONS_REQUEST)
    }

    private fun ensureWearEngine(host: MainActivity, onReady: () -> Unit) {
        val client = try {
            HiWear.getAuthClient(host)
        } catch (t: Throwable) {
            Log.w(TAG, "getAuthClient failed", t)
            onReady()
            return
        }
        client.checkPermission(Permission.DEVICE_MANAGER)
            .addOnSuccessListener { granted ->
                Log.i(TAG, "checkPermission(device_manager) = $granted")
                if (granted == true) onReady() else requestWearPermission(host, client, onReady)
            }
            .addOnFailureListener { e ->
                Log.w(TAG, "checkPermission failed: ${e.message}", e)
                requestWearPermission(host, client, onReady)
            }
    }

    private fun requestWearPermission(host: MainActivity, client: AuthClient, onReady: () -> Unit) {
        if (wearAuthInFlight) {
            wearAuthPending = onReady
            return
        }
        wearAuthInFlight = true
        wearAuthPending = onReady
        Log.i(TAG, "requesting device_manager permission")
        try {
            client.requestPermission(object : AuthCallback {
                override fun onOk(permissions: Array<Permission>) {
                    Log.i(TAG, "device_manager granted")
                    wearAuthInFlight = false
                    val action = wearAuthPending
                    wearAuthPending = null
                    action?.invoke()
                }

                override fun onCancel() {
                    Log.w(TAG, "device_manager denied by user")
                    wearAuthInFlight = false
                    wearAuthPending = null
                    BridgeBus.publish("offline", "Watch authorization was denied")
                }
            }, Permission.DEVICE_MANAGER)
                .addOnFailureListener { e ->
                    Log.w(TAG, "requestPermission failed: ${e.message}", e)
                    wearAuthInFlight = false
                    wearAuthPending = null
                    val failure = describeWearFailure(e, e.message ?: "Watch authorization failed")
                    BridgeBus.publish(failure.link, failure.detail)
                }
        } catch (t: Throwable) {
            Log.w(TAG, "requestPermission threw", t)
            wearAuthInFlight = false
            wearAuthPending = null
            BridgeBus.publish("offline", t.message ?: "Watch authorization failed")
        }
    }

    private fun bluetoothReady(host: MainActivity): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            hasPermission(host, Manifest.permission.BLUETOOTH_CONNECT) &&
                hasPermission(host, Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(host, Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermission(host: MainActivity, permission: String): Boolean =
        ContextCompat.checkSelfPermission(host, permission) == PackageManager.PERMISSION_GRANTED
}
