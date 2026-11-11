package com.car.dockbar.media

import android.content.Context
import android.media.AudioManager
import com.car.dockbar.util.Logx

/**
 * 媒体、导航音量。
 *
 * 显示和写入都用 AudioManager 的流索引，与系统同一套数字。
 * DCY11 日志里媒体流 3、导航流 15 的上限都是 39。
 * 不再加减 5：界面 0 写入 0，界面 15 写入 15。
 */
object CabinVolume {

    data class Channel(val title: String, val groupId: Int, val stream: Int)

    val MEDIA = Channel("媒体", 0, 3)
    val NAVI = Channel("导航", 1, 15)

    private val mediaShownListeners = mutableListOf<(Int) -> Unit>()

    fun addMediaShownListener(listener: (Int) -> Unit) {
        if (listener !in mediaShownListeners) mediaShownListeners += listener
    }

    fun removeMediaShownListener(listener: (Int) -> Unit) {
        mediaShownListeners -= listener
    }

    data class Level(val value: Int, val max: Int)

    /** 读到的流索引原样显示，上限用系统自己的 getStreamMaxVolume。 */
    fun read(ctx: Context, channel: Channel): Level {
        val max = streamMax(ctx, channel)
        val raw = streamVolume(ctx, channel).coerceIn(0, max)
        return Level(raw, max)
    }

    fun step(ctx: Context, channel: Channel, delta: Int) {
        val now = read(ctx, channel)
        write(ctx, channel, (now.value + delta).coerceIn(0, now.max))
    }

    /** [shown] 直接写入同号的流索引，不再加 5。 */
    fun write(ctx: Context, channel: Channel, shown: Int) {
        val max = streamMax(ctx, channel)
        val written = shown.coerceIn(0, max)
        runCatching {
            audio(ctx).setStreamVolume(channel.stream, written, 0)
        }.onFailure { Logx.e("音量：setStreamVolume(${channel.stream}) 失败", it) }
        val raw = streamVolume(ctx, channel).coerceIn(0, max)
        if (channel == MEDIA) mediaShownListeners.toList().forEach { it(raw) }
        Logx.d("音量 ${channel.title} 流${channel.stream} 写入 $written 读回 $raw 上限 $max")
    }

    private fun streamMax(ctx: Context, channel: Channel): Int =
        runCatching { audio(ctx).getStreamMaxVolume(channel.stream) }.getOrDefault(1).coerceAtLeast(1)

    private fun streamVolume(ctx: Context, channel: Channel): Int =
        runCatching { audio(ctx).getStreamVolume(channel.stream) }.getOrDefault(0)

    private fun audio(ctx: Context): AudioManager =
        ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
}
