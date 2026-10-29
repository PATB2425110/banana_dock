package com.car.dockbar.media

import java.util.regex.Pattern

/**
 * LRC 歌词解析（移植自 LyricFloat `LrcParser`）。
 *
 * 支持：
 *  - `[mm:ss.xx]` / `[mm:ss:xx]` 时间标签
 *  - 一行多个时间标签（同一句重复出现）
 *  - `[offset:±ms]` 全局偏移
 *  - 逐字标签 `<mm:ss.xx>`（解析时剥掉，只留文本）
 *  - `[ti:] [ar:] [al:] [by:] [re:] [ve:]` 元信息行跳过
 */
object LrcParser {

    data class Line(val timeMs: Long, val text: String)

    private val TIME: Pattern = Pattern.compile("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?\\]")
    private val WORD: Pattern = Pattern.compile("<\\d{1,3}:\\d{1,2}(?:[.:]\\d{1,3})?>")
    private val OFFSET: Pattern =
        Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*\\]", Pattern.CASE_INSENSITIVE)

    /** 解析结果：时间轴升序；无时间标签时返回 null */
    fun parse(lrc: String?): List<Line>? {
        if (lrc.isNullOrEmpty()) return null
        val src = lrc.replace("\r\n", "\n").replace('\r', '\n')
        val rawLines = src.split("\n")

        var globalOffset = 0L
        val out = ArrayList<Line>()

        for (raw in rawLines) {
            val line = raw.trim()
            if (line.isEmpty()) continue

            val om = OFFSET.matcher(line)
            if (om.find()) {
                globalOffset = om.group(1)?.toLongOrNull() ?: globalOffset
                continue
            }
            if (line.startsWith("[ti:") || line.startsWith("[ar:") || line.startsWith("[al:") ||
                line.startsWith("[by:") || line.startsWith("[re:") || line.startsWith("[ve:")
            ) continue

            val m = TIME.matcher(line)
            val times = ArrayList<Long>()
            var lastEnd = 0
            while (m.find()) {
                lastEnd = m.end()
                val min = m.group(1)?.toLongOrNull() ?: continue
                val sec = m.group(2)?.toLongOrNull() ?: continue
                val frac = m.group(3)
                val ms = when {
                    frac.isNullOrEmpty() -> 0L
                    frac.length == 2 -> frac.toLong() * 10
                    else -> frac.toLong()
                }
                times.add(min * 60000L + sec * 1000L + ms)
            }
            if (times.isEmpty()) continue

            var body = line.substring(lastEnd).trim()
            body = WORD.matcher(body).replaceAll("").trim()
            if (body.isEmpty()) continue

            for (t in times) out.add(Line(t + globalOffset, body))
        }

        if (out.isEmpty()) return null
        out.sortBy { it.timeMs }
        return out
    }

    /** 返回 posMs 对应的行下标；pos 早于第一行时返回 -1 */
    fun indexFor(lines: List<Line>?, posMs: Long): Int {
        if (lines.isNullOrEmpty()) return -1
        if (posMs < lines[0].timeMs) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /** 是否带时间轴 */
    fun hasTimeline(lrc: String?): Boolean = lrc != null && TIME.matcher(lrc).find()
}
