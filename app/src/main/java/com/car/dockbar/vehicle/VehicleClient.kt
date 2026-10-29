package com.car.dockbar.vehicle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.car.dockbar.util.LogUploader
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Prefs

/**
 * 车控客户端（运行在主进程）。
 *
 * 主进程**不再直接触碰 ECarX adaptapi**，全部通过 [VehicleControlService]（独立进程 `:car`）完成。
 * 这样即使车控 native 崩溃，也只是 `:car` 进程死掉，Dock 本身不受影响。
 *
 * 对外暴露的接口刻意与 [VehicleController] 保持一致（同名方法），
 * 这样 UI 层（DockOverlayService / DockEntryViewFactory / MainActivity）几乎不用改。
 */
object VehicleClient {

    /** 车控状态的本地镜像 */
    @Volatile
    var state: VehicleController.State = VehicleController.State()
        private set

    /** 车控进程是否可用（已连接且最近收到过状态） */
    @Volatile
    var available: Boolean = false
        private set

    /** 车控进程崩溃次数（用于上报与自动退避） */
    @Volatile
    var diedCount: Int = 0
        private set

    /** 最近一次异常描述（主界面展示） */
    @Volatile
    var lastError: String = ""
        private set

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(VehicleController.State) -> Unit>()

    private var ctx: Context? = null
    private var remote: Messenger? = null
    private var bound = false
    private var bindAttempts = 0

    private val replyMessenger = Messenger(Handler(Looper.getMainLooper()) { msg ->
        when (msg.what) {
            VehicleProtocol.MSG_STATE -> {
                val s = msg.data?.let { VehicleProtocol.decodeState(it) } ?: return@Handler true
                state = s
                available = true
                lastError = ""
                dispatch(s)
            }
            VehicleProtocol.MSG_HELLO -> Logx.d("车控进程已连接")
        }
        true
    })

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            remote = Messenger(service)
            bound = true
            bindAttempts = 0
            Logx.d("车控进程已绑定")
            subscribe()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // 车控进程死了（极可能是 native 崩溃）—— Dock 进程继续活着
            remote = null
            bound = false
            available = false
            diedCount++
            lastError = "车控进程已断开（第 $diedCount 次）"
            Logx.e("⚠ $lastError —— Dock 不受影响，车控功能暂时不可用")
            // 上报：这是定位「车控是否就是闪退元凶」的关键证据
            val c = ctx
            if (c != null) {
                runCatching {
                    LogUploader.upload(
                        c, "car_died",
                        "车控独立进程已断开（第 $diedCount 次）。\n" +
                                "若此记录频繁出现，说明崩溃确实发生在车控侧。\n" +
                                "当前安全等级=${Prefs.safeLevel(c)}",
                        "车控进程断开"
                    )
                }
            }
            // 退避重连：崩得越多等得越久，避免崩溃循环
            val delay = (2000L * diedCount).coerceAtMost(30_000L)
            main.postDelayed({
                val cc = ctx
                if (!bound && cc != null) bind(cc)
            }, delay)
        }

        override fun onBindingDied(name: ComponentName?) {
            onServiceDisconnected(name)
        }
    }

    // =====================================================================

    fun start(c: Context) {
        ctx = c.applicationContext
        bind(ctx!!)
    }

    private fun bind(c: Context) {
        if (bound) return
        if (bindAttempts >= 5) {
            lastError = "车控进程连续启动失败，已停止重试"
            Logx.e(lastError)
            return
        }
        bindAttempts++
        runCatching {
            val intent = Intent(c, VehicleControlService::class.java)
            val ok = c.bindService(intent, conn, Context.BIND_AUTO_CREATE)
            Logx.d("车控进程绑定：$ok（第 $bindAttempts 次）")
            if (!ok) main.postDelayed({ bind(c) }, 3000)
        }.onFailure {
            lastError = "绑定车控进程失败：${it.javaClass.simpleName}"
            Logx.e(lastError, it)
        }
    }

    fun stop() {
        val c = ctx ?: return
        runCatching { if (bound) c.unbindService(conn) }
        bound = false
        remote = null
        available = false
    }

    private fun subscribe() {
        val m = remote ?: return
        runCatching {
            val msg = Message.obtain(null, VehicleProtocol.MSG_SUBSCRIBE)
            msg.replyTo = replyMessenger
            m.send(msg)
        }.onFailure { Logx.e("订阅车控状态失败", it) }
    }

    fun refresh() {
        send(VehicleProtocol.MSG_REFRESH, null)
    }

    private fun send(what: Int, data: Bundle?) {
        val m = remote ?: run {
            Logx.d("车控进程未连接，命令被丢弃（what=$what）")
            return
        }
        runCatching {
            val msg = Message.obtain(null, what)
            if (data != null) msg.data = data
            msg.replyTo = replyMessenger
            m.send(msg)
        }.onFailure {
            Logx.d("发送车控消息失败：${it.javaClass.simpleName}")
        }
    }

    private fun command(cmd: String, configure: (Bundle) -> Unit = {}) {
        val b = Bundle().apply {
            putString(VehicleProtocol.KEY_CMD, cmd)
            configure(this)
        }
        send(VehicleProtocol.MSG_COMMAND, b)
    }

    // =====================================================================
    // 与 VehicleController 同名的高层操作（UI 层直接调用这些）
    // =====================================================================

    fun tempStep(ctx: Context, driver: Boolean, step: Int) = command(VehicleProtocol.CMD_TEMP_STEP) {
        it.putBoolean(VehicleProtocol.KEY_DRIVER, driver)
        it.putInt(VehicleProtocol.KEY_STEP, step)
    }

    fun fanStep(ctx: Context, delta: Int) = command(VehicleProtocol.CMD_FAN_STEP) {
        it.putInt(VehicleProtocol.KEY_STEP, delta)
    }

    fun toggleFanAuto(ctx: Context) = command(VehicleProtocol.CMD_FAN_AUTO)

    fun toggleRecirc(ctx: Context) = command(VehicleProtocol.CMD_RECIRC)

    fun cycleAirDirection(ctx: Context) = command(VehicleProtocol.CMD_AIR)

    fun setSeatVentLevel(ctx: Context, level: Int) = command(VehicleProtocol.CMD_SEAT_VENT) {
        it.putInt(VehicleProtocol.KEY_LEVEL, level)
    }

    fun setSeatHeatLevel(ctx: Context, level: Int) = command(VehicleProtocol.CMD_SEAT_HEAT) {
        it.putInt(VehicleProtocol.KEY_LEVEL, level)
    }

    /** 调试：读一个通道（主界面「车控通道」表用）。结果回到主线程。 */
    fun debugRead(func: Int, zone: Int, onResult: (String) -> Unit) {
        val m = remote
        if (m == null) { onResult("车控进程未连接"); return }
        val cb = Messenger(Handler(Looper.getMainLooper()) { msg ->
            val d = msg.data
            val text = when {
                d == null -> "空回包"
                d.containsKey(VehicleProtocol.KEY_TEXT) -> d.getString(VehicleProtocol.KEY_TEXT) ?: "null"
                else -> "0x%08X (%d)".format(
                    d.getInt(VehicleProtocol.KEY_READ_RESULT),
                    d.getInt(VehicleProtocol.KEY_READ_RESULT)
                )
            }
            onResult(text)
            true
        })
        runCatching {
            val msg = Message.obtain(null, VehicleProtocol.MSG_DEBUG)
            msg.data = Bundle().apply {
                putInt(VehicleProtocol.KEY_FUNC, func)
                putInt(VehicleProtocol.KEY_ZONE, zone)
                putBoolean(VehicleProtocol.KEY_IS_WRITE, false)
            }
            msg.replyTo = cb
            m.send(msg)
        }.onFailure { onResult("发送失败：${it.javaClass.simpleName}") }
    }

    /** 调试：写一个通道 */
    fun debugWrite(func: Int, zone: Int, value: Int, onResult: (String) -> Unit) {
        val m = remote
        if (m == null) { onResult("车控进程未连接"); return }
        val cb = Messenger(Handler(Looper.getMainLooper()) { msg ->
            val ok = msg.data?.getBoolean(VehicleProtocol.KEY_WRITE_OK) ?: false
            onResult(if (ok) "写入成功" else "写入失败")
            true
        })
        runCatching {
            val msg = Message.obtain(null, VehicleProtocol.MSG_DEBUG)
            msg.data = Bundle().apply {
                putInt(VehicleProtocol.KEY_FUNC, func)
                putInt(VehicleProtocol.KEY_ZONE, zone)
                putInt(VehicleProtocol.KEY_VALUE, value)
                putBoolean(VehicleProtocol.KEY_IS_WRITE, true)
            }
            msg.replyTo = cb
            m.send(msg)
        }.onFailure { onResult("发送失败：${it.javaClass.simpleName}") }
    }

    // =====================================================================

    fun addListener(l: (VehicleController.State) -> Unit) {
        synchronized(this) { listeners.add(l) }
        l(state)
    }

    fun removeListener(l: (VehicleController.State) -> Unit) {
        synchronized(this) { listeners.remove(l) }
    }

    private fun dispatch(s: VehicleController.State) {
        main.post {
            val snapshot = runCatching { synchronized(this) { listeners.toList() } }.getOrDefault(emptyList())
            snapshot.forEach { runCatching { it(s) } }
        }
    }

    /** 主界面展示用 */
    fun statusText(): String = when {
        available -> "已连接（独立进程 :car）"
        diedCount > 0 -> "不可用：车控进程已断开 $diedCount 次"
        else -> "连接中…"
    }
}
