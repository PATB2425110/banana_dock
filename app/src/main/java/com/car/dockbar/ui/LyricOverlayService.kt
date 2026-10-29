package com.car.dockbar.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.car.dockbar.R
import com.car.dockbar.media.LrcParser
import com.car.dockbar.media.LyricMonitor
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/**
 * 歌词悬浮窗服务（融合自 LyricFloat `FloatWindowService`）。
 *
 * 独立于 Dock 的一条悬浮窗：
 *  - 1~10 行歌词，当前句高亮居中，其余行渐暗；单行过长自动换行（不截断）
 *  - 单指拖动移动，位置自动保存
 *  - 「锁定」= 整窗 `FLAG_NOT_TOUCHABLE`，点窗口区域等价于点它下面的内容
 *  - 窗口内**没有任何调节控件**，透明度/尺寸/字号/行数/颜色/偏移全在主界面调
 *  - 数据来自 [LyricMonitor]（广播 + MediaSession 双通道，歌词三级兜底）
 */
class LyricOverlayService : Service(), LyricMonitor.Callback {

    companion object {
        const val ACTION_UPDATE = "com.car.dockbar.action.LYRIC_UPDATE"
        const val ACTION_STOP = "com.car.dockbar.action.LYRIC_STOP"
        const val ACTION_LOCK_TOGGLE = "com.car.dockbar.action.LYRIC_LOCK_TOGGLE"

        private const val NOTI_ID = 2002
        private const val CHANNEL_ID = "lyric_overlay"
        private const val TICK_MS = 200L

        /** 服务是否真的在跑（与 Activity 同进程，可直接读） */
        @Volatile
        var alive: Boolean = false
            private set

        /** 悬浮窗是否真的挂上去了 */
        @Volatile
        var attached: Boolean = false
            private set

        /** 当前播放进度快照 [pos, dur, playing]，未运行返回 null */
        @Volatile
        var progress: LongArray? = null
            private set

        /** 当前曲目概要（设置界面用） */
        @Volatile
        var nowPlaying: String? = null
            private set

        /** 进度来源（MediaSession / 广播估算） */
        @Volatile
        var progressSource: String? = null
            private set

        // ---------------------------------------------------------------- 状态回调

        private val stateListeners = mutableSetOf<(Boolean) -> Unit>()

        /** 运行状态监听（Dock 上的「歌词」按钮据此点亮/熄灭） */
        fun addStateListener(l: (Boolean) -> Unit) {
            stateListeners.add(l)
            runCatching { l(alive && attached) }
        }

        fun removeStateListener(l: (Boolean) -> Unit) {
            stateListeners.remove(l)
        }

        private fun notifyState() {
            val on = alive && attached
            stateListeners.toList().forEach { runCatching { it(on) } }
        }
    }

    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var root: View? = null
    private var lyricContainer: LinearLayout? = null

    private val lineViews = ArrayList<TextView>()
    private var builtLineCount = -1

    private val ui = Handler(Looper.getMainLooper())

    private var track: LyricMonitor.Track? = null
    private var lines: List<LrcParser.Line>? = null

    private var curPos = 0L
    private var curDur = 0L
    private var curPlaying = false
    private var curSrc: String? = null
    private var lastIndex = Int.MIN_VALUE

    private val ticker = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, TICK_MS)
        }
    }

    private val cmdReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE -> applyPrefs()
                ACTION_STOP -> stopSelf()
            }
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onCreate() {
        super.onCreate()
        alive = true
        Logx.d("LyricOverlayService onCreate")
        notifyState()
        startForegroundCompat()
        registerCmdReceiver()
        LyricMonitor.init(this, this)
        LyricMonitor.start()
        buildWindow()
        applyPrefs()
        ui.removeCallbacks(ticker)
        ui.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LOCK_TOGGLE) {
            val l = !Prefs.lyricLocked(this)
            Prefs.setLyricLocked(this, l)
            applyTouchMode()
            Logx.d("歌词悬浮窗：锁定状态切换为 ${if (l) "已锁定（点击穿透）" else "可交互"}")
            return START_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            Logx.d("歌词悬浮窗：收到停止指令")
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onDestroy() {
        Logx.d("LyricOverlayService onDestroy")
        alive = false
        attached = false
        progress = null
        nowPlaying = null
        notifyState()
        ui.removeCallbacks(ticker)
        LyricMonitor.removeCallback(this)
        LyricMonitor.stop()
        runCatching { unregisterReceiver(cmdReceiver) }
        runCatching { if (root != null && wm != null) wm!!.removeViewImmediate(root) }
        root = null
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "歌词悬浮窗", NotificationManager.IMPORTANCE_LOW
                ).apply { description = "保持歌词悬浮窗在后台运行" }
            )
        }
        // 锁定的窗口本身不接收点击（FLAG_NOT_TOUCHABLE），所以解锁只能从这里点
        val lockIntent = Intent(this, LyricOverlayService::class.java).setAction(ACTION_LOCK_TOGGLE)
        val lockPi = PendingIntent.getService(
            this, 3, lockIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 4,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("歌词悬浮窗运行中")
            .setContentText("点击打开设置")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "锁定/解锁窗口", lockPi)
            .build()
        runCatching { startForeground(NOTI_ID, n) }
            .onFailure { Logx.e("歌词悬浮窗 startForeground 失败：${it.message}") }
    }

    private fun registerCmdReceiver() {
        val f = IntentFilter().apply {
            addAction(ACTION_UPDATE)
            addAction(ACTION_STOP)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(cmdReceiver, f, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(cmdReceiver, f)
            }
        }.onFailure { Logx.e("歌词悬浮窗注册广播失败", it) }
    }

    // ---------------------------------------------------------------- 窗口

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildWindow() {
        wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager

        // 外框：圆角半透明底板（透明度在主界面调）
        val panel = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(16f).toFloat()
            setColor(0x000000)
        }
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = panel
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        outer.addView(
            inner,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        lyricContainer = inner
        root = outer

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.lyricX(this@LyricOverlayService)
            y = Prefs.lyricY(this@LyricOverlayService)
        }

        runCatching { wm?.addView(root, params) }
            .onFailure {
                Logx.e("歌词悬浮窗添加失败：${it.message}")
                attached = false
            }
            .onSuccess { attached = true }
        notifyState()

        applyTouchMode()
        bindDrag()
    }

    /** 单指拖动挪位置（窗口内无任何控件） */
    private fun bindDrag() {
        val v = root ?: return
        v.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var ix = 0
            var iy = 0
            var moved = false

            override fun onTouch(view: View, e: MotionEvent): Boolean {
                if (e.pointerCount > 1) return true
                val p = params ?: return true
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        ix = p.x
                        iy = p.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        val dy = e.rawY - sy
                        if (!moved && Math.abs(dx) + Math.abs(dy) > 8) moved = true
                        if (moved && !Prefs.lyricLocked(this@LyricOverlayService)) {
                            p.x = ix + dx.toInt()
                            p.y = iy + dy.toInt()
                            runCatching { wm?.updateViewLayout(root, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved && !Prefs.lyricLocked(this@LyricOverlayService)) {
                            Prefs.setLyricX(this@LyricOverlayService, p.x)
                            Prefs.setLyricY(this@LyricOverlayService, p.y)
                        }
                    }
                }
                return true
            }
        })
    }

    // ---------------------------------------------------------------- 配置生效

    private fun applyPrefs() {
        val v = root ?: return
        val p = params ?: return

        // 背景不透明度：把 alpha 直接烧进 solid 颜色里。
        // 不用 GradientDrawable.setAlpha() —— 那条路在某些 ROM 上对 solid 填充不生效。
        val a = (255 * Prefs.lyricAlpha(this) / 100f).toInt().coerceIn(0, 255)
        (v.background as? GradientDrawable)?.setColor((a shl 24) or 0x000000)

        applyTouchMode()

        // 窗口尺寸：宽高独立可调，高度 0 = 自适应内容
        val wPx = dp(Prefs.lyricWinW(this).toFloat())
        val hPx = if (Prefs.lyricWinH(this) <= 0) {
            LinearLayout.LayoutParams.WRAP_CONTENT
        } else {
            dp(Prefs.lyricWinH(this).toFloat())
        }
        lyricContainer?.layoutParams?.let { lp ->
            lp.width = wPx
            lp.height = hPx
            lyricContainer?.layoutParams = lp
        }

        val lc = Prefs.lyricLineCount(this)
        buildLineViews(lc)

        // 颜色 / 字号（当前行由 render() 逐行覆写）
        val size = Prefs.lyricTextSize(this).toFloat()
        val dim = Prefs.lyricTextColor(this)
        val hl = Prefs.lyricHlColor(this)
        val curSlot = curSlot(lc)
        for (i in lineViews.indices) {
            val t = lineViews[i]
            t.setTextSize(if (i == curSlot) size else (size - 4f).coerceAtLeast(10f))
            t.setTextColor(if (i == curSlot) hl else dim)
            t.setTypeface(null, if (i == curSlot) Typeface.BOLD else Typeface.NORMAL)
        }

        runCatching { wm?.updateViewLayout(v, p) }
        lastIndex = Int.MIN_VALUE
        render()
    }

    /**
     * 锁定窗口 = 整窗点击穿透（FLAG_NOT_TOUCHABLE），点窗口区域等价于点它下面的内容；
     * 解锁后恢复可交互。锁定后窗口本身收不到点击，解锁请走通知栏的「锁定/解锁窗口」按钮。
     */
    private fun applyTouchMode() {
        val v = root ?: return
        val p = params ?: return
        if (Prefs.lyricLocked(this)) {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        runCatching { wm?.updateViewLayout(v, p) }
        Logx.d("歌词悬浮窗：触摸模式=${if (Prefs.lyricLocked(this)) "锁定（点击穿透）" else "可交互"}")
    }

    /** 当前行在窗口里的位置：偏上一点，让下方能看到更多即将到来的歌词 */
    private fun curSlot(lineCount: Int): Int = (lineCount - 1) / 2

    /** 按行数动态生成 TextView（1~10 行） */
    private fun buildLineViews(n: Int) {
        val c = lyricContainer ?: return
        if (n == builtLineCount && lineViews.isNotEmpty()) return
        builtLineCount = n
        lineViews.clear()
        c.removeAllViews()
        for (i in 0 until n) {
            val t = TextView(this).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                maxLines = 2              // 太长的一句自动换行到下一行，不再截断
                ellipsize = null
                text = ""
                setPadding(0, dp(2f), 0, dp(2f))
            }
            c.addView(
                t,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
            lineViews.add(t)
        }
    }

    // ---------------------------------------------------------------- 数据回调

    override fun onTrackChanged(t: LyricMonitor.Track?) {
        track = t
        lines = t?.lines
        lastIndex = Int.MIN_VALUE
        nowPlaying = if (t == null) null else "${t.title ?: ""} / ${t.artist ?: ""}"
        render()
    }

    override fun onProgress(posMs: Long, durMs: Long, playing: Boolean, src: String?) {
        curPos = posMs
        curDur = durMs
        curPlaying = playing
        curSrc = src
        progress = longArrayOf(posMs, durMs, if (playing) 1 else 0)
        progressSource = src
    }

    // ---------------------------------------------------------------- 渲染

    private fun render() {
        if (lineViews.isEmpty()) return
        val n = lineViews.size
        val slot = curSlot(n)

        val tk = track
        if (tk == null) {
            for (i in 0 until n) lineViews[i].text = if (i == slot) "等待播放…" else ""
            return
        }

        val pos = curPos + Prefs.lyricOffsetMs(this)

        val ln = lines
        if (ln.isNullOrEmpty()) {
            for (i in 0 until n) {
                when (i) {
                    slot -> lineViews[i].text =
                        if (tk.title.isNullOrEmpty()) "未获取到歌词" else tk.title
                    slot + 1 -> lineViews[i].text =
                        if (tk.lyric == null) "（该音源未提供歌词）" else "（歌词无时间轴）"
                    else -> lineViews[i].text = ""
                }
            }
            return
        }

        val idx = LrcParser.indexFor(ln, pos)
        if (idx == lastIndex) return
        lastIndex = idx

        for (i in 0 until n) {
            var src = idx + (i - slot)
            if (idx < 0) {
                // 前奏：当前行显示 ♪，下面依次是第 0、1、2… 句
                src = i - slot - 1
            }
            val txt = when {
                i == slot && idx < 0 -> "♪"
                src >= 0 && src < ln.size -> ln[src].text
                else -> ""
            }
            lineViews[i].text = txt
        }
    }
}

/**
 * 歌词悬浮窗开关。Dock 上的「歌词」按钮和主界面共用这一套逻辑：
 * 开启会写 [Prefs.lyricEnabled]（开机自启 / 服务被杀后重拉都靠它判断）。
 */
object LyricWindows {

    /** @return true = 已启动；false = 启动失败（多半是没悬浮窗权限） */
    fun start(ctx: Context): Boolean {
        if (!android.provider.Settings.canDrawOverlays(ctx)) {
            Logx.e("歌词悬浮窗：没有悬浮窗权限，无法启动")
            return false
        }
        // 安全等级 >= 2：连续崩溃后自动禁用歌词悬浮窗（它也会创建 overlay 窗口）
        if (Prefs.safeLevel(ctx) >= 2) {
            Logx.d("安全模式 L${Prefs.safeLevel(ctx)}：歌词悬浮窗已禁用")
            return false
        }
        Prefs.setLyricEnabled(ctx, true)
        val ok = runCatching {
            androidx.core.content.ContextCompat.startForegroundService(
                ctx, Intent(ctx, LyricOverlayService::class.java)
            )
        }.isSuccess
        if (!ok) Logx.e("启动歌词悬浮窗失败")
        return ok
    }

    fun stop(ctx: Context) {
        Prefs.setLyricEnabled(ctx, false)
        // 服务已经不在跑就别再 startService 了（那会把它拉起来再立刻杀掉，白闪一下）
        if (!LyricOverlayService.alive) return
        runCatching {
            ctx.startService(
                Intent(ctx, LyricOverlayService::class.java)
                    .setAction(LyricOverlayService.ACTION_STOP)
            )
        }
    }

    /** 开 / 关切换。@return 切换后是否在运行 */
    fun toggle(ctx: Context): Boolean =
        if (LyricOverlayService.alive) {
            stop(ctx)
            false
        } else {
            start(ctx)
        }
}
