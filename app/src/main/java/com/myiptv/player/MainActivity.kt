package com.myiptv.player

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {
    companion object {
        @Volatile var core: Core? = null
        @Volatile var server: LocalServer? = null
    }

    // عنوان وهمي: تُخدَّم كل طلباته من داخل التطبيق دون أي اتصال شبكي (لا يتأثر بحظر الاتصال المحلي)
    private val origin = "http://aurora.localhost"
    private lateinit var web: WebView
    private var customView: View? = null
    private var customCb: WebChromeClient.CustomViewCallback? = null
    private var failedShown = false

    private fun theCore(): Core {
        var c = core
        if (c == null) { c = Core(applicationContext); core = c }
        return c
    }

    private fun tcpPort(): Int {
        val s = server
        return if (s != null && s.isAlive) s.listeningPort else 0
    }

    /** الخادم المحلي اختياري (لصفحة الإدارة في المتصفح فقط). فشله لا يمنع عمل التطبيق. */
    private fun ensureTcp() {
        val s0 = server
        if (s0 != null && s0.isAlive) return
        var port = 8787
        while (port <= 8800) {
            val s = LocalServer(theCore(), port)
            try { s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true); server = s; return } catch (e: Exception) { port++ }
        }
    }

    private fun toWeb(r: Resp): WebResourceResponse {
        val code = if (r.code in 300..399) 200 else r.code
        val textual = r.mime.startsWith("text/") || r.mime.contains("json") || r.mime.contains("javascript") || r.mime.contains("mpegurl")
        val h = HashMap<String, String>(r.headers)
        h["Access-Control-Allow-Origin"] = "*"
        if (r.length >= 0) h["Content-Length"] = r.length.toString()
        return WebResourceResponse(
            r.mime.substringBefore(";").trim(), if (textual) "utf-8" else null,
            code, r.reason.ifEmpty { "OK" }, h, r.stream ?: ByteArrayInputStream(ByteArray(0))
        )
    }

    /** الجسر بين الواجهة والنظام (نفس واجهة window.aurora في نسخة ويندوز). */
    inner class Bridge {
        // طلبات الواجهة التي تحمل بيانات (إضافة/تعديل/حذف سيرفر) تمر من هنا
        @JavascriptInterface fun api(method: String, url: String, body: String?): String {
            return try {
                val uri = Uri.parse(origin + url)
                val q = HashMap<String, String>()
                for (n in uri.queryParameterNames) q[n] = uri.getQueryParameter(n) ?: ""
                val r = theCore().handle(method, uri.path ?: "/", q, emptyMap(), body, "127.0.0.1", tcpPort())
                val txt = r.stream?.bufferedReader()?.use { it.readText() } ?: ""
                "${r.code}\n$txt"
            } catch (e: Exception) { "500\n" + (e.message ?: "error") }
        }
        @JavascriptInterface fun openManage() = runOnUiThread {
            try { ensureTcp() } catch (e: Exception) { }
            val s = server
            if (s != null && s.isAlive) {
                val c = theCore()
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:${s.listeningPort}/manage#${c.id}|${c.key}")))
            } else {
                Toast.makeText(this@MainActivity, "Web management is unavailable on this device. Use the in-app Add Playlist.", Toast.LENGTH_LONG).show()
            }
        }
        @JavascriptInterface fun external(exe: String?, url: String?) = runOnUiThread {
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*")
                startActivity(Intent.createChooser(i, "Play with"))
            }
        }
        @JavascriptInterface fun quit() = runOnUiThread { finish() }
    }

    private fun showFailure(view: WebView, why: String) {
        failedShown = true
        val html = "<html><body style='background:#060914;color:#fff;font-family:sans-serif;padding:24px'>" +
            "<h2>Aurora IPTV</h2><p>The app screen could not start.</p>" +
            "<p style='color:#ff9a9a;word-break:break-all'>" + TextUtils.htmlEncode(why) + "</p>" +
            "<p style='color:#9aa3c7'>Send a screenshot of this page to the developer.</p></body></html>"
        view.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#060914"))
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        web.addJavascriptInterface(Bridge(), "aurora")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                val u = req.url
                if (u.host != "aurora.localhost") return null
                return try {
                    val q = HashMap<String, String>()
                    for (n in u.queryParameterNames) q[n] = u.getQueryParameter(n) ?: ""
                    toWeb(theCore().handle(req.method ?: "GET", u.path ?: "/", q, req.requestHeaders ?: emptyMap(), null, "127.0.0.1", tcpPort()))
                } catch (e: Exception) {
                    WebResourceResponse("text/plain", "utf-8", 500, "Error", emptyMap(), ByteArrayInputStream((e.message ?: "error").toByteArray()))
                }
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && !failedShown) showFailure(view, error.description.toString())
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, r: WebResourceResponse) {
                if (request.isForMainFrame && !failedShown) showFailure(view, "HTTP ${r.statusCode} ${r.reasonPhrase} — ${request.url}")
            }
            override fun onPageFinished(view: WebView, url: String?) {
                if (failedShown || url == null || !url.startsWith(origin)) return
                view.postDelayed({
                    if (failedShown) return@postDelayed
                    view.evaluateJavascript(
                        "(function(){try{return window.__booted?'BOOT_OK':('BOOT_FAIL '+JSON.stringify(window.__err||[])+' len='+(document.body?document.body.innerText.length:-1))}catch(x){return 'JS_FAIL '+x}})()"
                    ) { r ->
                        if (r == null || !r.contains("BOOT_OK")) showFailure(view, "Page loaded but the UI did not start: " + (r ?: "no result"))
                    }
                }, 3500)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                if (customView != null) { cb.onCustomViewHidden(); return }
                customView = view; customCb = cb
                (window.decorView as ViewGroup).addView(view, ViewGroup.LayoutParams(-1, -1))
                WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            override fun onHideCustomView() { leaveFullscreen() }
        }
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) { leaveFullscreen(); return }
                web.evaluateJavascript("(window.auroraBack&&window.auroraBack())?'1':'0'") { r ->
                    if (r == null || !r.contains("1")) finish()
                }
            }
        })

        try { ensureTcp() } catch (e: Exception) { }
        web.loadUrl(origin + "/?k=" + theCore().key)
    }

    override fun onResume() {
        super.onResume()
        try { ensureTcp() } catch (e: Exception) { }
    }

    private fun leaveFullscreen() {
        customView?.let { (window.decorView as ViewGroup).removeView(it) }
        customView = null
        customCb?.onCustomViewHidden(); customCb = null
        WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    override fun onDestroy() { web.destroy(); super.onDestroy() }
}
