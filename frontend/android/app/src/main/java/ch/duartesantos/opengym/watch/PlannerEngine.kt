package ch.duartesantos.opengym.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs the exact openGym planner bundle in a headless WebView. The bridge never
 * reimplements progression: it feeds state in and gets a day snapshot out.
 */
class PlannerEngine(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var webView: WebView? = null

    @Volatile
    private var ready = false

    fun buildDay(state: JSONObject, iso: String): JSONObject {
        ensureWebView()

        val args = JSONObject.quote(state.toString()) + "," + JSONObject.quote(iso)
        val latch = CountDownLatch(1)
        var result: String? = null
        var error: String? = null

        main.post {
            val view = webView
            if (view == null) {
                error = "WebView is not ready"
                latch.countDown()
                return@post
            }

            val js = "(function(){try{return OpenGymPlanner.buildDayJson($args)}" +
                "catch(e){return '__ERR__'+(e&&e.message?e.message:e)}})()"

            try {
                view.evaluateJavascript(js) { value ->
                    try {
                        when (val parsed = JSONTokener(value ?: "null").nextValue()) {
                            is String -> {
                                if (parsed.startsWith("__ERR__")) error = parsed.removePrefix("__ERR__")
                                else result = parsed
                            }
                            else -> error = "planner returned an unexpected value"
                        }
                    } catch (t: Throwable) {
                        error = t.message ?: "planner result could not be parsed"
                    }
                    latch.countDown()
                }
            } catch (t: Throwable) {
                error = t.message ?: "planner could not be evaluated"
                latch.countDown()
            }
        }

        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw ApiException("local", "Planner timed out")
        }
        error?.let { throw ApiException("local", "Planner: $it") }

        return try {
            JSONObject(result ?: throw ApiException("local", "Planner returned nothing"))
        } catch (e: ApiException) {
            throw e
        } catch (t: Throwable) {
            throw ApiException("local", "Planner returned invalid JSON")
        }
    }

    fun destroy() {
        main.post {
            webView?.destroy()
            webView = null
            ready = false
        }
    }

    @Synchronized
    private fun ensureWebView() {
        if (ready && webView != null) return

        val latch = CountDownLatch(1)
        var initError: String? = null

        main.post {
            try {
                val view = WebView(appContext)
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = false
                view.settings.allowContentAccess = false
                view.settings.allowFileAccess = true
                view.settings.blockNetworkLoads = true
                view.settings.cacheMode = WebSettings.LOAD_NO_CACHE
                view.settings.setAllowFileAccessFromFileURLs(true)
                view.settings.setAllowUniversalAccessFromFileURLs(false)

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        ready = true
                        latch.countDown()
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        initError = description ?: "asset failed to load"
                        latch.countDown()
                    }
                }

                webView = view
                view.loadDataWithBaseURL(
                    "file:///android_asset/",
                    HTML,
                    "text/html",
                    "UTF-8",
                    null
                )
            } catch (t: Throwable) {
                initError = t.message ?: "WebView initialization failed"
                latch.countDown()
            }
        }

        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw ApiException("local", "Planner WebView timed out")
        }
        initError?.let { throw ApiException("local", "Planner unavailable: $it") }
        if (!ready) throw ApiException("local", "Planner unavailable")
    }

    companion object {
        private const val HTML =
            "<!doctype html><html><head><meta charset=\"utf-8\"></head><body>" +
                "<script src=\"planner.iife.js\"></script></body></html>"
    }
}
