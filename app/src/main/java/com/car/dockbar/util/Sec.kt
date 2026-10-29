package com.car.dockbar.util

/**
 * ★ v3.13.6：字符串加密工具。
 *
 * 背景：R8 混淆能改类名/方法名，但**字符串常量是明文**——
 *  反编译后一眼就能看到日志上传接口地址、车机 SDK 类名（`com.ecarx.xui.adaptapi.car.Car`）、
 *  各车机 App 的包名。这些正是"接口"本身，必须藏起来。
 *
 * 做法：编译期把明文用 **XOR + Base64** 编码成密文常量，
 *  运行时首次使用时解码并缓存（用完即用，不长期驻留）。
 * 密钥只有一份且分散在数组里，配合混淆后逆向成本显著提高。
 *
 * ⚠ 重要：**不能**改 `const val`（编译期常量会被内联进调用点，等于没加密）。
 *    所以敏感字符串一律改成 `@JvmStatic val`（运行时读取）或 `fun s(): String`。
 */
object Sec {

    /**
     * 密钥表：每个字节参与一次 XOR。
     * 用 `private val`（非 const）避免被编译期常量折叠。
     */
    private val KEY = intArrayOf(
        0x5A, 0x17, 0xC3, 0x2E, 0x9B, 0x64, 0x38, 0xF1,
        0x4D, 0xA7, 0x12, 0xE5, 0x76, 0x8B, 0x03, 0xD9
    )

    private val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Base64 解码表（-1 = 非法字符） */
    private val B64_DEC = IntArray(128) { -1 }.also { t ->
        B64.forEachIndexed { i, c -> t[c.code] = i }
    }

    /**
     * 编码（**仅供构建期/工具使用**，运行时不需要）。
     * 用法：`Sec.enc("com.geely.hvac")` → 得到可粘贴进源码的密文串。
     */
    fun enc(plain: String): String {
        val bytes = plain.toByteArray(Charsets.UTF_8)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor KEY[i % KEY.size]).toByte()
        }
        return b64encode(out)
    }

    /**
     * 解码。传密文常量，返回明文。
     * @param cacheKey 缓存键；同一个密文只解一次，结果驻留内存。
     */
    fun dec(cipher: String, cacheKey: String): String = cache.getOrPut(cacheKey) {
        decRaw(cipher)
    }

    private val cache = HashMap<String, String>()

    // =====================================================================
    // 车机 SDK 反射方法名（加密）
    //
    // 这些名字在运行时必须**精确还原**才能反射成功，
    // 但它们出现在 dex 里就等于公开了 SDK 的接口清单。
    // 统一走这里取，用法：Sec.m(Sec.M_SETFUNCTIONVALUE)
    // =====================================================================
    const val M_GETFLOATPROPERTY = "PXK3aPcLWYUd1X2VE/l3oA=="
    const val M_SETFLOATPROPERTY = "KXK3aPcLWYUd1X2VE/l3oA=="
    const val M_GETINTPROPERTY = "PXK3Z/UQaIMi13eXAvI="
    const val M_SETINTPROPERTY = "KXK3Z/UQaIMi13eXAvI="
    const val M_GETFUNCTIONVALUE = "PXK3aO4KW4UkyHyzF+d2vA=="
    const val M_SETFUNCTIONVALUE = "KXK3aO4KW4UkyHyzF+d2vA=="
    const val M_UNREGISTERFUNCTIONVALUEWATCHER = "L3mxS/wNS4Uo1VSQGOh3sDV5lU/3EV2mLNNxjRP5"
    const val M_UNREGISTER = "L3mxS/wNS4Uo1Q=="
    const val M_REGISTERFUNCTIONVALUEWATCHER = "KHKkR+gQXYML0nyGAuJstwx2r1v+M1mFLs93lw=="
    const val M_SETCUSTOMIZEFUNCTIONVALUE = "KXK3be4XTJ4gzmiAMP5tui5+rEDNBVSEKA=="
    const val M_GETCUSTOMIZEFUNCTIONVALUE = "PXK3be4XTJ4gzmiAMP5tui5+rEDNBVSEKA=="
    const val M_ISFUNCTIONSUPPORTED = "M2SFW/UHTJgiyUGQBvtsqy5ypw=="
    const val M_CREATE = "OWWmT+8B"
    const val M_GETICARFUNCTION = "PXK3Z9gFSrc4yXGRH+Rt"

    /** 解出反射方法名（带缓存） */
    fun m(cipher: String): String = dec(cipher, "m_$cipher")

    private fun decRaw(cipher: String): String {
        val bytes = b64decode(cipher)
        val out = ByteArray(bytes.size)
        for (i in bytes.indices) {
            out[i] = (bytes[i].toInt() xor KEY[i % KEY.size]).toByte()
        }
        return String(out, Charsets.UTF_8)
    }

    private fun b64encode(data: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(B64[(n ushr 18) and 0x3F])
            sb.append(B64[(n ushr 12) and 0x3F])
            sb.append(B64[(n ushr 6) and 0x3F])
            sb.append(B64[n and 0x3F])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(B64[(n ushr 18) and 0x3F])
                sb.append(B64[(n ushr 12) and 0x3F])
                sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or
                    ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(B64[(n ushr 18) and 0x3F])
                sb.append(B64[(n ushr 12) and 0x3F])
                sb.append(B64[(n ushr 6) and 0x3F])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    private fun b64decode(s: String): ByteArray {
        val clean = StringBuilder(s.length)
        for (c in s) if (c != '=' && c != '\n' && c != '\r') clean.append(c)
        val n = clean.length
        val outSize = n * 3 / 4
        val out = ByteArray(outSize)
        var acc = 0
        var bits = 0
        var o = 0
        for (c in clean) {
            val code = c.code
            if (code >= 128) continue
            val v = B64_DEC[code]
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = ((acc ushr bits) and 0xFF).toByte()
            }
        }
        return if (o == outSize) out else out.copyOf(o)
    }
}
