package com.car.dockbar.vehicle

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.car.dockbar.config.VehicleChannels
import com.car.dockbar.util.LogUploader
import com.car.dockbar.util.Logx
import com.car.dockbar.vehicle.SensorBridge

/**
 * 车控服务 —— **运行在独立进程 `:car`**。
 *
 * ## 为什么必须独立进程
 *
 * 车机 ECarX adaptapi 是厂商 native 库 + binder 系统服务，普通第三方 App 不在白名单内。
 * 实测本机（Qualcomm CX11 / Android 9）在 Dock 起来后约 1 秒会发生
 * **native SIGSEGV（signal 11, si_code=2 SEGV_ACCERR，fault addr 低位恒为 0xbc0）**，
 * 且崩溃点不在我们的 Java 调用栈上 —— 更像是 adaptapi 内部的后台线程崩了。
 *
 * 由于 native 崩溃会直接干掉整个进程，**唯一可靠的办法是把车控隔离开**：
 * 车控进程崩了，Dock 进程照样活着，用户不再闪退；
 * 同时主进程能通过 [android.content.ServiceConnection.onServiceDisconnected]
 * 感知到「车控进程崩了」并把这件事上传上来。
 *
 * ## 职责
 *  - 持有 [VehicleController]（在本进程内是独立的单例实例）
 *  - 定时刷新车控状态，主动推送给所有已订阅的主进程客户端
 *  - 执行主进程下发的命令（调温 / 风量 / 循环 / 风向 / 座椅）
 *  - 执行主进程下发的调试读写（主界面「车控通道」表）
 */
class VehicleControlService : Service() {

    /** 已订阅的主进程客户端 */
    private val clients = mutableListOf<Messenger>()

    private var worker: HandlerThread? = null
    private var work: Handler? = null

    /** 在主线程回推状态（Messenger 回调需要 Looper） */
    private val main = Handler(android.os.Looper.getMainLooper())

    private val vehicleListener: (VehicleController.State) -> Unit = { st ->
        broadcast(st)
    }

    private val incoming = object : Handler.Callback {
        override fun handleMessage(msg: Message): Boolean {
            when (msg.what) {
                VehicleProtocol.MSG_SUBSCRIBE -> {
                    val m = msg.replyTo
                    if (m != null) {
                        synchronized(clients) {
                            if (clients.none { it.toString() == m.toString() }) clients.add(m)
                        }
                        Logx.d("车控进程：新客户端订阅，当前 ${clients.size} 个")
                        // 立刻回一份当前状态，避免主进程等 2.5 秒
                        sendState(m, VehicleController.state)
                    }
                }
                VehicleProtocol.MSG_REFRESH -> {
                    work?.post { runCatching { VehicleController.refresh(this@VehicleControlService) } }
                }
                VehicleProtocol.MSG_COMMAND -> handleCommand(msg.data)
                VehicleProtocol.MSG_DEBUG -> handleDebug(msg)
            }
            return true
        }
    }

    /** Messenger 需要一个带 Looper 的 Handler；把 Callback 包进去 */
    private val messenger by lazy { Messenger(Handler(Looper.getMainLooper(), incoming)) }

    // =====================================================================

    override fun onCreate() {
        super.onCreate()
        Logx.d("VehicleControlService onCreate（进程 :car）")
        worker = HandlerThread("car-vehicle").apply { start() }
        work = Handler(worker!!.looper)

        // 车控初始化放到本进程的后台线程
        work?.post {
            runCatching { VehicleController.init(this) }
                .onFailure { Logx.e("车控初始化异常（:car 进程）", it) }
            // 上报一条诊断（含安全等级与通道配置），便于远程确认
            runCatching { uploadDiag() }.onFailure { Logx.e("车控诊断上传异常", it) }
        }
        VehicleController.addListener(vehicleListener)
        main.postDelayed(ticker, VehicleProtocol.REFRESH_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        Logx.d("VehicleControlService onDestroy（:car 进程）")
        main.removeCallbacks(ticker)
        VehicleController.removeListener(vehicleListener)
        work?.removeCallbacksAndMessages(null)
        worker?.quitSafely()
        worker = null
        work = null
        super.onDestroy()
    }

    // =====================================================================

    private val ticker = object : Runnable {
        override fun run() {
            work?.post { runCatching { VehicleController.refresh(this@VehicleControlService) } }
            main.postDelayed(this, VehicleProtocol.REFRESH_MS)
        }
    }

    /** 把状态推给所有客户端；推不通的（主进程已死）顺手剔除 */
    private fun broadcast(st: VehicleController.State) {
        val snapshot = synchronized(clients) { clients.toList() }
        if (snapshot.isEmpty()) return
        val dead = ArrayList<Messenger>()
        for (c in snapshot) {
            if (!sendState(c, st)) dead.add(c)
        }
        if (dead.isNotEmpty()) {
            synchronized(clients) { clients.removeAll(dead) }
        }
    }

    private fun sendState(to: Messenger, st: VehicleController.State): Boolean {
        return try {
            val m = Message.obtain(null, VehicleProtocol.MSG_STATE)
            m.data = VehicleProtocol.encodeState(st)
            to.send(m)
            true
        } catch (e: RemoteException) {
            false
        } catch (t: Throwable) {
            Logx.d("车控进程：推送状态失败 ${t.javaClass.simpleName}")
            false
        }
    }

    // =====================================================================

    private fun handleCommand(data: Bundle?) {
        val cmd = data?.getString(VehicleProtocol.KEY_CMD) ?: return
        val ctx = this
        work?.post {
            runCatching {
                when (cmd) {
                    VehicleProtocol.CMD_TEMP_STEP ->
                        VehicleController.tempStep(ctx, data.getBoolean(VehicleProtocol.KEY_DRIVER, true), data.getInt(VehicleProtocol.KEY_STEP, 1))
                    VehicleProtocol.CMD_FAN_STEP ->
                        VehicleController.fanStep(ctx, data.getInt(VehicleProtocol.KEY_STEP, 1))
                    VehicleProtocol.CMD_FAN_AUTO -> VehicleController.toggleFanAuto(ctx)
                    VehicleProtocol.CMD_RECIRC -> VehicleController.toggleRecirc(ctx)
                    VehicleProtocol.CMD_AIR -> VehicleController.cycleAirDirection(ctx)
                    VehicleProtocol.CMD_SEAT_VENT ->
                        VehicleController.setSeatVentLevel(ctx, data.getInt(VehicleProtocol.KEY_LEVEL, 0))
                    VehicleProtocol.CMD_SEAT_HEAT ->
                        VehicleController.setSeatHeatLevel(ctx, data.getInt(VehicleProtocol.KEY_LEVEL, 0))
                }
            }.onFailure { Logx.e("车控命令 $cmd 执行异常", it) }
        }
    }

    private fun handleDebug(msg: Message) {
        val replyTo = msg.replyTo ?: return
        val d = msg.data ?: return
        val func = d.getInt(VehicleProtocol.KEY_FUNC)
        val zone = d.getInt(VehicleProtocol.KEY_ZONE)
        val isWrite = d.getBoolean(VehicleProtocol.KEY_IS_WRITE)
        work?.post {
            val out = Bundle()
            runCatching {
                if (isWrite) {
                    val value = d.getInt(VehicleProtocol.KEY_VALUE)
                    out.putBoolean(VehicleProtocol.KEY_WRITE_OK, VehicleBridge.writeInt(func, zone, value))
                } else {
                    val v = VehicleBridge.readInt(func, zone)
                    if (v == null) out.putString(VehicleProtocol.KEY_TEXT, "null")
                    else out.putInt(VehicleProtocol.KEY_READ_RESULT, v)
                }
            }.onFailure { out.putString(VehicleProtocol.KEY_TEXT, "异常 ${it.javaClass.simpleName}") }
            runCatching {
                val r = Message.obtain(null, VehicleProtocol.MSG_DEBUG)
                r.data = out
                replyTo.send(r)
            }
        }
    }

    // =====================================================================

    private fun uploadDiag() {
        if (!LogUploader.isEnabled(this)) return
        val st = VehicleController.state
        val chans = VehicleChannels.ALL.joinToString("\n") { ch ->
            "  ${ch.key}: func=0x${VehicleChannels.funcOf(this, ch.key).toString(16)}" +
                    " zone=${VehicleChannels.zoneOf(this, ch.key)}"
        }
        val body = buildString {
            append("===== 车控诊断（:car 独立进程）=====\n")
            append("后端: ").append(VehicleBridge.backend.label).append('\n')
            append("温度读通道: ").append(VehicleBridge.floatChannelName).append('\n')
            // ★ v1.4.72：点火状态 + 空气质量（事件量通道）
            append("点火状态: ").append(VehicleController.ignitionLabel()).append('\n')
            append("空气质量(车内/车外): ").append(
                SensorBridge.readAirQuality(true)?.let { "%.0f %s".format(it.first, it.second) } ?: "--"
            ).append(" / ").append(
                SensorBridge.readAirQuality(false)?.let { "%.0f %s".format(it.first, it.second) } ?: "--"
            ).append('\n')
            append("温度写通道: ").append(VehicleBridge.floatWriteChannelName).append('\n')
            append("连接: connected=").append(st.connected).append('\n')
            append("解析: 主驾温=").append(st.driverTempC)
            append(" 副驾温=").append(st.passengerTempC)
            append(" 风速=").append(st.fanLevel).append(if (st.fanAuto) "(AUTO)" else "")
            append(" 循环=").append(if (st.recircInside) "内" else "外")
            append(" 风向=").append(st.airDirection)
            append(" 座椅通风=").append(st.seatVent)
            append(" 座椅加热=").append(st.seatHeat).append('\n')
            append("通道配置:\n").append(chans).append("\n\n")
            append("===== 最近日志 =====\n")
            append(Logx.snapshot().takeLast(150).joinToString("\n"))
        }
        LogUploader.upload(this, "diag", body, "车控初始化完成（独立进程）")
    }
}
