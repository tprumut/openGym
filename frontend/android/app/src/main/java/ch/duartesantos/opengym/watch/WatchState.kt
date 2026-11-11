package ch.duartesantos.opengym.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The openGym mobile app owns the state. This adapter reads it from the live
 * WebView when possible and falls back to the durable opengym-state.json file.
 * Writes update the file first, then push the new state into the running UI.
 */
object WatchState {
    private const val TAG = "WatchState"
    private const val FILE = "opengym-state.json"
    private const val TIMEOUT_SEC = 5L

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var webViewRef: WeakReference<WebView>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun attach(webView: WebView?) {
        webViewRef = if (webView != null) WeakReference(webView) else null
    }

    fun getState(): JSONObject? {
        val fromWeb = readFromWebView()
        if (fromWeb != null && fromWeb.length() > 0) return fromWeb
        return readFile()
    }

    fun getActiveSnapshot(): JSONObject? =
        parseWebResult(evalOnWebView("(function(){try{return window.__openGymWatch?window.__openGymWatch.getActive():null}catch(e){return null}})()"))

    fun completeCurrent(): JSONObject {
        val raw = evalOnWebView(
            "(function(){try{return window.__openGymWatch?window.__openGymWatch.completeCurrent()" +
                ":JSON.stringify({ok:false,error:'Open openGym on the phone'})}" +
                "catch(e){return JSON.stringify({ok:false,error:(e&&e.message)||'Complete failed'})}})()"
        )
        return parseWebResult(raw)
            ?: JSONObject().put("ok", false).put("error", "Open openGym on the phone")
    }

    fun setState(state: JSONObject) {
        val context = appContext ?: return
        val json = state.toString()

        try {
            val file = File(context.filesDir, FILE)
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "state file write failed", t)
        }

        val view = webViewRef?.get() ?: return
        main.post {
            try {
                val js = "(function(){try{return window.__openGymWatch?window.__openGymWatch.setState(${JSONObject.quote(json)}):false}catch(e){return false}})()"
                view.evaluateJavascript(js, null)
            } catch (t: Throwable) {
                Log.w(TAG, "state push to WebView failed", t)
            }
        }
    }

    private fun readFromWebView(): JSONObject? =
        parseWebResult(evalOnWebView("(function(){try{return window.__openGymWatch?window.__openGymWatch.getState():null}catch(e){return null}})()"))

    private fun evalOnWebView(script: String): String? {
        val view = webViewRef?.get() ?: return null
        if (Looper.myLooper() == Looper.getMainLooper()) return null

        val latch = CountDownLatch(1)
        var value: String? = null

        main.post {
            try {
                view.evaluateJavascript(script) {
                    value = it
                    latch.countDown()
                }
            } catch (t: Throwable) {
                latch.countDown()
            }
        }

        if (!latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)) return null
        return value
    }

    private fun parseWebResult(value: String?): JSONObject? {
        if (value.isNullOrBlank() || value == "null") return null
        return try {
            when (val parsed = JSONTokener(value).nextValue()) {
                is String -> if (parsed.isBlank() || parsed == "null") null else JSONObject(parsed)
                else -> null
            }
        } catch (t: Throwable) {
            Log.w(TAG, "WebView state parse failed", t)
            null
        }
    }

    private fun readFile(): JSONObject? {
        val context = appContext ?: return null
        return try {
            val file = File(context.filesDir, FILE)
            if (!file.exists() || file.length() == 0L) return null
            JSONObject(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            Log.w(TAG, "state file read failed", t)
            null
        }
    }
}
