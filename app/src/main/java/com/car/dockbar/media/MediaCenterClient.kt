package com.car.dockbar.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.IBinder
import android.os.Parcel
import com.car.dockbar.util.Logx

/**
 * 吉利媒体中心。帆书（FANDENG）和 QQ 音乐（QQMUSIC）都把当前曲目写在这里，
 * 仪表读的也是这一份。不依赖 oneos jar，按反编译的 AIDL 交易号调用。
 *
 * 服务：`com.geely.service.oneosapi.OneOSApiService`
 * 媒体中心是 IServiceManager.getService(3)。
 */
object MediaCenterClient {

    private const val PKG = "com.geely.service.oneosapi"
    private const val CLS = "com.geely.service.oneosapi.OneOSApiService"
    private const val SVC_DESC = "com.geely.lib.oneosapi.IServiceManager"
    private const val CENTER_DESC = "com.geely.lib.oneosapi.mediacenter.IMediaCenter"
    private const val MUSIC_DESC = "com.geely.lib.oneosapi.mediacenter.IMusicManager"
    private const val LISTENER_DESC = "com.geely.lib.oneosapi.mediacenter.listener.IMusicStateListener"

    /** IServiceManager.getService，媒体中心编号 */
    private const val SERVICE_MEDIA_CENTER = 3

    /** AudioSource：在线 / USB / 蓝牙 / 云听。QQ 和帆书都在在线源下面。 */
    private val LISTEN_SOURCES = intArrayOf(4, 1, 2, 6)

    private val APP_NAMES = arrayOf(
        "未知", "随行", "酷我", "酷狗", "网易云", "搜狐", "今日", "QYS", "其他", "帆书",
        "腾讯视频", "喜马拉雅", "GC", "火山", "哔哩哔哩", "雷石", "咪咕", "QQ音乐", "浏览器"
    )

    private val APP_PKG = mapOf(
        9 to "io.dushu.car",
        17 to "com.flyme.auto.music",
        4 to "com.netease.cloudmusic.iot",
        3 to "com.kugou.android.auto"
    )

    private var appCtx: Context? = null
    private var bound = false
    private var music: IBinder? = null
    private var audioSource = 4
    private var appSource = -1
    private var playing = false

    private val death = IBinder.DeathRecipient {
        Logx.w("媒体中心：连接断开，准备重绑")
        music = null
        appCtx?.let { ctx ->
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ bind(ctx) }, 2000)
        }
    }

    private val listener = object : android.os.Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(LISTENER_DESC)
                return true
            }
            return try {
                data.enforceInterface(LISTENER_DESC)
                when (code) {
                    1 -> {
                        val source = data.readInt()
                        val track = if (data.readInt() != 0) readMedia(data) else null
                        reply?.writeNoException()
                        if (track != null) {
                            track.sourceAudio = source
                            deliver(source, track, null)
                        }
                    }
                    3 -> {
                        val source = data.readInt()
                        val state = data.readInt()
                        reply?.writeNoException()
                        deliver(source, null, state)
                    }
                    else -> reply?.writeNoException()
                }
                true
            } catch (t: Throwable) {
                Logx.e("媒体中心：回调解析失败", t)
                true
            }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Logx.d("媒体中心：OneOSApi 已连接")
            service ?: return
            runCatching { service.linkToDeath(death, 0) }
            val center = transactBinder(service, SVC_DESC, 2, SERVICE_MEDIA_CENTER)
            if (center == null) {
                Logx.e("媒体中心：getService(3) 没有拿到 IMediaCenter")
                return
            }
            val mgr = transactBinder(center, CENTER_DESC, 5, null)
            if (mgr == null) {
                Logx.e("媒体中心：getMusicManager 失败")
                return
            }
            music = mgr
            audioSource = transactInt(center, CENTER_DESC, 1) ?: 4
            LISTEN_SOURCES.forEach { src ->
                val ok = transactVoid(mgr, 1) { it.writeInt(src); it.writeStrongBinder(listener) }
                Logx.d("媒体中心：监听音源 $src → $ok")
            }
            pull()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            music = null
            Logx.w("媒体中心：服务断开")
        }
    }

    fun start(ctx: Context) {
        if (bound) return
        appCtx = ctx.applicationContext
        bind(ctx.applicationContext)
    }

    fun hasTrack(): Boolean = appSource >= 0

    fun playerPackage(): String? = APP_PKG[appSource]

    fun toggle() {
        val mgr = music ?: return
        val code = if (playing) 5 else 4
        val ok = transactVoid(mgr, code) { it.writeInt(audioSource) }
        Logx.d("媒体中心：${if (playing) "暂停" else "播放"} 音源=$audioSource → $ok")
    }

    private fun bind(ctx: Context) {
        val intent = Intent().setClassName(PKG, CLS)
        bound = runCatching { ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
            .getOrElse {
                Logx.e("媒体中心：绑定异常 ${it.javaClass.simpleName}: ${it.message}")
                false
            }
        Logx.d("媒体中心：bind $PKG → $bound")
        if (!bound) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (music == null) bind(ctx)
            }, 3000)
        }
    }

    private fun pull() {
        val mgr = music ?: return
        val track = readCurrent(mgr, audioSource) ?: run {
            var found: Track? = null
            for (src in LISTEN_SOURCES) {
                found = readCurrent(mgr, src)
                if (found != null) break
            }
            found
        }
        if (track == null) {
            Logx.d("媒体中心：当前没有曲目（音源=$audioSource）")
            return
        }
        val state = transactIntArg(mgr, 15, track.sourceAudio) ?: 0
        deliver(track.sourceAudio, track, state)
    }

    private fun readCurrent(mgr: IBinder, source: Int): Track? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MUSIC_DESC)
            data.writeInt(source)
            if (!mgr.transact(17, data, reply, 0)) return null
            reply.readException()
            if (reply.readInt() == 0) null else readMedia(reply).also { it.sourceAudio = source }
        } catch (t: Throwable) {
            Logx.e("媒体中心：读当前曲目失败 source=$source", t)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun deliver(source: Int, track: Track?, state: Int?) {
        if (state != null) {
            audioSource = source
            playing = state == 0
        }
        if (track == null) {
            if (state != null) MediaBridge.onCenterState(playing)
            return
        }
        audioSource = track.sourceAudio
        appSource = track.appSource
        val label = APP_NAMES.getOrElse(track.appSource) { "音源${track.appSource}" }
        if (track.name.isBlank()) return
        MediaBridge.onCenterTrack(
            title = track.name,
            artist = track.artist,
            album = track.album,
            art = track.art,
            playing = playing,
            sourceLabel = label
        )
    }

    private fun readMedia(p: Parcel): Track {
        val id = p.readString()
        val artist = p.readString().orEmpty()
        val name = p.readString().orEmpty()
        p.readLong()
        p.readString()
        val art = runCatching { p.readParcelable<Bitmap>(Bitmap::class.java.classLoader) }.getOrNull()
        p.readString()
        val album = p.readString().orEmpty()
        val app = p.readInt()
        return Track(name, artist, album, art, app).also { it.unusedId = id }
    }

    private fun transactBinder(remote: IBinder, desc: String, code: Int, arg: Int?): IBinder? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(desc)
            if (arg != null) data.writeInt(arg)
            if (!remote.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.readStrongBinder()
        } catch (t: Throwable) {
            Logx.e("媒体中心：transact $code 失败", t)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactInt(remote: IBinder, desc: String, code: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(desc)
            if (!remote.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (t: Throwable) {
            Logx.e("媒体中心：读 int $code 失败", t)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactIntArg(remote: IBinder, code: Int, arg: Int): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MUSIC_DESC)
            data.writeInt(arg)
            if (!remote.transact(code, data, reply, 0)) return null
            reply.readException()
            reply.readInt()
        } catch (t: Throwable) {
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactVoid(remote: IBinder, code: Int, fill: (Parcel) -> Unit): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MUSIC_DESC)
            fill(data)
            val ok = remote.transact(code, data, reply, 0)
            if (ok) reply.readException()
            ok
        } catch (t: Throwable) {
            Logx.e("媒体中心：调用 $code 失败", t)
            false
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private class Track(
        val name: String,
        val artist: String,
        val album: String,
        val art: Bitmap?,
        val appSource: Int
    ) {
        var sourceAudio: Int = 4
        var unusedId: String? = null
    }
}
