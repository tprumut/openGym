package ch.duartesantos.opengym;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import androidx.annotation.NonNull;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.huawei.hmf.tasks.OnFailureListener;
import com.huawei.wearengine.HiWear;
import com.huawei.wearengine.device.Device;
import com.huawei.wearengine.device.DeviceClient;
import com.huawei.wearengine.p2p.Message;
import com.huawei.wearengine.p2p.P2pClient;
import com.huawei.wearengine.p2p.Receiver;
import com.huawei.wearengine.p2p.SendCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Capacitor bridge for the openGym Lite Wearable app on HUAWEI WATCH FIT 5 Pro.
 *
 * The React layer deliberately sees the same logical methods as the Apple Watch bridge:
 * status, sendSnapshot, drain, ack and openOnWatch. Wear Engine messages are JSON envelopes;
 * events received from the watch are persisted before the JS event is emitted, so a WebView
 * restart cannot lose a set completion.
 */
@CapacitorPlugin(name = "HuaweiWatchBridge")
public final class HuaweiWatchBridge extends Plugin {
    private static final String TAG = "HuaweiWatchBridge";
    private static final String PREFS = "opengym_huawei_watch";
    private static final String EVENTS = "events";
    private static final String WIRE_VERSION = "opengym-fit-v1";

    private P2pClient p2p;
    private Device device;
    private Receiver receiver;
    private final Object lock = new Object();

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private String watchPackage() {
        return BuildConfig.HUAWEI_WATCH_PACKAGE == null ? "" : BuildConfig.HUAWEI_WATCH_PACKAGE.trim();
    }

    private String watchFingerprint() {
        return BuildConfig.HUAWEI_WATCH_FINGERPRINT == null ? "" : BuildConfig.HUAWEI_WATCH_FINGERPRINT.trim();
    }

    private boolean configured() {
        return !watchPackage().isEmpty() && !watchFingerprint().isEmpty();
    }

    private P2pClient p2p() {
        if (p2p == null) p2p = HiWear.getP2pClient(getContext());
        if (configured()) {
            p2p.setPeerPkgName(watchPackage());
            p2p.setPeerFingerPrint(watchFingerprint());
        }
        return p2p;
    }

    @Override
    public void load() {
        super.load();
        if (!configured()) {
            Log.w(TAG, "Huawei watch bridge is disabled: set HUAWEI_WATCH_PACKAGE and HUAWEI_WATCH_FINGERPRINT");
            return;
        }
        try {
            p2p();
            receiver = message -> onMessage(message);
            // The receiver is registered after the first bonded device is discovered in status().
        } catch (Exception e) {
            Log.w(TAG, "Wear Engine is unavailable", e);
        }
    }

    private void onMessage(Message message) {
        if (message == null || message.getType() != Message.MESSAGE_TYPE_DATA || message.getData() == null) return;
        try {
            String raw = new String(message.getData(), StandardCharsets.UTF_8);
            JSONObject wire = new JSONObject(raw);
            if (!WIRE_VERSION.equals(wire.optString("v"))) return;
            JSONObject event = wire.optJSONObject("event");
            if (event == null || event.optString("id", "").isEmpty()) return;
            enqueue(event);
            JSObject detail = JSObject.fromJSONObject(event);
            notifyListeners("watchEvent", detail);
        } catch (Exception e) {
            Log.w(TAG, "Ignoring malformed wearable message", e);
        }
    }

    private void enqueue(JSONObject event) {
        synchronized (lock) {
            try {
                JSONArray old = new JSONArray(prefs().getString(EVENTS, "[]"));
                JSONArray next = new JSONArray();
                for (int i = 0; i < old.length(); i++) next.put(old.get(i));
                next.put(event);
                while (next.length() > 100) next = tail(next, 100);
                prefs().edit().putString(EVENTS, next.toString()).apply();
            } catch (Exception e) {
                Log.w(TAG, "Could not persist wearable event", e);
            }
        }
    }

    private static JSONArray tail(JSONArray source, int max) throws Exception {
        JSONArray out = new JSONArray();
        int start = Math.max(0, source.length() - max);
        for (int i = start; i < source.length(); i++) out.put(source.get(i));
        return out;
    }

    private void fail(PluginCall call, String message, Exception error) {
        Log.w(TAG, message, error);
        call.reject(message, error);
    }

    private void discover(PluginCall call, DeviceCallback callback) {
        if (!configured()) {
            JSObject ret = new JSObject();
            ret.put("paired", false);
            ret.put("installed", false);
            ret.put("configured", false);
            call.resolve(ret);
            return;
        }
        try {
            DeviceClient devices = HiWear.getDeviceClient(getContext());
            devices.getBondedDevices()
                    .addOnSuccessListener(list -> {
                        Device found = null;
                        if (list != null) {
                            for (Device candidate : list) {
                                if (candidate != null) { found = candidate; break; }
                            }
                        }
                        device = found;
                        callback.run(found);
                    })
                    .addOnFailureListener(error -> fail(call, "Could not discover Huawei wearable", error));
        } catch (Exception e) {
            fail(call, "Wear Engine is unavailable", e);
        }
    }

    private interface DeviceCallback { void run(Device device); }

    @PluginMethod
    public void status(PluginCall call) {
        discover(call, found -> {
            if (found == null) {
                JSObject ret = new JSObject();
                ret.put("paired", false);
                ret.put("installed", false);
                ret.put("configured", true);
                call.resolve(ret);
                return;
            }
            try {
                // Wear Engine's supported Android probe is ping(device). A successful ping means
                // the configured peer HAP is installed and reachable; a failed task means it is
                // either absent or currently unavailable.
                p2p().ping(found, result -> { })
                        .addOnSuccessListener(ignored -> {
                            JSObject ret = new JSObject();
                            ret.put("paired", true);
                            ret.put("installed", true);
                            ret.put("configured", true);
                            ret.put("device", found.getName());
                            call.resolve(ret);
                            registerReceiver(found);
                        })
                        .addOnFailureListener(error -> {
                            JSObject ret = new JSObject();
                            ret.put("paired", true);
                            ret.put("installed", false);
                            ret.put("configured", true);
                            ret.put("device", found.getName());
                            call.resolve(ret);
                        });
            } catch (Exception e) {
                fail(call, "Could not query watch app", e);
            }
        });
    }

    private void registerReceiver(Device target) {
        if (target == null || receiver == null) return;
        try {
            p2p().registerReceiver(target, receiver).addOnFailureListener(error -> Log.w(TAG, "Receiver registration failed", error));
        } catch (Exception e) {
            Log.w(TAG, "Receiver registration failed", e);
        }
    }

    private void sendEnvelope(String type, String json, PluginCall call) {
        if (!configured()) {
            call.reject("Huawei Wear Engine is not configured");
            return;
        }
        if (device == null) {
            discover(call, found -> {
                if (found == null) { call.reject("No paired Huawei wearable found"); return; }
                sendEnvelopeTo(type, json, found, call);
            });
            return;
        }
        sendEnvelopeTo(type, json, device, call);
    }

    private void sendEnvelopeTo(String type, String json, Device target, PluginCall call) {
        try {
            JSONObject wire = new JSONObject();
            wire.put("v", WIRE_VERSION);
            wire.put("type", type);
            if (json != null) wire.put("json", json);
            Message message = new Message.Builder()
                    .setPayload(wire.toString().getBytes(StandardCharsets.UTF_8))
                    .build();
            p2p().send(target, message, new SendCallback() {
                @Override public void onSendResult(int resultCode) {
                    if (resultCode == 207) {
                        JSObject ret = new JSObject();
                        ret.put("sent", true);
                        call.resolve(ret);
                    } else call.reject("Wear Engine send failed: " + resultCode);
                }
                @Override public void onSendProgress(long progress) { }
            }).addOnFailureListener(error -> fail(call, "Wear Engine send failed", error));
        } catch (Exception e) {
            fail(call, "Could not send message to Huawei wearable", e);
        }
    }

    @PluginMethod
    public void sendSnapshot(PluginCall call) {
        sendEnvelope("snapshot", call.getString("json", ""), call);
    }

    @PluginMethod
    public void openOnWatch(PluginCall call) {
        sendEnvelope("open", call.getString("type", ""), call);
    }

    /** Animation transfer is intentionally deferred for the first Fit 5 Pro MVP. */
    @PluginMethod
    public void sendClips(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("sent", false);
        ret.put("unsupported", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void dropClips(PluginCall call) {
        call.resolve();
    }

    @PluginMethod
    public void drain(PluginCall call) {
        JSArray out = new JSArray();
        synchronized (lock) {
            try {
                JSONArray events = new JSONArray(prefs().getString(EVENTS, "[]"));
                for (int i = 0; i < events.length(); i++) out.put(JSObject.fromJSONObject(events.getJSONObject(i)));
            } catch (Exception e) {
                Log.w(TAG, "Could not read wearable event queue", e);
            }
        }
        JSObject ret = new JSObject();
        ret.put("events", out);
        call.resolve(ret);
    }

    @PluginMethod
    public void ack(PluginCall call) {
        JSArray ids = call.getArray("ids", new JSArray());
        synchronized (lock) {
            try {
                JSONArray old = new JSONArray(prefs().getString(EVENTS, "[]"));
                List<String> remove = new ArrayList<>();
                for (int i = 0; i < ids.length(); i++) remove.add(ids.getString(i));
                JSONArray next = new JSONArray();
                for (int i = 0; i < old.length(); i++) {
                    JSONObject event = old.getJSONObject(i);
                    if (!remove.contains(event.optString("id"))) next.put(event);
                }
                prefs().edit().putString(EVENTS, next.toString()).apply();
            } catch (Exception e) {
                fail(call, "Could not acknowledge wearable events", e);
                return;
            }
        }
        call.resolve();
    }
}
