package ch.duartesantos.opengym.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ch.duartesantos.opengym.BuildConfig
import ch.duartesantos.opengym.MainActivity
import ch.duartesantos.opengym.R
import com.huawei.wearengine.HiWear
import com.huawei.wearengine.common.WearEngineErrorCode
import com.huawei.wearengine.device.Device
import com.huawei.wearengine.p2p.Message as P2pMessage
import com.huawei.wearengine.p2p.P2pClient
import com.huawei.wearengine.p2p.Receiver
import com.huawei.wearengine.p2p.SendCallback
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class P2pService : Service() {

    private lateinit var planner: PlannerEngine

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var p2p: P2pClient? = null
    private var device: Device? = null

    @Volatile
    private var registered = false

    @Volatile
    private var connecting = false

    @Volatile
    private var latestActiveJson: String? = null

    @Volatile
    private var hasLatestActive = false

    private var retryDelayMs = 5_000L

    @Volatile
    private var nextRetryAt = 0L

    private val retryRunnable = Runnable { connectP2p() }

    private val health = object : Runnable {
        override fun run() {
            if (registered && device?.isConnected != true) {
                registered = false
                unregisterReceiver()
                BridgeBus.publish("offline", "Watch disconnected")
            }
            if (!registered && System.currentTimeMillis() >= nextRetryAt) connectP2p()
            main.postDelayed(this, 15_000)
        }
    }

    private val receiver = object : Receiver {
        override fun onReceiveMessage(message: P2pMessage) {
            val payload = message.data?.let { String(it, StandardCharsets.UTF_8) }
            val text = if (!payload.isNullOrBlank()) payload else message.description
            if (text.isNullOrBlank()) return

            val json = try {
                JSONObject(text)
            } catch (_: Exception) {
                Log.w(TAG, "ignoring non-JSON P2P message")
                return
            }

            if (json.optInt("v", 0) != PROTOCOL_VERSION) return

            when (json.optString("t")) {
                "hello" -> handleSync(todayIso())
                "sync" -> handleSync(json.optString("date").ifBlank { todayIso() })
                "ping" -> sendJson(
                    JSONObject()
                        .put("t", "pong")
                        .put("v", PROTOCOL_VERSION)
                        .put("at", System.currentTimeMillis())
                )
                "plan" -> handlePlan(json)
                "finish" -> handleFinish(json)
                "complete" -> handleComplete()
                "revoke" -> handleRevoke()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        WatchState.init(this)
        planner = PlannerEngine(this)

        createChannel()
        startForegroundNow()
        running = true

        BridgeBus.publish("starting", "Starting watch sync")
        connectP2p()
        main.postDelayed(health, 15_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC_NOW -> {
                if (!registered) {
                    BridgeBus.publish("offline", "Watch not connected")
                    nextRetryAt = 0L
                    connectP2p()
                } else {
                    handleSync(todayIso())
                }
            }
            ACTION_ACTIVE -> {
                intent?.getStringExtra(EXTRA_ACTIVE)?.let { receiveActive(it) }
                if (!registered) connectP2p()
            }
            ACTION_REVOKE -> handleRevoke()
            else -> connectP2p()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        unregisterReceiver()
        planner.destroy()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun unregisterReceiver() {
        try {
            p2p?.unregisterReceiver(receiver)
        } catch (t: Throwable) {
            Log.w(TAG, "unregisterReceiver failed", t)
        }
    }

    private fun connectP2p() {
        if (registered || connecting) return
        if (BuildConfig.WATCH_FINGERPRINT.startsWith("REPLACE_")) {
            BridgeBus.publish("error", "Set watch.fingerprint in local.properties")
            scheduleReconnect(60_000L)
            return
        }

        connecting = true
        BridgeBus.publish("starting", "Looking for the watch")

        try {
            val client = HiWear.getP2pClient(applicationContext)
            p2p = client

            HiWear.getDeviceClient(applicationContext).bondedDevices
                .addOnSuccessListener { devices ->
                    val connected = devices.firstOrNull { it.isConnected }
                    Log.i(TAG, "bonded devices: ${devices.size}, connected: ${connected}")
                    if (connected == null) {
                        failConnect("Watch not connected")
                        return@addOnSuccessListener
                    }

                    device = connected
                    client.isAppInstalled(connected, BuildConfig.WATCH_PACKAGE)
                        .addOnSuccessListener { installed ->
                            Log.i(TAG, "isAppInstalled(${BuildConfig.WATCH_PACKAGE}) = $installed")
                            if (!installed) {
                                failConnect("Install openGym on the watch")
                                return@addOnSuccessListener
                            }
                            register(client, connected)
                        }
                        .addOnFailureListener { failConnect(it) }
                }
                .addOnFailureListener { failConnect(it) }
        } catch (t: Throwable) {
            failConnect(t)
        }
    }

    private fun register(client: P2pClient, connected: Device) {
        try {
            client.setPeerPkgName(BuildConfig.WATCH_PACKAGE)
            client.setPeerFingerPrint(BuildConfig.WATCH_FINGERPRINT)

            client.registerReceiver(connected, receiver)
                .addOnSuccessListener {
                    registered = true
                    connecting = false
                    retryDelayMs = 5_000L
                    nextRetryAt = 0L
                    main.removeCallbacks(retryRunnable)
                    Log.i(TAG, "P2P registered with $connected")
                    BridgeBus.publish("ready", "Watch connected")
                    handleSync(todayIso())
                    sendCurrentActive()
                }
                .addOnFailureListener { failConnect(it) }
        } catch (t: Throwable) {
            failConnect(t)
        }
    }

    private fun failConnect(t: Throwable) {
        failConnect(describeWearFailure(t, t.message ?: "Wear Engine is unavailable"))
    }

    private fun failConnect(message: String) {
        failConnect(WearFailure("offline", message))
    }

    private fun failConnect(failure: WearFailure) {
        registered = false
        connecting = false
        Log.w(TAG, "connect failed: ${failure.detail}")
        BridgeBus.publish(failure.link, failure.detail)
        val delay = failure.backoffMs ?: retryDelayMs
        if (failure.backoffMs == null) {
            retryDelayMs = (retryDelayMs * 2).coerceAtMost(60_000L)
        }
        scheduleReconnect(delay)
    }

    private fun scheduleReconnect(delayMs: Long) {
        nextRetryAt = System.currentTimeMillis() + delayMs
        main.removeCallbacks(retryRunnable)
        main.postDelayed(retryRunnable, delayMs)
    }

    private fun handleSync(date: String) {
        if (!DATE.matches(date)) {
            sendErr("bad-request", "sync needs an ISO date")
            return
        }

        BridgeBus.publish("syncing", "Syncing $date")
        worker.execute {
            try {
                val state = WatchState.getState()
                    ?: throw ApiException("auth", "Open openGym on the phone first")
                val day = planner.buildDay(state, date)
                    .put("t", "day")
                    .put("v", PROTOCOL_VERSION)
                sendJson(day)
                sendCurrentActive()
                BridgeBus.publish("ready", "Synced $date", System.currentTimeMillis())
            } catch (e: ApiException) {
                sendErr(e.code, e.message ?: "Sync failed")
                BridgeBus.publish(if (e.code == "auth") "auth" else "offline", e.message ?: "Sync failed")
            } catch (t: Throwable) {
                sendErr("local", t.message ?: "Sync failed")
                BridgeBus.publish("offline", t.message ?: "Sync failed")
            }
        }
    }

    private fun handlePlan(message: JSONObject) {
        val date = message.optString("date").ifBlank { todayIso() }
        val routineId = message.optString("routineId").trim()

        if (!DATE.matches(date) || routineId.isBlank()) {
            sendErr("bad-request", "plan needs date and routineId")
            return
        }

        BridgeBus.publish("syncing", "Updating plan")
        worker.execute {
            try {
                val state = WatchState.getState()
                    ?: throw ApiException("auth", "Open openGym on the phone first")
                if (routineId != "rest") {
                    val routines = state.optJSONArray("routines") ?: JSONArray()
                    var found = false
                    for (i in 0 until routines.length()) {
                        if (routines.optJSONObject(i)?.optString("id") == routineId) {
                            found = true
                            break
                        }
                    }
                    if (!found) throw ApiException("bad-request", "Unknown routine")
                }

                val dayPlan = state.optJSONObject("dayPlan") ?: JSONObject()
                dayPlan.put(date, routineId)
                state.put("dayPlan", dayPlan)
                state.put("_ts", System.currentTimeMillis())
                WatchState.setState(state)

                sendAck("plan", true)
                val day = planner.buildDay(state, date)
                    .put("t", "day")
                    .put("v", PROTOCOL_VERSION)
                sendJson(day)
                BridgeBus.publish("ready", "Plan updated", System.currentTimeMillis())
            } catch (e: ApiException) {
                sendErr(e.code, e.message ?: "Plan update failed")
                BridgeBus.publish(if (e.code == "auth") "auth" else "offline", e.message ?: "Plan update failed")
            } catch (t: Throwable) {
                sendErr("local", t.message ?: "Plan update failed")
                BridgeBus.publish("offline", t.message ?: "Plan update failed")
            }
        }
    }

    private fun handleFinish(message: JSONObject) {
        val workout = message.optJSONObject("workout")
        if (workout == null) {
            sendErr("bad-request", "finish needs workout")
            return
        }

        val id = workout.optString("id").trim()
        BridgeBus.publish("syncing", "Saving workout")
        worker.execute {
            try {
                val state = WatchState.getState()
                    ?: throw ApiException("auth", "Open openGym on the phone first")
                val result = WorkoutMerger.merge(state, workout)
                if (!result.duplicate) WatchState.setState(result.state)

                sendAck("finish", true, result.workoutId)
                val detail = if (result.duplicate) "Workout already saved" else "Workout saved"
                BridgeBus.publish("ready", detail, System.currentTimeMillis())
            } catch (e: ApiException) {
                if (e.code == "auth") sendErr("auth", e.message ?: "Open openGym on the phone first")
                sendAck("finish", false, id, e.message ?: "Save failed")
                BridgeBus.publish(if (e.code == "auth") "auth" else "offline", e.message ?: "Save failed")
            } catch (t: Throwable) {
                sendAck("finish", false, id, t.message ?: "Save failed")
                BridgeBus.publish("offline", t.message ?: "Save failed")
            }
        }
    }

    private fun handleComplete() {
        BridgeBus.publish("syncing", "Completing exercise")
        worker.execute {
            try {
                val result = WatchState.completeCurrent()
                if (result.optBoolean("ok")) {
                    val active = result.opt("active") as? JSONObject
                    rememberActive(active)
                    sendActive(active)
                    sendAck("complete", true)
                    BridgeBus.publish("ready", "Exercise completed", System.currentTimeMillis())
                } else {
                    val error = result.optString("error", "Complete failed")
                    sendAck("complete", false, error = error)
                    BridgeBus.publish("offline", error)
                }
            } catch (t: Throwable) {
                val error = t.message ?: "Complete failed"
                sendAck("complete", false, error = error)
                BridgeBus.publish("offline", error)
            }
        }
    }

    private fun receiveActive(raw: String) {
        val active = try {
            if (raw.isBlank() || raw == "null") null else JSONObject(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "ignoring malformed active snapshot", t)
            return
        }
        rememberActive(active)
        sendActive(active)
    }

    private fun rememberActive(active: JSONObject?) {
        latestActiveJson = active?.toString()
        hasLatestActive = true
    }

    private fun sendCurrentActive() {
        if (hasLatestActive) {
            sendActiveJson(latestActiveJson)
        } else {
            worker.execute {
                val active = WatchState.getActiveSnapshot()
                rememberActive(active)
                sendActive(active)
            }
        }
    }

    private fun sendActiveJson(raw: String?) {
        val active = try {
            if (raw.isNullOrBlank() || raw == "null") null else JSONObject(raw)
        } catch (t: Throwable) {
            Log.w(TAG, "stored active snapshot is malformed", t)
            null
        }
        sendActive(active)
    }

    private fun sendActive(active: JSONObject?) {
        val message = JSONObject()
            .put("t", "active")
            .put("v", PROTOCOL_VERSION)
        if (active == null) message.put("active", JSONObject.NULL) else message.put("active", active)
        sendJson(message)
    }

    private fun handleRevoke() {
        worker.execute {
            BridgePrefs.setEnabled(this, false)
            BridgeBus.publish("disabled", "Unpaired on the watch")
            sendAck("revoke", true)
            main.postDelayed({ stopSelf() }, 1_000L)
        }
    }

    private fun sendAck(
        forWhat: String,
        ok: Boolean,
        workoutId: String? = null,
        error: String? = null
    ) {
        val ack = JSONObject()
            .put("t", "ack")
            .put("v", PROTOCOL_VERSION)
            .put("for", forWhat)
            .put("ok", ok)
        if (!workoutId.isNullOrBlank()) ack.put("workoutId", workoutId)
        if (error != null) ack.put("error", error)
        sendJson(ack)
    }

    private fun sendErr(code: String, message: String) {
        sendJson(
            JSONObject()
                .put("t", "err")
                .put("v", PROTOCOL_VERSION)
                .put("code", code)
                .put("message", message)
        )
    }

    private fun sendJson(json: JSONObject) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { sendJson(json) }
            return
        }

        val text = json.toString()
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > 4096) Log.w(TAG, "P2P message is ${bytes.size} bytes (budget 4096)")

        val client = p2p
        val target = device
        if (!registered || client == null || target == null || !target.isConnected) {
            Log.w(TAG, "cannot send ${json.optString("t")}: watch is not connected")
            return
        }

        try {
            val payload = P2pMessage.Builder().setPayload(bytes).build()
            client.send(target, payload, object : SendCallback {
                override fun onSendResult(resultCode: Int) {
                    if (resultCode != WearEngineErrorCode.ERROR_CODE_COMM_SUCCESS) {
                        Log.w(TAG, "P2P send failed: $resultCode")
                    }
                }

                override fun onSendProgress(progress: Long) = Unit
            })
        } catch (t: Throwable) {
            Log.w(TAG, "P2P send threw", t)
        }
    }

    private fun startForegroundNow() {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val sync = PendingIntent.getService(
            this,
            1,
            Intent(this, P2pService::class.java).setAction(ACTION_SYNC_NOW),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(R.drawable.ic_stat_bridge, getString(R.string.notif_sync), sync)
            .build()

        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_title),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_text)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        @Volatile
        var running = false

        @Volatile
        private var instance: P2pService? = null

        private const val TAG = "P2pService"
        private const val CHANNEL_ID = "watch-sync"
        private const val NOTIFICATION_ID = 42
        private const val PROTOCOL_VERSION = 1

        const val ACTION_SYNC_NOW = "com.opengym.bridge.SYNC_NOW"
        const val ACTION_ACTIVE = "com.opengym.bridge.ACTIVE"
        const val ACTION_REVOKE = "com.opengym.bridge.REVOKE"
        const val EXTRA_ACTIVE = "active"

        private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        // java.time is API 26+; the app runs on 24. The watch protocol only ever needs the
        // local ISO date, which SimpleDateFormat gives without desugaring.
        fun todayIso(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        fun publishActive(context: Context, json: String) {
            val service = instance
            if (service != null) {
                service.receiveActive(json)
                return
            }
            if (!BridgePrefs.enabled(context)) return
            try {
                val intent = Intent(context, P2pService::class.java)
                    .setAction(ACTION_ACTIVE)
                    .putExtra(EXTRA_ACTIVE, json)
                ContextCompat.startForegroundService(context, intent)
            } catch (t: Throwable) {
                Log.w(TAG, "publishActive failed", t)
            }
        }
    }
}
