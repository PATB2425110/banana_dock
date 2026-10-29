package com.car.dockbar.util

import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * 极简 adb 客户端：连本机 adbd 执行一条 shell 命令。
 *
 * ## 为什么需要
 *
 * 车机（CX11，userdebug）实测 `getprop service.adb.tcp.port = 5555` 且 **adbd 免 RSA 认证**
 * （`PrivilegeProbe` 发 CNXN 握手直接回 CNXN，不回 AUTH）。
 * 这意味着**普通 App 在本机就能拿到 adb shell**，无需电脑、无需 root、无需用户操作。
 *
 * 拿到 shell 后可以：
 *   - `pm grant <pkg> android.car.permission.CONTROL_CAR_CLIMATE` —— 拿到车控写入权限
 *   - `id` / `su -c id` —— 确认身份，看能不能进一步提权
 *
 * 这正是能控空调的第三方 App（cn.navitool，内置「正在授予 ADB 权限...」流程）在做的事。
 *
 * ## 协议要点（adb/protocol.txt）
 * ```
 * CNXN(version, maxdata, "host::features=...\0")   →  对端回 CNXN（免认证）或 AUTH（需签名）
 * OPEN(local-id, 0, "shell:<cmd>\0")               →  对端回 OKAY(remote-id, local-id)
 * WRTE(...)  +data                                  →  收到后需回一个 OKAY 确认
 * CLSE(...)                                         →  stream 结束
 * ```
 * 消息头 24 字节：command / arg0 / arg1 / data_length / data_crc32 / magic(=cmd^0xffffffff)，
 * 全部 little-endian。
 */
object AdbClient {

    private const val A_CNXN = 0x4e584e43
    private const val A_OPEN = 0x4e45504f
    private const val A_OKAY = 0x59414b4f
    private const val A_WRTE = 0x45545257
    private const val A_CLSE = 0x45534c43

    private const val VERSION = 0x01000000
    private const val MAXDATA = 4096

    data class Result(val ok: Boolean, val output: String, val error: String = "") {
        val text: String get() = output.ifBlank { error.ifBlank { "(无输出)" } }
    }

    /** 在指定端口执行一条 shell 命令。只干一件事，失败也绝不抛异常。 */
    fun exec(port: Int, cmd: String, timeoutMs: Int = 10_000): Result {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 3_000)
                s.soTimeout = timeoutMs
                val out = s.getOutputStream()
                val inp = DataInputStream(s.getInputStream())

                // ---- 1. 握手 ----
                out.write(pack(A_CNXN, VERSION, MAXDATA, "host::features=shell_v2,cmd\u0000"))
                out.flush()
                val hello = read(inp) ?: return Result(false, "", "握手无响应")
                if (hello.cmd == 0x48545541) {          // AUTH
                    return Result(false, "", "adbd 要求 RSA 认证，本机无已授权 key")
                }
                if (hello.cmd != A_CNXN) {
                    return Result(false, "", "握手失败，回 0x${Integer.toHexString(hello.cmd)}")
                }

                // ---- 2. 开 stream 执行 shell ----
                val localId = 1
                out.write(pack(A_OPEN, localId, 0, "shell:$cmd\u0000"))
                out.flush()

                val sb = StringBuilder()
                var peerId = 0
                while (true) {
                    val m = read(inp) ?: break
                    when (m.cmd) {
                        A_OKAY -> if (peerId == 0) peerId = m.arg0
                        A_WRTE -> {
                            sb.append(String(m.data, Charsets.UTF_8))
                            // 必须回 OKAY，否则 adbd 会停止推送后续输出
                            out.write(pack(A_OKAY, localId, peerId, null))
                            out.flush()
                        }
                        A_CLSE -> {
                            runCatching {
                                out.write(pack(A_CLSE, localId, peerId, null))
                                out.flush()
                            }
                            break
                        }
                        else -> Unit
                    }
                }
                Result(true, sb.toString())
            }
        } catch (t: Throwable) {
            Result(false, "", "${t.javaClass.simpleName}: ${t.message}")
        }
    }

    // ---------------- 协议编解码 ----------------

    private class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    private fun pack(cmd: Int, arg0: Int, arg1: Int, payload: String?): ByteArray {
        val d = payload?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0)
        val crc = CRC32().apply { update(d) }.value
        return ByteBuffer.allocate(24 + d.size).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(cmd)
            putInt(arg0)
            putInt(arg1)
            putInt(d.size)
            putInt(crc.toInt())
            putInt(cmd xor -1)
            put(d)
        }.array()
    }

    private fun read(inp: DataInputStream): Msg? {
        val hdr = ByteArray(24)
        inp.readFully(hdr)
        val b = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int
        val arg0 = b.int
        val arg1 = b.int
        val len = b.int
        b.int                       // crc，不校验
        b.int                       // magic，不校验
        val data = if (len > 0) ByteArray(len).also { inp.readFully(it) } else ByteArray(0)
        return Msg(cmd, arg0, arg1, data)
    }
}
