package com.myiptv.player

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit

/** نتيجة معالجة طلب (مستقلة عن أي خادم) */
class Resp(
    val code: Int,
    val mime: String,
    val stream: InputStream?,
    val length: Long = -1,
    val headers: Map<String, String> = emptyMap(),
    val reason: String = "OK"
)

/**
 * قلب التطبيق: يخدم الواجهة وقائمة السيرفرات وتمرير البث.
 * تستخدمه نافذة التطبيق مباشرة (بدون شبكة) ويستخدمه الخادم المحلي لصفحة المتصفح.
 */
class Core(private val ctx: Context) {
    private val ua = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3"
    private val client = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val file = File(ctx.filesDir, "data.json")
    private var db = JSONObject()
    private val fails = HashMap<String, LongArray>()
    val key: String
    val id: String

    init {
        try { db = JSONObject(file.readText()) } catch (e: Exception) { }
        val rnd = SecureRandom()
        if (!db.has("key")) db.put("key", (100000 + rnd.nextInt(900000)).toString())
        if (!db.has("id")) db.put("id", (listOf("02") + List(5) { "%02X".format(rnd.nextInt(256)) }).joinToString(":"))
        if (!db.has("playlists")) db.put("playlists", JSONArray())
        key = db.getString("key")
        id = db.getString("id")
        save()
    }

    @Synchronized private fun save() { file.writeText(db.toString()) }
    private fun norm(s: String) = s.lowercase().filter { it in '0'..'9' || it in 'a'..'f' }
    private fun isLocal(ip: String) = ip.isEmpty() || ip.startsWith("127.") || ip == "::1" || ip == "0:0:0:0:0:0:0:1"

    @Synchronized fun blocked(ip: String): Boolean {
        if (isLocal(ip)) return false
        val f = fails[ip] ?: return false
        return f[0] >= 15 && System.currentTimeMillis() - f[1] < 600000
    }

    @Synchronized private fun fail(ip: String) {
        val now = System.currentTimeMillis()
        val f = fails[ip]
        if (f == null || now - f[1] > 600000) fails[ip] = longArrayOf(1, now) else f[0]++
    }

    private fun lan(port: Int): List<String> {
        if (port <= 0) return emptyList()
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).filter { it.isUp && !it.isLoopback }
                .flatMap { Collections.list(it.inetAddresses) }.filter { it is Inet4Address && !it.isLoopbackAddress }
                .map { "http://${it.hostAddress}:$port" }
        } catch (e: Exception) { emptyList() }
    }

    private fun text(code: Int, s: String, mime: String = "text/plain; charset=utf-8"): Resp {
        val b = s.toByteArray(Charsets.UTF_8)
        return Resp(code, mime, ByteArrayInputStream(b), b.size.toLong())
    }
    private fun json(code: Int, s: String) = text(code, s, "application/json; charset=utf-8")
    private fun err(code: Int, msg: String) = text(code,
        "<html><body style='background:#060914;color:#fff;font-family:sans-serif;padding:24px'><h2>Aurora IPTV</h2><p>$code — $msg</p></body></html>",
        "text/html; charset=utf-8")

    private fun asset(path: String): Resp = try {
        val mime = if (path.endsWith(".html")) "text/html; charset=utf-8" else if (path.endsWith(".js")) "text/javascript" else "application/octet-stream"
        Resp(200, mime, ctx.assets.open(path), -1, mapOf("Cache-Control" to "no-store"))
    } catch (e: Exception) { err(404, "not found") }

    private fun parse(b: String?): JSONObject? = try { if (b == null) null else JSONObject(b) } catch (e: Exception) { null }

    private fun clean(b: JSONObject): JSONObject? {
        val type = b.optString("type")
        val d = b.optJSONObject("data") ?: return null
        if (type !in listOf("xtream", "m3u", "mac")) return null
        val data = JSONObject()
        for (k in d.keys().asSequence().take(8)) data.put(k, d.optString(k).take(600))
        return JSONObject().put("name", b.optString("name").trim().take(80).ifEmpty { "Playlist" }).put("type", type).put("data", data)
    }

    fun handle(method: String, p: String, q: Map<String, String>, hdr: Map<String, String>, body: String?, ip: String, port: Int): Resp {
        val ok = q["k"] == key
        if (p == "/" || p == "/index.html") {
            if (ok) return asset("web/index.html")
            return Resp(302, "text/plain", null, 0, mapOf("Location" to "/manage"), "Found")
        }
        if (p == "/manage") return asset("web/manage.html")
        if (p.startsWith("/lib/")) return asset("web/lib/" + p.substringAfterLast('/'))
        if (p == "/api/login" && method == "POST") {
            val b = parse(body)
            if (b != null && norm(b.optString("mac")) == norm(id) && b.optString("key") == key) return json(200, "{\"key\":\"$key\"}")
            fail(ip); return json(401, "{\"error\":\"invalid\"}")
        }
        if (!ok) { fail(ip); return err(401, "invalid key") }
        if (p == "/api/info") {
            val urls = JSONArray(); lan(port).forEach { urls.put(it) }
            return json(200, JSONObject().put("id", id).put("key", key).put("port", port).put("urls", urls).toString())
        }
        if (p == "/api/playlists") return api(method, q, body)
        if (p == "/proxy") return proxy(q, hdr)
        return err(404, "not found")
    }

    @Synchronized private fun api(method: String, q: Map<String, String>, body: String?): Resp {
        val arr = db.getJSONArray("playlists")
        return when (method.uppercase()) {
            "GET" -> json(200, arr.toString())
            "POST" -> {
                val c = parse(body)?.let { clean(it) } ?: return json(400, "{\"error\":\"bad\"}")
                val pid = q["id"]
                if (pid != null) {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("id") == pid) {
                            o.put("name", c.get("name")).put("type", c.get("type")).put("data", c.get("data")); save()
                            return json(200, o.toString())
                        }
                    }
                    return json(404, "{\"error\":\"not found\"}")
                }
                val item = JSONObject().put("id", UUID.randomUUID().toString()).put("name", c.get("name")).put("type", c.get("type")).put("data", c.get("data"))
                arr.put(item); save()
                json(200, item.toString())
            }
            "DELETE" -> {
                val pid = q["id"]
                val n = JSONArray()
                for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") != pid) n.put(arr.get(i))
                db.put("playlists", n); save()
                json(200, "{\"ok\":true}")
            }
            else -> json(405, "{}")
        }
    }

    private fun px(u: String) = "/proxy?u=" + URLEncoder.encode(u, "UTF-8") + "&k=" + key

    private fun rewrite(text: String, base: String): String {
        val baseUrl = base.toHttpUrl()
        fun abs(x: String) = baseUrl.resolve(x)?.let { px(it.toString()) } ?: x
        val uriRe = Regex("URI=\"([^\"]+)\"")
        return text.lines().joinToString("\n") { l ->
            val t = l.trim()
            when {
                t.isEmpty() -> l
                !t.startsWith("#") -> abs(t)
                else -> uriRe.replace(l) { "URI=\"" + abs(it.groupValues[1]) + "\"" }
            }
        }
    }

    private fun proxy(q: Map<String, String>, hdr: Map<String, String>): Resp {
        val target = q["u"]
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) return text(400, "bad url")
        return try {
            val b = Request.Builder().url(target).header("User-Agent", ua).header("X-User-Agent", "Model: MAG250; Link: WiFi")
            hdr.entries.firstOrNull { it.key.equals("range", true) }?.let { b.header("Range", it.value) }
            q["mac"]?.let { mac ->
                b.header("Cookie", "mac=$mac; stb_lang=en; timezone=Europe/Istanbul")
                q["tk"]?.takeIf { it.isNotEmpty() }?.let { b.header("Authorization", "Bearer $it") }
            }
            val r = client.newCall(b.build()).execute()
            val rb = r.body ?: return text(502, "empty response")
            val ct = r.header("Content-Type") ?: "application/octet-stream"
            val mime = ct.substringBefore(";").trim()
            val reason = r.message.ifEmpty { "OK" }
            val isM3u8 = ct.contains("mpegurl", true) || r.request.url.encodedPath.endsWith(".m3u8", true)
            if (isM3u8) {
                val raw = rb.string()
                val has = raw.contains("#EXTM3U")
                val out = if (has) rewrite(raw, r.request.url.toString()) else raw
                r.close()
                val bytes = out.toByteArray(Charsets.UTF_8)
                Resp(r.code, if (has) "application/vnd.apple.mpegurl" else mime, ByteArrayInputStream(bytes), bytes.size.toLong(), mapOf("Cache-Control" to "no-store"), reason)
            } else {
                val stream = object : FilterInputStream(rb.byteStream()) {
                    override fun close() { try { super.close() } finally { r.close() } }
                }
                val h = HashMap<String, String>()
                r.header("Content-Range")?.let { h["Content-Range"] = it }
                h["Accept-Ranges"] = r.header("Accept-Ranges") ?: "bytes"
                Resp(r.code, mime, stream, r.header("Content-Length")?.toLongOrNull() ?: -1L, h, reason)
            }
        } catch (e: Exception) {
            text(502, "proxy error: ${e.message}")
        }
    }
}

/** خادم HTTP محلي اختياري لصفحة الإدارة في المتصفح (شبكة الواي فاي) */
class LocalServer(private val core: Core, port: Int) : NanoHTTPD("0.0.0.0", port) {
    private val hostRe = Regex("^(localhost|(\\d{1,3}\\.){3}\\d{1,3}|\\[[0-9a-f:]+\\])$", RegexOption.IGNORE_CASE)

    private fun status(code: Int, desc: String) = object : Response.IStatus {
        override fun getDescription() = "$code $desc"
        override fun getRequestStatus() = code
    }

    private fun plain(code: Int, msg: String): Response =
        NanoHTTPD.newFixedLengthResponse(status(code, msg), "text/plain; charset=utf-8", msg)

    override fun serve(s: IHTTPSession): Response {
        val ip = s.remoteIpAddress ?: ""
        val host = (s.headers["host"] ?: "").replace(Regex(":\\d+$"), "")
        // يُقبل عنوان IP أو localhost فقط (حماية من DNS rebinding)
        if (!hostRe.matches(host)) return plain(403, "forbidden")
        if (core.blocked(ip)) return plain(429, "too many attempts")
        var body: String? = null
        if (s.method == Method.POST) {
            val f = HashMap<String, String>()
            try { s.parseBody(f); body = f["postData"] } catch (e: Exception) { }
        }
        val q = HashMap<String, String>()
        for ((k, v) in s.parameters) q[k] = v.firstOrNull() ?: ""
        val r = core.handle(s.method.name, s.uri, q, s.headers, body, ip, listeningPort)
        val st = status(r.code, r.reason)
        val stream = r.stream
        val resp = when {
            stream == null -> NanoHTTPD.newFixedLengthResponse(st, r.mime, "")
            r.length >= 0 -> NanoHTTPD.newFixedLengthResponse(st, r.mime, stream, r.length)
            else -> NanoHTTPD.newChunkedResponse(st, r.mime, stream)
        }
        for ((k, v) in r.headers) resp.addHeader(k, v)
        return resp
    }
}
