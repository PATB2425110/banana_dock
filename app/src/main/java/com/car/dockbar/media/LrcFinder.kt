package com.car.dockbar.media

import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.car.dockbar.util.Logx
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * 兜底：广播里拿不到歌词时，到常用音乐目录里找同名 `.lrc` 文件。
 * （移植自 LyricFloat `LrcFinder`）
 */
object LrcFinder {

    private val DIRS = arrayOf(
        "Music", "Music/Lyric", "Music/Lyrics", "Kugou", "KuGou", "kgmusic",
        "qqmusic", "QQMusic", "netease/cloudmusic", "xiami", "KuwoMusic",
        "Android/data/com.kugou.android/files", "Android/data/com.tencent.qqmusic/files"
    )

    private val CACHE = HashMap<String, String>()
    private var thread: HandlerThread? = null
    private var worker: Handler? = null

    interface Callback {
        fun onFound(title: String, lrc: String?)
    }

    private fun ensureThread() {
        if (thread == null) {
            thread = HandlerThread("lrc-finder").also { it.start() }
            worker = Handler(thread!!.looper)
        }
    }

    fun findAsync(title: String?, artist: String?, cb: Callback) {
        if (title.isNullOrEmpty()) return
        val cached = CACHE[title]
        if (cached != null) {
            cb.onFound(title, cached)
            return
        }
        ensureThread()
        worker?.post {
            val lrc = find(title, artist)
            if (lrc != null) synchronized(CACHE) { CACHE[title] = lrc }
            Handler(Looper.getMainLooper()).post { cb.onFound(title, lrc) }
        }
    }

    /** 全盘扫描（限深度 3、限 4000 个文件，异步） */
    fun findDeepAsync(title: String?, cb: Callback) {
        if (title.isNullOrEmpty()) return
        ensureThread()
        worker?.post {
            val lrc = deepScan(title)
            if (lrc != null) synchronized(CACHE) { CACHE[title] = lrc }
            Handler(Looper.getMainLooper()).post { cb.onFound(title, lrc) }
        }
    }

    private fun deepScan(title: String): String? = try {
        if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            scan(Environment.getExternalStorageDirectory(), title, 0, IntArray(1), 3)
        } else null
    } catch (_: Throwable) {
        null
    }

    private fun find(title: String, artist: String?): String? {
        return try {
            if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
                val root = Environment.getExternalStorageDirectory()
                for (d in DIRS) {
                    val hit = scan(File(root, d), title, 0, IntArray(1), 2)
                    if (hit != null) return hit
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun scan(dir: File?, title: String, depth: Int, counter: IntArray, maxDepth: Int): String? {
        if (dir == null || !dir.exists() || !dir.isDirectory || depth > maxDepth) return null
        if (counter[0] > 4000) return null
        val fs = dir.listFiles() ?: return null
        val key = normalize(title)
        for (f in fs) {
            counter[0]++
            if (counter[0] > 4000) return null
            if (f.isDirectory) {
                val hit = scan(f, title, depth + 1, counter, maxDepth)
                if (hit != null) return hit
                continue
            }
            val n = f.name.lowercase()
            if (!n.endsWith(".lrc")) continue
            if (n.contains(key) || key.contains(stripExt(n))) {
                val txt = readFile(f)
                if (txt != null) {
                    Logx.d("LrcFinder：命中本地歌词文件 ${f.absolutePath}")
                    return txt
                }
            }
        }
        return null
    }

    private fun stripExt(name: String): String {
        val i = name.lastIndexOf('.')
        return if (i > 0) name.substring(0, i) else name
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(" ", "").replace("（", "").replace("）", "")
            .replace("(", "").replace(")", "")

    /** 读文件，UTF-8 失败自动退 GBK（老歌词文件常见） */
    fun readFile(f: File): String? = try {
        readWith(f, "UTF-8") ?: readWith(f, "GBK")
    } catch (_: Throwable) {
        null
    }

    private fun readWith(f: File, charset: String): String? = try {
        FileInputStream(f).use { ins ->
            InputStreamReader(ins, charset).use { r ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var n: Int
                while (r.read(buf).also { n = it } > 0) sb.append(buf, 0, n)
                sb.toString()
            }
        }
    } catch (_: Throwable) {
        null
    }
}
