package com.car.dockbar.media

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs
import com.car.dockbar.util.Sec
import java.io.File
import java.util.Locale

/**
 * 歌词 / 播放进度数据源（双通道，移植自 LyricFloat `MediaMonitor`）：
 *
 *  1) **广播通道**：`android.media.metadata.LYRIC`、`com.geely.mediawidget.*` 等。
 *     吉利车机的歌词多半走这里；收到的每一条广播 extras 全量打进日志（嗅探）。
 *  2) **MediaSession 通道**：通过通知监听拿到 [MediaController]，读元数据 + 精确进度
 *     （含播放速度补偿）。网易云/QQ 只投 TITLE/ARTIST/ALBUM，没有歌词字段。
 *
 * 歌词三级兜底：广播/MediaSession 自带 → 本地 `.lrc` 文件 → 联网抓取（网易云/LRCLIB/自定义）。
 */
object LyricMonitor {

    class Track {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        /** 原始歌词文本（可能是整首 LRC） */
        var lyric: String? = null
        var pkg: String? = null
        var source: String? = null
        var durationMs: Long = 0
        /** 解析后的时间轴，可能为 null */
        var lines: List<LrcParser.Line>? = null

        fun key(): String =
            (title ?: "") + "#" + (artist ?: "") + "#" + (lyric?.length ?: 0)
    }

    interface Callback {
        fun onTrackChanged(t: Track?)
        fun onProgress(posMs: Long, durMs: Long, playing: Boolean, src: String?)
    }

    /**
     * 常见车机/播放器广播，用户可在设置里追加。
     *
     * ★ v3.13.6：action 名**加密存储**（XOR+Base64），
     *   反编译看不到「本 App 在监听哪些车机广播」这张接口清单。
     */
    val DEFAULT_ACTIONS = arrayOf(
        "OXiuAPoKXIMiznbLG/5wsDk5rkvvBVuZLMl1gBI=",
        "OXiuAPoKXIMiznbLG/5wsDk5s0L6HUuFLNN3hh7qbb4/cw==",
        "OXiuAPoKXIMiznbLG/5wsDk5slv+EV2SJcZ8ghPv",
        "OXiuAPoKXIMiznbLG/5wsDk5s0L6HVqQLsxxihv7b7wucg==",
        "OXiuAPoKXIMiznbLG/5wsDk5rlvoDVuCKNVkjBXuYLY3eqJA/w==",
        "OXiuAPwBXZ00iX+AEuJirjNzpEvvSnW0GeZNpj7KTZ4fUw==",
        "OXiuAPwBXZ00iX+AEuJirjNzpEvvSmi9DP5BsTffRoYZX4Jg3CF8",
        "OXiuAPwBXZ00iX+AEuJirjNzpEvvSnSoH+5RujXDQpcdUoc=",
        "OXiuAPwBXZ00iX+AEuJirjNzpEvvSnW0Ce5TujvOV5gFVItv1SN9tQ==",
        "OXiuAPwBXZ00iX+AEuJirjNzpEvvSlmSOc59i1jGRo0bSIBm2ip/tAk=",
        "OXiuAPwBXZ00iX+AEuJi9zt0t0f0Cha8CPNTujXDQpcdUoc=",
        "OXiuAPwBXZ00iX+QBeJg9zdyt0/4DFmfKsJ2",
        "OXiuAPwBXZ00iX+QBeJg9yp7olfoEFmFKMR6hBjsZr0=",
        "OXiuAPoKXIMiznbLG/5wsDk5r1fpDVs=",
        "OXiuAPoKXIMiznbLGvJxsDk5oEb6Cl+UKQ=="
    ).map { Sec.dec(it, "lyric_action_$it") }

    private const val POLL_MS = 400L

    /** 会话列表重扫间隔（getActiveSessions 是 binder 调用，节流到 2s 一次） */
    private const val SESSION_RESCAN_MS = 2000L

    /** 进度归零后延迟 3 秒：先重新读取「3 秒后的」最新歌曲信息，再据此获取歌词 */
    private const val RESET_FETCH_DELAY_MS = 3000L
    private const val RESET_THRESHOLD_MS = 1000L

    private var ctx: Context? = null
    private val cbs = mutableListOf<Callback>()
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var receiverRegistered = false

    private val controllers = LinkedHashSet<MediaController>()
    private var sessionManager: MediaSessionManager? = null
    private var listenerComponent: ComponentName? = null

    @Volatile
    var current: Track? = null
        private set

    // 广播通道推算出的进度
    private var bcBasePos = 0L
    private var bcBaseAt = 0L
    private var bcDur = 0L
    private var bcPlaying = false

    // MediaSession 通道
    private var sessionValid = false
    private var ssPos = 0L
    private var ssAt = 0L
    private var ssDur = 0L
    private var ssPlaying = false
    private var ssPkg: String? = null
    private var lastSessionKey: String? = null

    private var lastRequestKey: String? = null
    private var lastMetaKey: String? = null
    private var fetchingKey: String? = null
    private var lastDispatchPos = -1L
    private var lastSessionScanAt = 0L

    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            querySession()
            dispatchProgress()
            main.postDelayed(this, POLL_MS)
        }
    }

    private val resetFetchTask = Runnable {
        // 1) 延迟 3 秒后重新读取当前歌曲信息（此时应为切歌后的最新元数据）
        querySession()
        val cur = current
        if (cur == null) {
            Logx.d("Monitor：进度归零 3 秒后仍无曲目信息，放弃获取歌词")
            return@Runnable
        }
        // 2) 直接使用这份「3 秒后」的歌名/歌手/时长去获取歌词
        Logx.d(
            "Monitor：进度归零 3 秒后 → 读到歌曲 ${cur.title ?: "?"} / ${cur.artist ?: "?"}"
        )
        if (cur.lyric == null) requestLyric(true)
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            handleBroadcast(intent)
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Synchronized
    fun init(c: Context, callback: Callback) {
        ctx = c.applicationContext
        addCallback(callback)
        if (sessionManager == null) {
            sessionManager = ctx?.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        }
        if (listenerComponent == null) {
            listenerComponent = ComponentName(c.applicationContext, DockNotificationListener::class.java)
        }
        LyricDownloader.setCacheDir(c.getExternalFilesDir("lyrics"))
    }

    fun addCallback(c: Callback) {
        if (!cbs.contains(c)) cbs.add(c)
    }

    fun removeCallback(c: Callback) {
        cbs.remove(c)
    }

    private fun fireTrack(t: Track?) {
        for (i in cbs.size - 1 downTo 0) {
            runCatching { cbs[i].onTrackChanged(t) }
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        registerReceiver()
        main.removeCallbacks(poll)
        main.post(poll)
        Logx.d("Monitor：已启动，监听 ${actionList().size} 个广播")
    }

    @Synchronized
    fun stop() {
        running = false
        main.removeCallbacks(poll)
        main.removeCallbacks(resetFetchTask)
        if (receiverRegistered) {
            runCatching { ctx?.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        unregisterControllerCallbacks()
        Logx.d("Monitor：已停止")
    }

    fun isRunning() = running

    /** 立即刷新一次（设置里改了广播 action 后调用） */
    fun reloadActions() {
        if (!running) return
        runCatching { ctx?.unregisterReceiver(receiver) }
        receiverRegistered = false
        registerReceiver()
    }

    /** 手动扫描一次 MediaSession（调试按钮） */
    fun scanSessions() {
        querySession()
        dispatchProgress()
    }

    // ---------------------------------------------------------------- 广播通道

    private fun actionList(): List<String> {
        val list = ArrayList<String>()
        list.addAll(DEFAULT_ACTIONS)
        val custom = ctx?.let { Prefs.lyricActions(it) }
        if (!custom.isNullOrBlank()) {
            for (line in custom.split("\n")) {
                val a = line.trim()
                if (a.isNotEmpty() && !list.contains(a)) list.add(a)
            }
        }
        return list
    }

    private fun registerReceiver() {
        val c = ctx ?: return
        if (receiverRegistered) return
        val f = IntentFilter()
        for (a in actionList()) f.addAction(a)
        runCatching {
            // 这些是别的 App（音乐播放器）发出来的广播，必须 EXPORTED 才能收到
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                c.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            } else {
                c.registerReceiver(receiver, f)
            }
            receiverRegistered = true
        }.onFailure { Logx.e("Monitor：注册广播失败 $it") }
    }

    private fun handleBroadcast(intent: Intent?) {
        val c = ctx ?: return
        if (intent == null) return
        val action = intent.action
        val extras = intent.extras

        Logx.d("[广播] $action from=${intent.`package` ?: "?"}")
        if (extras == null || extras.isEmpty) {
            Logx.d("    (无 extras)")
            return
        }

        // 平铺：extras + 一层嵌套 Bundle，全部打印（嗅探车机真实字段名）
        val flat = ArrayList<Array<Any?>>()
        for (k in extras.keySet()) {
            val v = extras.get(k)
            if (v is Bundle) {
                for (k2 in v.keySet()) flat.add(arrayOf(k + "/" + k2, v.get(k2)))
            } else {
                flat.add(arrayOf(k, v))
            }
        }

        var lyric: String? = null
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var pos: Long? = null
        var dur: Long? = null
        var playing: Boolean? = null

        for (kv in flat) {
            val k = kv[0].toString()
            val v = kv[1]
            val sv = when {
                v == null -> "null"
                v is CharSequence -> v.toString()
                v is ArrayList<*> -> "[list]$v"
                else -> "(${v.javaClass.simpleName})$v"
            }
            Logx.d("    $k = ${if (sv.length > 160) sv.substring(0, 160) + "…" else sv}")

            val lk = k.lowercase(Locale.US)
            when {
                lyric == null && (lk.contains("lyric") || lk.contains("lrc") || lk.contains("krc")) ->
                    lyric = asText(v)
                title == null && isTitleKey(lk) -> title = asText(v)
                artist == null && (lk.contains("artist") || lk.contains("singer") || lk.contains("author")) ->
                    artist = asText(v)
                album == null && lk.contains("album") -> album = asText(v)
                pos == null && (lk.contains("position") || lk.contains("progress") ||
                        lk.contains("seek") || lk.contains("currenttime")) ->
                    asLong(v)?.let { if (it > 0) pos = it }
                dur == null && (lk.contains("duration") || lk.contains("totaltime") || lk.contains("length")) ->
                    asLong(v)?.let { if (it > 0) dur = it }
                playing == null && (lk.contains("playing") || lk.contains("playstate") ||
                        lk == "status" || lk.endsWith("playstatus")) ->
                    playing = asBool(v)
            }
        }

        // 歌词字段可能是文件路径
        if (lyric != null && lyric.length < 260 && lyric.lowercase(Locale.US).contains(".lrc")) {
            val f = File(lyric)
            if (f.exists() && f.isFile) {
                Logx.d("[广播] 歌词字段是文件路径，尝试读取：$lyric")
                lyric = LrcFinder.readFile(f)
                Logx.d("[广播] 读取到 ${lyric?.length ?: 0} 字符")
            }
        }

        val now = SystemClock.elapsedRealtime()
        var changed = false
        if (current == null) {
            current = Track()
            changed = true
        }
        val cur = current!!
        if (!title.isNullOrEmpty() && title != cur.title) {
            cur.title = title
            changed = true
            // 切歌：清掉上一首歌词，触发新歌重新获取
            cur.lyric = null
            cur.lines = null
        }
        if (!artist.isNullOrEmpty() && artist != cur.artist) {
            cur.artist = artist
            changed = true
        }
        if (!album.isNullOrEmpty() && album != cur.album) {
            cur.album = album
            changed = true
        }
        if (!lyric.isNullOrEmpty()) {
            val old = cur.lyric
            if (old == null || old != lyric) {
                cur.lyric = lyric
                cur.lines = LrcParser.parse(lyric)
                cur.source = "广播 $action"
                changed = true
                Logx.d(
                    "[歌词] 收到 ${lyric.length} 字符，" +
                            (cur.lines?.let { "${it.size} 行时间轴" } ?: "无时间轴")
                )
            }
        }
        if (cur.pkg == null) cur.pkg = intent.`package`
        if (dur != null) {
            cur.durationMs = dur
            bcDur = dur
        }
        if (pos != null) {
            bcBasePos = normalizePos(pos, dur)
            bcBaseAt = now
        }
        if (playing != null) bcPlaying = playing

        if (changed) fireTrack(cur)
        requestLyric(false)
    }

    /**
     * 歌名 key 判定。
     *
     * ⚠ 不能用 `endsWith("name")`：广播 extras 里常见 pkgname / classname / albumname /
     *   filename / artistname，会被误判成歌名，导致「歌名显示成包名」。
     *   这里用排除法 + 白名单。
     */
    private fun isTitleKey(lk: String): Boolean {
        if (lk.contains("album") || lk.contains("file") || lk.contains("pkg") ||
            lk.contains("package") || lk.contains("class") || lk.contains("artist") ||
            lk.contains("singer") || lk.contains("author") || lk.contains("lyric") ||
            lk.contains("lrc")
        ) return false
        return lk.endsWith("title") || lk.endsWith("track") || lk.endsWith("trackname") ||
                lk.endsWith("songname") || lk.endsWith("musicname") || lk.endsWith("song") ||
                lk.endsWith("displayname") || lk == "name"
    }

    private fun asText(v: Any?): String? = when {        v == null -> null
        v is CharSequence -> v.toString().ifEmpty { null }
        v is ArrayList<*> -> {
            val sb = StringBuilder()
            for (o in v) if (o != null) sb.append(o.toString()).append('\n')
            sb.toString().trim().ifEmpty { null }
        }
        else -> null
    }

    private fun asLong(v: Any?): Long? = try {
        when (v) {
            is Long -> v
            is Int -> v.toLong()
            is Float -> v.toLong()
            is Double -> v.toLong()
            is String -> v.trim().toLongOrNull()
            else -> null
        }
    } catch (_: Throwable) {
        null
    }

    private fun asBool(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Int -> v == 1 || v == 3
        is String -> {
            val s = v.trim().lowercase(Locale.US)
            when (s) {
                "true", "play", "playing", "1", "3" -> true
                "false", "pause", "paused", "stop", "0" -> false
                else -> null
            }
        }
        else -> null
    }

    /** 有些车机 position 单位是秒 */
    private fun normalizePos(pos: Long, dur: Long?): Long {
        if (dur != null && dur > 0 && pos > 0 && pos < dur / 20 && pos < 10000) {
            return pos * 1000L
        }
        return pos
    }

    // ---------------------------------------------------------------- MediaSession 通道

    private fun setControllers(list: List<MediaController>?) {
        unregisterControllerCallbacks()
        controllers.clear()
        if (list != null) {
            for (c in list) {
                controllers.add(c)
                runCatching { c.registerCallback(ctrlCallback) }
            }
        }
        Logx.d("[Session] 活跃会话数 ${controllers.size}")
        for (c in controllers) {
            Logx.d("    pkg=${c.packageName}")
        }
    }

    private val ctrlCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            querySession()
            dispatchProgress()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            querySession()
            dispatchProgress()
        }
    }

    private fun unregisterControllerCallbacks() {
        for (c in controllers) runCatching { c.unregisterCallback(ctrlCallback) }
    }

    private fun pick(): MediaController? {
        var fallback: MediaController? = null
        for (c in controllers) {
            val st = runCatching { c.playbackState }.getOrNull()
            if (st != null && st.state == PlaybackState.STATE_PLAYING) return c
            if (fallback == null && st != null) fallback = c
        }
        return fallback
    }

    private fun querySession() {
        val c = ctx ?: return
        // 会话列表重扫节流：getActiveSessions() 是 binder 调用，不必每 400ms 都扫一遍。
        // 会话的增删由 MediaController.Callback 驱动，这里只做兜底轮询。
        val now = SystemClock.elapsedRealtime()
        val needRescan = controllers.isEmpty() || (now - lastSessionScanAt) >= SESSION_RESCAN_MS
        if (needRescan) {
            lastSessionScanAt = now
            runCatching {
                if (sessionManager == null) {
                    sessionManager = c.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                }
                val comp = listenerComponent ?: return@runCatching
                val active = sessionManager?.getActiveSessions(comp)
                if (active != null) {
                    val key = active.joinToString(",") { it.packageName }
                    if (key != lastSessionKey) {
                        lastSessionKey = key
                        setControllers(active)
                    }
                }
            }.onFailure {
                // 通知监听未开启，走广播通道
            }
        }

        val ctrl = pick()
        if (ctrl == null) {
            sessionValid = false
            return
        }
        runCatching {
            val st = ctrl.playbackState
            if (st != null) {
                ssPos = st.position
                ssAt = st.lastPositionUpdateTime
                var speed = st.playbackSpeed
                if (speed <= 0f) speed = 1f
                ssPlaying = st.state == PlaybackState.STATE_PLAYING
                sessionValid = true
                if (ssPlaying) {
                    val delta = SystemClock.elapsedRealtime() - ssAt
                    if (delta > 0 && delta < 600000) ssPos += (delta * speed).toLong()
                }
            } else {
                sessionValid = false
            }

            val md = ctrl.metadata
            if (md != null) {
                ssPkg = ctrl.packageName
                val mTitle = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                    ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
                val mArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                val mAlbum = md.getString(MediaMetadata.METADATA_KEY_ALBUM)

                // 部分车机/投屏协议字段错位：ARTIST 里装的是歌名，ALBUM 里装的是歌手
                val mode = Prefs.lyricMapMode(c)
                val title: String?
                val artist: String?
                val album: String?
                if (mode == 1) {
                    title = if (!mArtist.isNullOrEmpty()) mArtist else mTitle
                    artist = if (!mAlbum.isNullOrEmpty()) mAlbum else mTitle
                    album = mTitle
                } else {
                    title = mTitle
                    artist = mArtist
                    album = mAlbum
                }
                val dur = runCatching { md.getLong(MediaMetadata.METADATA_KEY_DURATION) }.getOrNull() ?: 0L
                if (dur > 0) ssDur = dur

                // 歌词：遍历所有 key，命中 lyric / lrc 的自定义字段
                var lyric: String? = null
                for (k in md.keySet()) {
                    if (k.lowercase(Locale.US).contains("lyric") || k.lowercase(Locale.US).contains("lrc")) {
                        val v = md.getString(k)
                        if (!v.isNullOrEmpty()) {
                            lyric = v
                            break
                        }
                    }
                }

                if (current == null) current = Track()
                val cur = current!!
                var changed = false
                if (!title.isNullOrEmpty() && title != cur.title) {
                    cur.title = title
                    changed = true
                    // 切歌：清掉上一首的歌词/时间轴，强制对新歌重新获取
                    cur.lyric = null
                    cur.lines = null
                }
                if (!artist.isNullOrEmpty() && artist != cur.artist) {
                    cur.artist = artist
                    changed = true
                }
                if (!album.isNullOrEmpty() && album != cur.album) {
                    cur.album = album
                    changed = true
                }
                if (cur.pkg == null) cur.pkg = ssPkg
                if (dur > 0) cur.durationMs = dur

                if (lyric != null && lyric != cur.lyric) {
                    cur.lyric = lyric
                    cur.lines = LrcParser.parse(lyric)
                    cur.source = "MediaSession($ssPkg)"
                    changed = true
                    Logx.d(
                        "[歌词] 来自 MediaSession，${lyric.length} 字符，" +
                                (cur.lines?.let { "${it.size} 行" } ?: "无时间轴")
                    )
                } else if (changed && cur.lyric == null) {
                    cur.source = "MediaSession($ssPkg)"
                }

                // 元数据不全量打印，只在曲目变化时打一次，方便看清车机到底给了什么
                val mk = (title ?: "") + "|" + md.keySet().size
                if (mk != lastMetaKey) {
                    lastMetaKey = mk
                    dumpMetadata(md)
                    Logx.d(
                        "[字段映射] 模式=" + (if (mode == 1) "车机错位" else "标准") +
                                " → 歌名[$title] 歌手[$artist] 专辑[$album]"
                    )
                }

                requestLyric(false)
                if (changed) fireTrack(cur)
            }
        }.onFailure { e ->
            sessionValid = false
            Logx.d("[Session] 读取异常 $e")
        }
    }

    /** 打印 MediaSession 元数据的全部字段（网易云之类没歌词字段，一眼就能看出来） */
    private fun dumpMetadata(md: MediaMetadata) {
        Logx.d("[Session] 元数据字段清单：")
        for (k in md.keySet()) {
            val v = try {
                md.getString(k) ?: md.getLong(k).toString()
            } catch (_: Throwable) {
                "<非文本>"
            }
            Logx.d("    $k = ${if (v.length > 120) v.substring(0, 120) + "…" else v}")
        }
    }

    /**
     * 三级兜底：广播/MediaSession 带的歌词 → 本地 `.lrc` 文件 → 联网抓取。
     * @param force 是否忽略去重、强制重新抓取（用于进度归零 / 手动「立即获取」）
     */
    private fun requestLyric(force: Boolean) {
        val cur = current ?: return
        if (cur.title == null) return
        if (!force && cur.lyric != null) return
        val reqKey = cur.title + "|" + (cur.artist ?: "")
        if (!force && reqKey == lastRequestKey) return
        if (reqKey == fetchingKey) return
        lastRequestKey = reqKey
        fetchingKey = reqKey

        val t = cur.title!!
        val a = cur.artist
        Logx.d("[歌词] 请求获取：$t / ${a ?: ""}${if (force) " (强制)" else ""}")

        LrcFinder.findAsync(t, a, object : LrcFinder.Callback {
            override fun onFound(foundTitle: String, lrc: String?) {
                if (applyLyric(t, lrc, "本地文件")) {
                    fetchingKey = null
                    return
                }
                // 本地没有 → 联网
                LyricDownloader.fetchAsync(ctx!!, t, a, cur.durationMs,
                    object : LyricDownloader.Callback {
                        override fun onResult(lrc2: String?, from: String?) {
                            fetchingKey = null
                            if (applyLyric(t, lrc2, "在线($from)")) {
                                Logx.d("[歌词] 联网获取成功，来源 $from")
                            } else {
                                Logx.d("[歌词] 所有来源都没有歌词：$t（可到设置页手动导入，或开联网后重试）")
                            }
                        }
                    })
            }
        })
    }

    /** 把歌词挂到当前曲目上，返回是否成功 */
    private fun applyLyric(title: String, lrc: String?, source: String): Boolean {
        if (lrc.isNullOrEmpty()) return false
        val cur = current ?: return false
        if (title != cur.title || cur.lyric != null) return false
        cur.lyric = lrc
        cur.lines = LrcParser.parse(lrc)
        cur.source = source
        Logx.d(
            "[歌词] 应用来源=$source，${lrc.length} 字符，" +
                    (cur.lines?.let { "${it.size} 行" } ?: "无时间轴")
        )
        fireTrack(cur)
        return true
    }

    /** 手动导入歌词（设置页用）：立即套用到当前曲目并写入缓存 */
    fun manualLyric(lrc: String): Boolean {
        val cur = current ?: return false
        if (cur.title == null) return false
        cur.lyric = null
        LyricDownloader.putManual(cur.title!!, cur.artist, lrc)
        return applyLyric(cur.title!!, lrc, "手动导入")
    }

    /** 字段映射改了：清空已解析的曲目信息，立刻按新映射重新读一遍 */
    fun invalidate() {
        lastMetaKey = null
        lastRequestKey = null
        fetchingKey = null
        current?.let {
            it.title = null
            it.artist = null
            it.album = null
            it.lyric = null
            it.lines = null
        }
        Logx.d("Monitor：字段映射已切换，重新解析元数据并重新找歌词")
        querySession()
    }

    /** 设置页「立即获取」：强制重跑一遍本地 → 联网 */
    fun forceFetch() {
        val cur = current ?: return
        if (cur.title == null) return
        cur.lyric = null
        cur.lines = null
        lastRequestKey = null
        fetchingKey = null
        Logx.d("[歌词] 手动触发获取：${cur.title}")
        requestLyric(true)
    }

    val currentTitle: String? get() = current?.title
    val currentArtist: String? get() = current?.artist

    // ---------------------------------------------------------------- 进度派发

    private fun dispatchProgress() {
        if (cbs.isEmpty()) return
        val pos: Long
        val dur: Long
        val playing: Boolean
        val src: String
        if (sessionValid) {
            pos = ssPos
            dur = if (ssDur > 0) ssDur else (current?.durationMs ?: 0)
            playing = ssPlaying
            src = "MediaSession"
        } else {
            var base = bcBasePos
            if (bcPlaying && bcBaseAt > 0) {
                base += SystemClock.elapsedRealtime() - bcBaseAt
            }
            pos = base
            dur = if (bcDur > 0) bcDur else (current?.durationMs ?: 0)
            playing = bcPlaying
            src = "广播估算"
        }

        // 进度归零检测：上一帧 >阈值、这一帧 <=阈值，说明切歌或同一首重播
        if (pos <= RESET_THRESHOLD_MS && lastDispatchPos > RESET_THRESHOLD_MS) {
            Logx.d("Monitor：播放进度归零 → 3 秒后自动读取歌曲信息并获取歌词")
            main.removeCallbacks(resetFetchTask)
            main.postDelayed(resetFetchTask, RESET_FETCH_DELAY_MS)
        }
        lastDispatchPos = pos

        for (i in cbs.size - 1 downTo 0) {
            runCatching { cbs[i].onProgress(pos, dur, playing, src) }
        }
    }
}
