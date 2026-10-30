package com.car.dockbar.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import com.car.dockbar.util.Logx

/**
 * 系统媒体信息读取（MediaSession）。
 *
 * 需要「通知使用权」：设置 → 应用 → 特殊权限 → 通知使用权 → 打开 CarDockBar。
 */
object MediaBridge {

    data class MediaInfo(
        val hasSession: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val art: Bitmap? = null,
        val isPlaying: Boolean = false
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(MediaInfo) -> Unit>()

    @Volatile
    var info: MediaInfo = MediaInfo()
        private set

    private var controller: MediaController? = null
    private var manager: MediaSessionManager? = null
    private var component: ComponentName? = null
    /** 解码专辑图 URI 时用（content:// 需要 ContentResolver） */
    private var appCtx: Context? = null
    private var started = false

    /**
     * CS11 的 QQ 音乐壳。它不进 [getActiveSessions]，歌名在自己的媒体通知里。
     * 只有标准会话没有真实歌名时才用这条，避免盖住汽水 / 网易云。
     */
    private const val FLYME_MUSIC = "com.flyme.auto.music"
    private var noteTitle = ""
    private var noteArtist = ""
    private var noteArt: Bitmap? = null
    private var noteKey = ""
    private var usingNote = false
    private var noteKeysLogged = false
    private var serviceNoticeLogged = false
    private var imagesLogged = false
    private var bundleLoggedFor = ""
    private var notifArtLoggedFor = ""
    private var centerActive = false
    private var centerTitle = ""
    private var centerArtist = ""
    private var centerAlbum = ""
    private var centerArt: Bitmap? = null
    private var centerPlaying = false
    private var centerLabel = ""

    private val sessionListener =
        MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
            Logx.d("媒体会话变化：${sessions?.size ?: 0} 个")
            bindBest(sessions)
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Logx.d("媒体元数据变化：${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "-"}")
            publish()
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            publish()
        }

        override fun onSessionDestroyed() {
            publish()
        }
    }

    // =====================================================================

    fun hasPermission(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, DockNotificationListener::class.java)
        return flat.split(":").any {
            val cn = ComponentName.unflattenFromString(it)
            cn != null && cn == me
        }
    }

    fun start(ctx: Context) {
        appCtx = ctx.applicationContext
        if (!hasPermission(ctx)) {
            Logx.e("媒体：没有通知使用权，音乐卡片将显示占位。请在主界面点「授权通知使用权」")
            if (!started) publish(MediaInfo())
            return
        }
        started = true
        appCtx = ctx.applicationContext
        try {
            component = ComponentName(ctx, DockNotificationListener::class.java)
            manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            manager?.addOnActiveSessionsChangedListener(sessionListener, component)
            val sessions = manager?.getActiveSessions(component)
            bindBest(sessions)
            Logx.d("媒体：已连接，当前 ${sessions?.size ?: 0} 个会话")
            DockNotificationListener.instance?.let { ingestNotifications(it.activeNotifications) }
        } catch (t: Throwable) {
            started = false
            Logx.e("媒体：连接失败", t)
        }
    }

    fun stop() {
        if (!started) return
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionListener) }
        runCatching { controller?.unregisterCallback(controllerCallback) }
        controller = null
        started = false
        publish(MediaInfo())
    }

    /**
     * 权限刚授予 / 被系统重置后重新绑定。
     *
     * 典型场景：装了新构建的 APK 后「通知使用权」被系统清掉，
     * 用户在设置里重新打开开关，此时服务还在跑但 started 已被 stop() 置 false，
     * 调这个可以立刻重绑，不用等 2.5s 轮询。
     */
    fun rebind(ctx: Context) {
        Logx.d("媒体：rebind() 重新绑定媒体会话（权限=${hasPermission(ctx)}）")
        stop()
        start(ctx)
    }

    fun refresh(ctx: Context) {
        if (!hasPermission(ctx)) {
            if (started) stop()
            return
        }
        if (!started) { start(ctx); return }
        runCatching {
            val sessions = manager?.getActiveSessions(component)
            bindBest(sessions)
        }
    }

    /** 通知监听刚连上时，把当前通知扫一遍 */
    fun ingestNotifications(items: Array<StatusBarNotification>?) {
        items?.filter { it.packageName == FLYME_MUSIC }?.forEach {
            ingestNotification(it, removed = false)
        }
    }

    /**
     * `com.flyme.auto.music` 的媒体通知。进度条会反复 post 同一条，
     * 歌名没变就不刷卡片、也不打日志。
     */
    fun ingestNotification(sbn: StatusBarNotification, removed: Boolean) {
        if (sbn.packageName != FLYME_MUSIC) return
        if (removed) {
            if (noteTitle.isEmpty() && !usingNote) return
            noteTitle = ""
            noteArtist = ""
            noteArt = null
            noteKey = ""
            usingNote = false
            Logx.d("媒体：通知 $FLYME_MUSIC 已移除，退回媒体会话")
            appCtx?.let { refresh(it) }
            return
        }
        val n = sbn.notification ?: return
        val extras = n.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val sub = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        if (!noteKeysLogged) {
            noteKeysLogged = true
            val keys = extras?.keySet()?.joinToString(",") { it.substringAfterLast('.') } ?: ""
            Logx.d("媒体：通知 $FLYME_MUSIC extras=[$keys] title='$title' text='$text'")
        }
        // CX11 上这条是系统前台服务通知（「QQ音乐正在运行」），不是歌曲。
        // 通知 extras 里的 images 仍可能是封面，歌名继续走媒体会话。
        val extraArt = artFromFlymeExtras(extras)
        if (extraArt != null) noteArt = extraArt
        if (isSystemRunningNotice(title, text)) {
            if (!serviceNoticeLogged) {
                serviceNoticeLogged = true
                Logx.d("媒体：忽略 QQ「正在运行」通知，不把它当成歌曲")
            }
            if (usingNote) {
                usingNote = false
                noteTitle = ""
                noteArtist = ""
                noteKey = ""
                appCtx?.let { refresh(it) }
            } else if (extraArt != null && info.art == null && isRealTitle(info.title)) {
                publish(info.copy(art = extraArt))
            }
            return
        }
        if (!isRealTitle(title)) return
        val artist = when {
            text.isNotEmpty() && text != title -> text
            sub.isNotEmpty() && sub != title -> sub
            else -> ""
        }
        val key = "$title|$artist"
        val art = appCtx?.let { artFromNotification(it, n) }
        if (key == noteKey && art == null) return
        noteTitle = title
        noteArtist = artist
        if (art != null) noteArt = art
        noteKey = key
        @Suppress("DEPRECATION")
        val token = extras?.getParcelable<MediaSession.Token>(Notification.EXTRA_MEDIA_SESSION)
        if (token != null) attachNoteToken(token)
        usingNote = true
        Logx.d("媒体：采用通知 $FLYME_MUSIC title=$title artist=$artist token=${token != null}")
        publishNote()
    }

    private fun sessionHasRealTitle(): Boolean =
        isRealTitle(controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE))

    private fun attachNoteToken(token: MediaSession.Token) {
        val ctx = appCtx ?: return
        if (controller?.sessionToken == token) return
        runCatching { controller?.unregisterCallback(controllerCallback) }
        val c = MediaController(ctx, token)
        controller = c
        runCatching { c.registerCallback(controllerCallback, main) }
        Logx.d("媒体：改用 $FLYME_MUSIC 通知里的会话")
    }

    private fun artFromNotification(ctx: Context, n: Notification): Bitmap? {
        val d = n.getLargeIcon()?.loadDrawable(ctx) ?: return null
        return copyArt(drawableToBitmap(d))
    }

    /** 会话交来的封面经常还被播放器持有，直接画在悬浮窗上会是空白。先复制成普通位图。 */
    private fun copyArt(src: Bitmap?): Bitmap? {
        if (src == null || src.isRecycled) return null
        return runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull()
    }

    /** 蓝牙 AVRCP 没歌时会填 "Not Provided"，不能当成真实歌名 */
    private fun isRealTitle(t: String?): Boolean {
        if (t.isNullOrBlank()) return false
        val s = t.trim()
        return !s.equals("Not Provided", ignoreCase = true) &&
                s != "-" && s != "未知" && s != "未知曲目"
    }

    /**
     * ★★ v1.4.88：**优先选「有封面」的会话**。
     *
     * 为什么要改（原逻辑：优先 STATE_PLAYING → 有 TITLE → 第一个）：
     *
     *  这台车机的媒体生态是**包名白名单制**，DiPlay / 水果互联 / DBPlay 都**伪装成
     *  预装音乐 App 的包名**（如 `com.kugou.android.auto`）走「帆书通道」——
     *  靠**一直播静音音频霸住媒体焦点**，所以它们的会话是**全程 STATE_PLAYING 常驻**。
     *
     *  而 CarPlay 源那个会话（kugou）**metadata 只有 4 个 key**
     *  （TITLE/ARTIST/ALBUM/DURATION，**没有 ALBUM_ART**），
     *  它永远第一个被 STATE_PLAYING 命中 → 我们就一直绑在它上面 → **永远读不到封面**。
     *
     *  真正带封面 Bitmap 的是另外那个会话。
     *
     *  ⚠ 不能每 2.5s 无脑重选，否则歌名/歌手会来回跳。
     *    所以：**当前会话仍有效且已有封面 → 保持不动**；只在「当前没封面」或「会话消失」时才切换。
     */
    private fun bindBest(sessions: List<MediaController>?) {
        val list = sessions ?: emptyList()
        if (list.isEmpty()) {
            if (usingNote && isRealTitle(noteTitle)) {
                publishNote()
                return
            }
            if (controller != null) {
                runCatching { controller?.unregisterCallback(controllerCallback) }
                controller = null
            }
            publish()
            return
        }

        val cur = controller
        val curAlive = cur != null && list.any { it.sessionToken == cur.sessionToken }

        // ★ 诊断：把候选会话逐个打出来（一轮日志就能定位是不是选错了会话）
        list.forEachIndexed { i, c ->
            // MediaController.getPackageName() 是公开 API（Token.describe() 是 @hide）
            val pkg = runCatching { c.packageName }.getOrNull() ?: "?"
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            val title = runCatching { c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) }.getOrNull()
            Logx.d(
                "媒体：会话[" + i + "] pkg=" + pkg + " playing=" + playing +
                " 有封面=" + hasArt(c) + " 打分=" + scoreOf(c) + " title=" + (title ?: "-")
            )
        }

        // ★★ v1.4.97 #5：改成「打分」选会话。
        //
        //   v1.4.88 为了修「读不到封面」把优先级换成「有封面 > 正在播放」——
        //   副作用就是现在会绑到一个**压根没在播**的会话上（用户反馈 #5）。
        //   现在两者都要：正在播放优先，播放中同分时才有封面优先；
        //   STOPPED / NONE / ERROR 的会话明确排到最低（除非只剩它一个）。
        var best: MediaController? = null
        if (curAlive) {
            // 当前会话还在：只有在它**不是最高分**时才换（避免歌名来回跳）
            val curScore = scoreOf(cur!!)
            val top = list.maxOf { scoreOf(it) }
            best = if (curScore >= top) cur else list.first { scoreOf(it) == top }
        } else {
            // 首次绑定 / 当前会话消失：取最高分的那个（同分取靠前的）
            val top = list.maxOf { scoreOf(it) }
            best = list.first { scoreOf(it) == top }
        }

        val bestTitle = runCatching {
            best?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        }.getOrNull()
        // 标准会话没有歌名时，保留 Flyme / QQ 通知里的那条
        if (usingNote && isRealTitle(noteTitle) && !isRealTitle(bestTitle)) {
            publishNote()
            return
        }
        usingNote = false

        if (best?.sessionToken == cur?.sessionToken) {
            publish()
            return
        }
        runCatching { controller?.unregisterCallback(controllerCallback) }
        controller = best
        runCatching { best?.registerCallback(controllerCallback, main) }
        Logx.d("媒体：切换会话 → " + (runCatching { best?.packageName }.getOrNull() ?: "无"))
        publish()
    }

    /**
     * ★ v1.4.97 #5：给候选会话打分，分高的当选。
     *
     *  4 = 正在播放 **且** 有封面
     *  3 = 正在播放
     *  2 = 没停（PAUSED/BUFFERING 等，可能是切歌间隙）**且** 有封面
     *  1 = 没停 **且** 有歌名
     *  0 = 已 STOPPED / NONE / ERROR，或啥都没有
     *
     * ⚠ 这台车上「帆书通道」那几个会话靠静音音频全程 STATE_PLAYING 常驻，
     *   所以单看 playing 分不开；但**真的没在播**的那些是 STOPPED/NONE，
     *   现在会被压到 0 分 —— 只要还有别的候选就不会再绑到它们上。
     */
    private fun scoreOf(c: MediaController): Int {
        val st = runCatching { c.playbackState?.state }.getOrNull()
        val playing = st == PlaybackState.STATE_PLAYING
        val dead = st == PlaybackState.STATE_STOPPED ||
                st == PlaybackState.STATE_NONE ||
                st == PlaybackState.STATE_ERROR
        val art = hasArt(c)
        val titled = !TextUtils.isEmpty(
            runCatching { c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) }.getOrNull()
        )
        return when {
            dead -> 0
            playing && art -> 4
            playing -> 3
            art -> 2
            titled -> 1
            else -> 0
        }
    }

    /** ★ v1.4.88：这个会话的 metadata 里有没有封面（Bitmap 或 URI 都算） */
    private fun hasArt(c: MediaController): Boolean {
        val md = runCatching { c.metadata }.getOrNull() ?: return false
        for (k in ART_KEYS) {
            if (runCatching { md.getBitmap(k) }.getOrNull() != null) return true
        }
        for (k in ART_URI_KEYS) {
            val uri = runCatching { md.getString(k) }.getOrNull()
                ?: runCatching { md.getText(k)?.toString() }.getOrNull()
            if (!uri.isNullOrBlank()) return true
        }
        val pkg = runCatching { c.packageName }.getOrNull() ?: return false
        if (pkg == "com.android.bluetooth" || pkg == FLYME_MUSIC) return false
        val items = DockNotificationListener.instance?.activeNotifications ?: return false
        return items.any { it.packageName == pkg && it.notification?.getLargeIcon() != null }
    }

    /** 媒体中心推来的当前曲目。有歌名时盖过空的蓝牙会话。 */
    fun onCenterTrack(
        title: String,
        artist: String,
        album: String,
        art: Bitmap?,
        playing: Boolean,
        sourceLabel: String
    ) {
        if (!isRealTitle(title)) return
        val changed = title != centerTitle || artist != centerArtist || sourceLabel != centerLabel
        centerActive = true
        centerTitle = title
        centerArtist = artist
        centerAlbum = album
        if (art != null) centerArt = art
        centerPlaying = playing
        centerLabel = sourceLabel
        if (changed) Logx.d("媒体中心：$sourceLabel title=$title artist=$artist playing=$playing")
        publish(
            MediaInfo(
                hasSession = true,
                title = title,
                artist = artist,
                album = album,
                art = centerArt,
                isPlaying = playing
            )
        )
    }

    fun onCenterState(playing: Boolean) {
        if (!centerActive) return
        centerPlaying = playing
        publish(
            MediaInfo(
                hasSession = true,
                title = centerTitle,
                artist = centerArtist,
                album = centerAlbum,
                art = centerArt,
                isPlaying = playing
            )
        )
    }

    private fun publish() {
        val c = controller
        val md = runCatching { c?.metadata }.getOrNull()
        val ps = runCatching { c?.playbackState }.getOrNull()

        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
            ?: ""
        val album = md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art = pickArt(md)
        // ★ v1.4.74：专辑图诊断 —— 把 MediaMetadata 里所有 key 打出来，
        //   这样就知道播放器到底填了什么（Bitmap? URI? 都没有?）
        if (art == null && md != null) {
            // ★ MediaMetadata.keys() 是 API 30+ 才有的，Android 9（API 28）没有 → 用反射取
            val keys: List<String> = runCatching {
                val m = md.javaClass.methods.firstOrNull { it.name == "keys" && it.parameterTypes.isEmpty() }
                @Suppress("UNCHECKED_CAST")
                (m?.invoke(md) as? Set<String>)?.toList() ?: emptyList()
            }.getOrDefault(emptyList())
            if (keys.isEmpty()) {
                // 反射拿不到就退回已知 key 列表逐个探
                Logx.d("媒体：★ 无专辑图（metadata 取不到 key 列表），逐个探测常用 key…")
                for (k in PROBE_KEYS) {
                    val v = runCatching { md.getText(k) }.getOrNull()
                    val s = runCatching { md.getString(k) }.getOrNull()
                    val bmp = runCatching { md.getBitmap(k) }.getOrNull()
                    if (v != null) Logx.d("媒体：   $k → text=$v")
                    if (s != null && s != v) Logx.d("媒体：   $k → string=$s")
                    if (bmp != null) Logx.d("媒体：   $k → ★ 有 Bitmap ${bmp.width}×${bmp.height}")
                }
            } else {
                Logx.d("媒体：★ 无专辑图，metadata 的 key = [${keys.joinToString(", ") { it.substringAfterLast('.') }}]")
                for (k in keys) {
                    val v = runCatching { md.getText(k) }.getOrNull()
                    val s = runCatching { md.getString(k) }.getOrNull()
                    val bmp = runCatching { md.getBitmap(k) }.getOrNull()
                    if (v != null) Logx.d("媒体：   ${k.substringAfterLast('.')} = $v")
                    if (s != null && s != v) Logx.d("媒体：   ${k.substringAfterLast('.')} = $s")
                    if (bmp != null) Logx.d("媒体：   ${k.substringAfterLast('.')} → ★ 有 Bitmap ${bmp.width}×${bmp.height}")
                }
            }
        } else if (art != null) {
            Logx.d("媒体：★ 专辑图已获取 ${art.width}×${art.height}")
        }
        val playing = ps?.state == PlaybackState.STATE_PLAYING
        val showTitle = if (isRealTitle(title)) title else if (isRealTitle(noteTitle)) noteTitle else title
        val showArtist = if (isRealTitle(title)) artist else if (noteArtist.isNotEmpty()) noteArtist else artist
        val showArt = art ?: noteArt

        publish(
            MediaInfo(
                hasSession = c != null && (!TextUtils.isEmpty(showTitle) || ps != null) ||
                        isRealTitle(showTitle),
                title = showTitle,
                artist = showArtist,
                album = album,
                art = showArt,
                isPlaying = playing || (usingNote && notePlaying())
            )
        )
    }

    private fun notePlaying(): Boolean =
        controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    /** 通知有歌名、会话 token 还没挂上时，直接用通知内容刷卡片 */
    private fun publishNote() {
        publish(
            MediaInfo(
                hasSession = true,
                title = noteTitle,
                artist = noteArtist,
                album = "",
                art = noteArt,
                isPlaying = notePlaying()
            )
        )
    }

    /** 常见的 metadata key（Android 9 没有 keys()，反射拿不到时逐个探测这些） */
    /** ★ v1.4.88：判断「这个会话有没有封面」用的 key（Bitmap 形态） */
    private val ART_KEYS = arrayOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON
    )

    /** ★ v1.4.88：URI 形态的封面 key */
    private val ART_URI_KEYS = arrayOf(
        "android.media.metadata.ALBUM_ART_URI",
        "android.media.metadata.ART_URI",
        "android.media.metadata.DISPLAY_ICON_URI"
    )

    private val PROBE_KEYS = listOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        "android.media.metadata.ALBUM_ART_URI",
        "android.media.metadata.ART_URI",
        "android.media.metadata.DISPLAY_ICON_URI",
        MediaMetadata.METADATA_KEY_TITLE,
        MediaMetadata.METADATA_KEY_ARTIST,
        MediaMetadata.METADATA_KEY_ALBUM,
        MediaMetadata.METADATA_KEY_ALBUM_ARTIST
    )

    /**
     * ★ v3.4：安全取 MediaMetadata 的字符串值。
     *
     * `MediaMetadata.getString(key)` 在该 key 存的**不是 String 类型**时返回 null，
     * 而 `getText(key)` 能拿到 CharSequence。真车日志实证（蓝牙 AVRCP 播放）：
     * ```
     * 媒体：★ 无专辑图（metadata 取不到 key 列表），逐个探测常用 key…
     * 媒体：   android.media.metadata.ALBUM_ART_URI → text=/storage/emulated/0/bluetooth/…AVRCP_BIP_IMG_xxx.JPEG
     * ```
     * 探测用 `getText` 能打出路径，而 [pickArt] 用 `getString` 却取不到
     * → 专辑图恒为 null（而 TITLE/ARTIST 正常，因为它们是 String）。
     * 所以两个都试，`getText` 兜底。
     */
    private fun mdText(md: MediaMetadata, key: String): String? =
        runCatching { md.getString(key) }.getOrNull()
            ?: runCatching { md.getText(key)?.toString() }.getOrNull()

    private fun pickArt(md: MediaMetadata?): Bitmap? {
        if (md == null) return null
        for (key in arrayOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON
        )) {
            val d = runCatching { md.getBitmap(key) }.getOrNull()
            val copy = copyArt(d)
            if (copy != null) return copy
        }
        // ★ v1.4.64：getBitmap 拿不到时，退回 URI 自己解码。
        //   很多第三方播放器（尤其车机定制的酷狗 / CloudMusic）只填
        //   METADATA_KEY_ALBUM_ART_URI / METADATA_KEY_ART_URI / DISPLAY_ICON_URI，
        //   不填 Bitmap → 专辑图永远不显示。
        // ★ v3.4：改用 [mdText]（getString 拿不到时用 getText 兜底），
        //   否则播放器把 URI 存成 CharSequence 时这里永远取不到。
        val uri = mdText(md, MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: mdText(md, MediaMetadata.METADATA_KEY_ART_URI)
            ?: mdText(md, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        uri?.let { copyArt(decodeArtUri(it)) }?.let { return it }
        runCatching { md.description }.getOrNull()?.let { desc ->
            copyArt(desc.iconBitmap)?.let { return it }
            desc.iconUri?.toString()?.let { copyArt(decodeArtUri(it)) }?.let { return it }
        }
        artFromMetadataBundle(md)?.let { return it }
        // 酷狗车机版的封面在自己的媒体通知大图里，会话 metadata 的 getBitmap 经常是空的
        return artFromPlayerNotification()
    }

    /**
     * CX11 的酷狗/互联会话在标准 key 上没有封面。Android 9 又没有 keys()，
     * 这里直接读 MediaMetadata 内部 Bundle，有 Bitmap 或本地路径就用。
     */
    private fun artFromMetadataBundle(md: MediaMetadata): Bitmap? {
        val bundle = runCatching {
            val f = MediaMetadata::class.java.getDeclaredField("mBundle")
            f.isAccessible = true
            (f.get(md) as? Bundle)?.also { it.classLoader = Bitmap::class.java.classLoader }
        }.getOrNull() ?: return null
        val title = mdText(md, MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val logThis = title.isNotEmpty() && title != bundleLoggedFor
        if (logThis) bundleLoggedFor = title
        var found: Bitmap? = null
        for (k in bundle.keySet()) {
            val v = runCatching { bundle.get(k) }.getOrNull()
            if (logThis) Logx.d("媒体：   bundle ${k.substringAfterLast('.')} = ${describeExtra(v)}")
            if (found == null) found = bitmapFromExtra(v, minSide = 32)
        }
        return found
    }

    /** Flyme QQ 的通知没有 MediaSession，封面若有，在 extras 的 images 里。 */
    private fun artFromFlymeExtras(extras: Bundle?): Bitmap? {
        if (extras == null) return null
        val key = extras.keySet().firstOrNull { it == "images" || it.endsWith(".images") } ?: return null
        val v = runCatching { extras.get(key) }.getOrNull()
        if (!imagesLogged) {
            imagesLogged = true
            Logx.d("媒体：QQ 通知 images = ${describeExtra(v)}")
        }
        return bitmapFromExtra(v, minSide = 96)
    }

    private fun describeExtra(v: Any?): String = when (v) {
        null -> "null"
        is Bitmap -> "Bitmap ${v.width}×${v.height}"
        is String -> "String ${v.take(80)}"
        is ArrayList<*> -> "ArrayList(${v.size}) " + v.take(3).joinToString(",") { describeExtra(it) }
        is Bundle -> "Bundle[${v.keySet().joinToString(",") { it.substringAfterLast('.') }}]"
        else -> v.javaClass.name
    }

    private fun bitmapFromExtra(v: Any?, minSide: Int): Bitmap? {
        val bmp = when (v) {
            is Bitmap -> copyArt(v)
            is android.graphics.drawable.Icon -> {
                val ctx = appCtx ?: return null
                val d = runCatching { v.loadDrawable(ctx) }.getOrNull() ?: return null
                copyArt(drawableToBitmap(d))
            }
                is String -> localArt(v)
            is CharSequence -> localArt(v.toString())
            is android.net.Uri -> localArt(v.toString())
            is ByteArray -> {
                if (v.size < 64) null
                else copyArt(BitmapFactory.decodeByteArray(v, 0, v.size))
            }
            is ArrayList<*> -> {
                for (item in v) {
                    bitmapFromExtra(item, minSide)?.let { return it }
                }
                null
            }
            is Bundle -> {
                for (k in v.keySet()) {
                    bitmapFromExtra(v.get(k), minSide)?.let { return it }
                }
                null
            }
            else -> null
        } ?: return null
        if (bmp.width < minSide || bmp.height < minSide) return null
        return bmp
    }

    private fun localArt(path: String): Bitmap? {
        if (!path.startsWith("/") && !path.startsWith("file://") && !path.startsWith("content://")) return null
        return copyArt(decodeArtUri(path))
    }

    /**
     * 当前会话对应播放器的通知大图。酷狗（com.kugou.android.auto）把封面放在这里，
     * MediaMetadata.getBitmap 取到的是空。
     */
    private fun artFromPlayerNotification(): Bitmap? {
        val pkg = runCatching { controller?.packageName }.getOrNull() ?: return null
        if (pkg == "com.android.bluetooth" || pkg == FLYME_MUSIC) return null
        val ctx = appCtx ?: return null
        val items = DockNotificationListener.instance?.activeNotifications ?: return null
        var best: Bitmap? = null
        var bestArea = 0
        for (sbn in items) {
            if (sbn.packageName != pkg) continue
            val n = sbn.notification ?: continue
            val found = ArrayList<Bitmap>(4)
            artFromNotification(ctx, n)?.let { found.add(it) }
            val extras = n.extras
            if (extras != null) {
                runCatching { extras.classLoader = Bitmap::class.java.classLoader }
                for (k in extras.keySet()) {
                    val v = runCatching { extras.get(k) }.getOrNull()
                    bitmapFromExtra(v, minSide = 48)?.let { found.add(it) }
                }
            }
            for (b in found) {
                val area = b.width * b.height
                if (area > bestArea) {
                    best = b
                    bestArea = area
                }
            }
        }
        val title = runCatching {
            controller?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        }.getOrNull().orEmpty()
        if (best != null && title != notifArtLoggedFor) {
            notifArtLoggedFor = title
            Logx.d("媒体：★ 封面取自 $pkg 通知 ${best.width}×${best.height}")
        }
        return best
    }

    private fun isSystemRunningNotice(title: String, text: String): Boolean =
        title.contains("正在运行") || text.contains("点按即可了解详情")

    /** 解码专辑图 URI（http/https 走网络；content/file 走 ContentResolver） */
    /**
     * ★★ v1.4.90：**只用「本地文件路径」这一种方式**取专辑图。
     *
     * 为什么只留这一条 —— 真车日志实证（水果互联 / 蓝牙 AVRCP 播放）：
     * ```
     * 媒体：   android.media.metadata.ALBUM_ART_URI → text=
     *         /storage/emulated/0/bluetooth/28:4B:54:39:F6:F4AVRCP_BIP_IMG_1000059.JPEG
     * ```
     * `AVRCP_BIP_IMG` 是**蓝牙 AVRCP 的封面图传输标准** —— 系统把手机推过来的封面
     * 缓存成外部存储里的一个 JPEG 文件，再把这个**裸路径**塞进 `ALBUM_ART_URI`。
     *
     * 它**既不是 `content://` 也不是 `http://`**，所以旧实现每次都落进 `else -> null`，
     * 再加上前面三个 Bitmap key（ALBUM_ART/ART/DISPLAY_ICON）也全是 null
     * → art 恒为 null → Dock 永远显示音符占位。
     * （静态对比过 0.40 / 1.2.0：pickArt 三个 key 的字节码完全一致，
     *   所以这不是最近改坏的，是一直如此。）
     *
     * 按用户要求：**取消 http/https 下载与 content:// 分支，只走本地文件**。
     */
    private fun decodeArtUri(uri: String): Bitmap? {
        if (uri.isBlank()) return null
        if (uri.startsWith("content://")) {
            val ctx = appCtx ?: return null
            return runCatching {
                ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it)
                }
            }.getOrElse { e ->
                Logx.e("媒体：专辑图 content 读取失败 $uri", e)
                null
            }?.also {
                Logx.d("媒体：★ 专辑图读取成功 ${it.width}×${it.height} ← $uri")
            }
        }
        // 统一剥掉可能的 file:// 前缀，剩下的就是文件系统绝对路径
        val path = uri.removePrefix("file://")
        if (!path.startsWith("/")) {
            Logx.d("媒体：专辑图路径不是本地绝对路径，跳过 → " + uri)
            return null
        }
        val f = java.io.File(path)
        if (!f.exists() || !f.canRead()) {
            Logx.d("媒体：专辑图文件不可读 exists=${f.exists()} canRead=${f.canRead()} → $path")
            return null
        }
        return runCatching {
            java.io.FileInputStream(f).use { BitmapFactory.decodeStream(it) }
        }.getOrElse { e ->
            Logx.e("媒体：专辑图读取失败 $path", e)
            null
        }?.also {
            Logx.d("媒体：★ 专辑图读取成功 ${it.width}×${it.height} ← $path")
        }
    }

    private fun drawableToBitmap(d: Drawable): Bitmap? {
        if (d is BitmapDrawable) return d.bitmap
        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else 128
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else 128
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, c.width, c.height)
            d.draw(c)
            bmp
        }.getOrNull()
    }

    // =====================================================================
    // 交互
    // =====================================================================

    fun playPause() {
        if (usingNote && isRealTitle(noteTitle)) {
            val c = controller
            val pkg = runCatching { c?.packageName }.getOrNull()
            if (pkg == FLYME_MUSIC) {
                val tc = c?.transportControls
                if (tc != null) {
                    runCatching {
                        val st = c.playbackState?.state
                        if (st == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
                    }
                    return
                }
            }
            appCtx?.let { openFlyme(it) }
            return
        }
        val tc = controller?.transportControls ?: return
        runCatching {
            val st = controller?.playbackState?.state
            if (st == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            Logx.d("媒体：切换播放/暂停")
        }.onFailure { Logx.e("媒体：切换播放失败", it) }
    }

    fun next() {
        runCatching { controller?.transportControls?.skipToNext() }
    }

    fun previous() {
        runCatching { controller?.transportControls?.skipToPrevious() }
    }

    /** 打开当前播放器界面。成功返回 true，无会话或启动失败返回 false */
    fun openPlayer(ctx: Context): Boolean {
        val c = controller
        val sessionTitle = runCatching { c?.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) }.getOrNull()
        val pkg = runCatching { c?.packageName }.getOrNull()
        if (isRealTitle(sessionTitle) && !pkg.isNullOrBlank() && pkg != "com.android.bluetooth") {
            val pi = runCatching { c?.sessionActivity }.getOrNull()
            if (pi != null) {
                runCatching { pi.send() }.onSuccess {
                    Logx.d("媒体：通过 sessionActivity 打开 $pkg")
                    return true
                }
            }
            return launchPackage(ctx, pkg)
        }
        if (usingNote && isRealTitle(noteTitle)) return openFlyme(ctx)
        if (pkg.isNullOrBlank()) return false
        return launchPackage(ctx, pkg)
    }

    private fun openFlyme(ctx: Context): Boolean = launchPackage(ctx, FLYME_MUSIC)

    private fun launchPackage(ctx: Context, pkg: String): Boolean = runCatching {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: return@runCatching false
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        Logx.d("媒体：启动 $pkg")
        true
    }.getOrElse { false }

    val currentTitle: String get() = info.title

    // =====================================================================

    fun addListener(l: (MediaInfo) -> Unit) {
        listeners.add(l)
        l(info)
    }

    fun removeListener(l: (MediaInfo) -> Unit) { listeners.remove(l) }

    private fun publish(i: MediaInfo) {
        info = i
        main.post {
            val snapshot = runCatching { listeners.toList() }.getOrDefault(emptyList())
            snapshot.forEach { runCatching { it(i) } }
        }
    }
}
