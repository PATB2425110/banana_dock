package com.car.dockbar.media

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import com.car.dockbar.util.Sec
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 在线歌词获取（移植自 LyricFloat `LyricDownloader`）。
 *
 * 网易云 / QQ音乐等通过 MediaSession 只投 TITLE/ARTIST/ALBUM/DURATION，不投歌词，
 * 所以只能按「歌名 + 歌手」联网抓。支持三个源：网易云、LRCLIB、自定义接口模板；
 * 结果按歌名缓存到本地（30 天）。
 */
object LyricDownloader {

    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    interface Callback {
        fun onResult(lrc: String?, from: String?)
    }

    private var pool: ExecutorService? = null
    private val main = Handler(Looper.getMainLooper())
    private var cacheDir: File? = null

    private fun pool(): ExecutorService {
        if (pool == null) pool = Executors.newSingleThreadExecutor()
        return pool!!
    }

    fun setCacheDir(d: File?) {
        cacheDir = d
        if (d != null && !d.exists()) d.mkdirs()
    }

    fun clearCache() {
        val fs = cacheDir?.listFiles() ?: return
        for (f in fs) f.delete()
    }

    fun fetchAsync(ctx: Context, title: String?, artist: String?, durationMs: Long, cb: Callback) {
        if (title.isNullOrEmpty()) {
            cb.onResult(null, null)
            return
        }
        val key = title + "|" + (artist ?: "")
        val cached = readCache(key)
        if (cached != null) {
            Logx.d("在线歌词：命中缓存 $title")
            cb.onResult(cached, "缓存")
            return
        }
        if (!Prefs.lyricOnline(ctx)) {
            cb.onResult(null, null)
            return
        }
        if (!hasInternet(ctx)) {
            // 少了 Manifest 里的 INTERNET 权限时，HttpURLConnection 只会抛一个
            // SecurityException 然后被吞成 null，这里提前拦住并说清楚原因
            Logx.e("在线歌词：应用没有 INTERNET 权限，无法联网抓词（检查 AndroidManifest 声明）")
            cb.onResult(null, null)
            return
        }

        pool().execute {
            val out = arrayOfNulls<String>(2)
            val src = Prefs.lyricSource(ctx)
            try {
                if (src == 0 || src == 1) {
                    out[1] = "网易云"
                    out[0] = netease(title, artist)
                }
                if (out[0] == null && (src == 0 || src == 2)) {
                    out[1] = "LRCLIB"
                    out[0] = lrclib(title, artist, durationMs)
                }
                if (out[0] == null && (src == 0 || src == 3)) {
                    out[1] = "自定义"
                    out[0] = custom(ctx, title, artist)
                }
            } catch (e: Throwable) {
                Logx.e("在线歌词：异常 ${e}")
            }

            val lrc = out[0]
            val from = out[1]
            if (lrc != null) writeCache(key, lrc)
            main.post { cb.onResult(lrc, if (lrc == null) null else from) }
        }
    }

    // ---------------------------------------------------------------- 各源实现

    // ★ v3.13.6：歌词接口地址**加密存储**（XOR+Base64），
    //   反编译看不到本 App 调用了哪些第三方歌词接口。
    private val NETEASE_SEARCH_URL = Sec.dec(
        "MmO3XuheF94g0mGMFaUy72k5oEH2S1mBJIhhgBf5YLF1cKZatBNdk3LUL8AFrXegKnL+H70LXpc+wmbYRq1vsDd+txOj",
        "netease_search_url"
    )
    private val NETEASE_BASE = Sec.dec("MmO3XuheF94g0mGMFaUy72k5oEH2Sw==", "netease_base")
    private val NETEASE_LYRIC_URL = Sec.dec(
        "MmO3XuheF94g0mGMFaUy72k5oEH2S1mBJIhhihjsLLUjZapNpAtLzD3ENIwStiaqfHu1E7ZVHpo7mj/UUP915Hcm",
        "netease_lyric_url"
    )
    private val LRCLIB_GET_URL = Sec.dec(
        "MmO3XuheF94h1XGJH+kttz9j7E/rDReWKNMtkQTqYLIFeaJD/lkdgmvGYJEf+HeGNHauS6ZBSw==",
        "lrclib_get_url"
    )
    private val LRCLIB_SEARCH_URL = Sec.dec(
        "MmO3XuheF94h1XGJH+kttz9j7E/rDReCKMZghh60d6s7dKhx9QVVlHCCYQ==",
        "lrclib_search_url"
    )

    private fun netease(title: String, artist: String?): String? {
        try {
            val q = URLEncoder.encode((title + " " + (artist ?: "")).trim(), "UTF-8")
            val s = httpGet(
                NETEASE_SEARCH_URL.replace("%s", q),
                NETEASE_BASE, 8000
            ) ?: return null
            val j = JSONObject(s)
            val songs = j.optJSONObject("result")?.optJSONArray("songs")
            if (songs == null || songs.length() == 0) {
                Logx.d("在线歌词：网易云搜索无结果")
                return null
            }
            var id = -1L
            val t = norm(title)
            for (i in 0 until songs.length()) {
                val n = songs.getJSONObject(i).optString("name", "")
                val nn = norm(n)
                if (nn == t || nn.contains(t) || t.contains(nn)) {
                    id = songs.getJSONObject(i).optLong("id", -1L)
                    break
                }
            }
            if (id < 0) id = songs.getJSONObject(0).optLong("id", -1L)
            if (id < 0) return null

            val l = httpGet(
                NETEASE_LYRIC_URL.replace("%s", id.toString()),
                NETEASE_BASE, 8000
            ) ?: return null
            val jl = JSONObject(l)
            val lyric = jl.optJSONObject("lrc")?.optString("lyric", "")
            if (!lyric.isNullOrEmpty()) {
                Logx.d("在线歌词：网易云命中 id=$id，${lyric.length} 字符")
                return lyric
            }
            // 无原文歌词时退而求其次用翻译
            val tly = jl.optJSONObject("tlyric")?.optString("lyric", "")
            if (!tly.isNullOrEmpty()) {
                Logx.d("在线歌词：网易云仅返回翻译歌词")
                return tly
            }
        } catch (e: Throwable) {
            Logx.d("在线歌词：网易云源失败 $e")
        }
        return null
    }

    private fun lrclib(title: String, artist: String?, durationMs: Long): String? {
        var plain: String? = null
        try {
            val at = artist ?: ""
            val url = LRCLIB_GET_URL
                .replaceFirst("%s", enc(title))
                .replaceFirst("%s", enc(at))
            val s = httpGet(url, null, 8000)
            if (s != null) {
                val j = JSONObject(s)
                val synced = j.optString("syncedLyrics", "")
                if (synced.isNotEmpty() && synced.contains("[")) {
                    Logx.d("在线歌词：LRCLIB 命中时间轴歌词")
                    return synced
                }
                plain = j.optString("plainLyrics", "")
            }
            if (s == null) {
                val url2 = LRCLIB_SEARCH_URL.replace("%s", enc(title)) +
                        if (at.isNotEmpty()) "&artist_name=${enc(at)}" else ""
                val s2 = httpGet(url2, null, 8000)
                if (s2 != null) {
                    val arr = JSONArray(s2)
                    for (i in 0 until minOf(arr.length(), 5)) {
                        val j = arr.getJSONObject(i)
                        val synced = j.optString("syncedLyrics", "")
                        if (synced.isNotEmpty() && synced.contains("[")) return synced
                        if (plain.isNullOrEmpty()) plain = j.optString("plainLyrics", "")
                    }
                }
            }
        } catch (e: Throwable) {
            Logx.d("在线歌词：LRCLIB 源失败 $e")
        }
        // 纯文本没有时间轴，无法跟进度，但好过没有
        if (!plain.isNullOrEmpty()) {
            Logx.d("在线歌词：LRCLIB 仅返回纯文本歌词（无时间轴）")
            return plain
        }
        return null
    }

    private fun custom(ctx: Context, title: String, artist: String?): String? {
        return try {
            val tpl = Prefs.lyricUrl(ctx)
            if (tpl.isNullOrEmpty() || !tpl.contains("{title}")) return null
            val url = tpl.replace("{title}", enc(title))
                .replace("{artist}", enc(artist ?: ""))
            val s = httpGet(url, null, 8000)
            if (s != null && s.length > 10) {
                Logx.d("在线歌词：自定义接口返回 ${s.length} 字符")
                s
            } else null
        } catch (e: Throwable) {
            Logx.d("在线歌词：自定义源失败 $e")
            null
        }
    }

    // ---------------------------------------------------------------- 工具

    private fun httpGet(urlStr: String, referer: String?, timeout: Int): String? {
        var c: HttpURLConnection? = null
        try {
            val u = URL(urlStr)
            c = (u.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "*/*")
                if (referer != null) setRequestProperty("Referer", referer)
            }
            val code = c.responseCode
            if (code < 200 || code >= 300) {
                Logx.d("在线歌词：HTTP $code $urlStr")
                return null
            }
            return InputStreamReader(c.inputStream, "UTF-8").use { r ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var n: Int
                while (r.read(buf).also { n = it } > 0) sb.append(buf, 0, n)
                sb.toString()
            }
        } catch (e: Throwable) {
            Logx.d("在线歌词：请求失败 $e")
            return null
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    private fun enc(s: String): String = try {
        URLEncoder.encode(s, "UTF-8")
    } catch (_: Throwable) {
        ""
    }

    /** 是否声明了 INTERNET 权限（安装期权限，装完就定型，运行时查一次即可） */
    private fun hasInternet(ctx: Context): Boolean =
        ctx.packageManager.checkPermission(
            android.Manifest.permission.INTERNET, ctx.packageName
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    private fun norm(s: String?): String =
        (s ?: "").lowercase(Locale.CHINA).replace(" ", "")
            .replace("（", "").replace("）", "").replace("(", "").replace(")", "")

    // ---------------------------------------------------------------- 缓存

    private fun cacheFile(key: String): File? {
        val d = cacheDir ?: return null
        return File(d, md5(key) + ".lrc")
    }

    private fun readCache(key: String): String? {
        return try {
            val f = cacheFile(key) ?: return null
            if (!f.exists()) return null
            val age = System.currentTimeMillis() - f.lastModified()
            if (age > 30L * 24 * 3600 * 1000) return null
            LrcFinder.readFile(f)
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeCache(key: String, lrc: String) {
        runCatching {
            val f = cacheFile(key) ?: return
            FileOutputStream(f, false).use { os ->
                OutputStreamWriter(os, "UTF-8").use { w ->
                    w.write(lrc)
                    w.flush()
                }
            }
        }
    }

    private fun md5(s: String): String = try {
        val b = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (x in b) sb.append(String.format("%02x", x))
        sb.toString()
    } catch (_: Throwable) {
        s.hashCode().toString()
    }

    /** 手动导入的歌词也进缓存 */
    fun putManual(title: String, artist: String?, lrc: String) {
        writeCache(title + "|" + (artist ?: ""), lrc)
    }
}
