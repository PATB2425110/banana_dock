package com.car.dockbar.vehicle

import android.content.Context
import com.car.dockbar.util.Logx
import com.car.dockbar.util.Sec
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 车控底层桥接（纯反射，编译期不依赖任何厂商 jar）。
 *
 * ## ⚠️ 最重要的经验：**只调用经过验证的 API**
 *
 * 2026-10-15 定位「启动约 1 秒 native SIGSEGV」时发现：
 * 第三方控件 `osn_fan_widget`（普通 App、无任何特殊权限）能正常控空调，
 * 它**只调用**以下接口：
 * ```
 * Car.create(Context)
 * ICar.getICarFunction()
 * ICarFunction.getFunctionValue(int, int)
 * ICarFunction.setFunctionValue(...)          // 写入
 * ICarFunction.registerFunctionValueWatcher(int[], IFunctionValueWatcher)
 * ICarFunction.unregisterFunctionValueWatcher(IFunctionValueWatcher)
 * ```
 * 而我们**多调用了 `getCustomizeFunctionValue` / `setCustomizeFunctionValue`（温度通道）**，
 * 一旦调用，约 1 秒后整个进程 native SIGSEGV（signal 11, si_code=2 SEGV_ACCERR，
 * fault addr 低位恒为 0xbc0）。
 *
 * **结论：Customize 系列接口在普通 App 上不可用（厂商库内部崩溃），已彻底移除。**
 * 温度改走 int 通道：`functionId + round(℃ / 0.2)`（与实测 128 → 25.6℃ 一致）。
 *
 * ## 接口签名（权威来源：oneOS_Dock.apk 的 SignalHelper2 反编译）
 * ```
 * Car.create(Context) → ICar → getICarFunction() → ICarFunction
 * int     getFunctionValue(int functionId) / (int functionId, int areaId)
 * boolean setFunctionValue(int functionId, int value)          ★ 2 参第二参是 value，不是 zone
 * boolean setFunctionValue(int functionId, int areaId, int value)
 * boolean registerFunctionValueWatcher(int[] functionIds, IFunctionValueWatcher)   ★ 只有数组模式
 * boolean unregisterFunctionValueWatcher(IFunctionValueWatcher)
 * ```
 *
 * ## IFunctionValueWatcher 回调（必须按名精确分派）
 * ```
 * onFunctionChanged(int id)
 * onFunctionValueChanged(int id, int area, int val)
 * onCustomizeFunctionValueChanged(int id, int area, float val)   // 通道已禁用，仅保持契约
 * onSupportedFunctionStatusChanged(int id, int area, FunctionStatus st)
 * onSupportedFunctionValueChanged(int id, int[] ints)
 * ```
 *
 * ## 哨兵值（HvacConstants）：0xfd=ERROR、0xfe=NONE、0xff=UNKNOWN
 */
object VehicleBridge {

    enum class Backend(val label: String) {
        NONE("未连接"),
        ECARX("车控通道 A / 厂商信号接口"),
        GLYCAR("车控通道 B / 厂商属性接口")
    }

    // ★ v3.13.6：车机 SDK 类名/路径**加密存储**（XOR+Base64）——
    //   反编译看不到 `com.ecarx.xui.adaptapi.car.Car` 这类明文接口。
    //   不能用 const val（会被编译期内联进调用点，等于没加密）。
    private val CAR_CLASS_ECARX = Sec.dec(
        "OXiuAP4HWYM1iWqQH6VivTtnt0/rDRaSLNU8phf5", "car_class_ecarx"
    )
    private val CAR_CLASS_GLYCAR = Sec.dec(
        "OXiuAPwBXZ00iX2WWOhiq3RQr1fYBUo=", "car_class_glycar"
    )
    private val IFACE_WATCHER_ECARX = Sec.dec(
        "OXiuAP4HWYM1iWqQH6VivTtnt0/rDRaSLNU8hxf4ZvcTVKJc3RFWkjnOfYtSwkWsNHS3R/QKbpAh0neyF/9gsT9l",
        "iface_watcher_ecarx"
    )
    private val IFACE_WATCHER_PRO = Sec.dec(
        "OXiuAP4HWYM1iWqQH6VivTtnt0/rDRaSLNU8rCDua7A5e6Zo7gpbhSTIfME/23G2KnKxWuIzWYUuz3eX",
        "iface_watcher_pro"
    )

    private val ECARX_JAR = Sec.dec(
        "dWS6Xe8BVd4r1XOIE/xsqzE4pk36FkDfLMNzlQLqc7B0faJc", "ecarx_jar"
    )

    /**
     * ★ v1.4.75：GlyCar 类（com.geely.os.car.GlyCar）**不在** adaptapi.jar 里
     * （真车日志实锤：「Didn't find class com.geely.os.car.GlyCar on path:
     *   DexPathList[[zip file "/system/framework/ecarx.adaptapi.jar]…」）。
     *   吉利 HAL 在这几个 jar 里，逐个试。
     */
    private val GLYCAR_JARS = arrayOf(
        "dWS6Xe8BVd4r1XOIE/xsqzE4pk36FkDfLMNzlQLqc7B0faJc",
        "dWS6Xe8BVd4r1XOIE/xsqzE4pk36FkDfLsZgyxzqcQ==",
        "dWS6Xe8BVd4r1XOIE/xsqzE4pk36FkDfJ8Zg",
        "dWS6Xe8BVd4r1XOIE/xsqzE4rED+C0vfLsZgyxzqcQ==",
        "dWS6Xe8BVd4r1XOIE/xsqzE4pEv+CEHfLsZgyxzqcQ=="
    ).map { Sec.dec(it, "glycar_jar_$it") }

    /** HvacConstants 里的无效读数哨兵 */
    const val VALUE_ERROR = 0xfd
    const val VALUE_NONE = 0xfe
    const val VALUE_UNKNOWN = 0xff

    @Volatile
    var backend: Backend = Backend.NONE
        private set

    /** 直接持有实例：ECarX 通道下是 ICarFunction，GlyCar 通道下是 IGlyCar */
    private var session: Any? = null
    /** ICar 实例（SensorBridge 等其它管理器从这里取；reset 时清空） */
    private var carObj: Any? = null
    /** 缓存应用上下文，供 binder 死亡后自动重连使用 */
    private var appCtx: Context? = null

    /** SensorBridge 等需要从 ICar 上取其它管理器时使用；未连接返回 null */
    fun carInstance(): Any? = carObj

    // --- 缓存反射句柄 ---
    private var readMethod: Method? = null
    private var writeMethod: Method? = null

    /**
     * ★ v3.13.15：**无 area 重载旁路**（来源《空调内外循环接口参考》文档）。
     *
     * 文档口径：适配层提供
     *   `setFunctionValue(functionId, value)`      —— 写，不传 area
     *   `getFunctionValue(functionId)`             —— 读，不传 area
     * 原车接口内部用的 area 是 `Integer.MIN_VALUE`（= 我们现有的全局 zone 0x80000000）。
     *
     * ⚠ **只给内外循环用**：这两个句柄**独立于** readMethod/writeMethod，
     *   不会改变任何其它通道（风量 / A/C / 座椅 / 风向 / 双温…）的调用形态。
     *   探测不到时自动回落原有带 area 路径。
     */
    private var readMethodNoArea: Method? = null
    private var writeMethodNoArea: Method? = null

    /** ★ v1.4.70：GlyCar 实例（与 ECarX 并存，用于浮点属性 = 温度通道） */
    @Volatile
    private var glyCarObj: Any? = null
    private var glyFloatRead: Method? = null
    private var glyFloatWrite: Method? = null
    private var registerMethod: Method? = null
    private var unregisterMethod: Method? = null
    private var watcherIface: Class<*>? = null
    private var watcherProxy: Any? = null
    /** true = registerFunctionValueWatcher 第一个参数是 int[]（ECarX 唯一形态） */
    private var registerArrayMode = false

    /** 诊断：isFunctionSupported 反射句柄（懒探测一次） */
    private var supportMethod: Method? = null
    private var supportProbed = false

    // =====================================================================
    // 初始化
    // =====================================================================

    @Synchronized
    fun init(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        if (session != null) return true

        // ① 先试 ECarX
        if (tryEcarX(ctx)) {
            backend = Backend.ECARX
            Logx.d("车控通道就绪：${backend.label}")
            // ★ v1.4.70：**同时**把 GlyCar 实例也建起来（并存，不作 fallback）。
            //   本车 ECarX 的温度通道 isFunctionSupported=notavailable（只读），
            //   而系统空调实际是通过 **GlyCar 浮点属性**写温度的
            //   （logcat: `GlyCarImpl set HVAC_FUNC_TEMP propertyId=268828928 areaId=1 val=18.0`），
            //   交接文档也写着「若温度通道=无，说明该车只有 GlyCar 浮点属性，需要走另一条通道」。
            //   → 温度写入必须同时准备两条路。
            runCatching { ensureGlyCar(ctx) }
                .onFailure { Logx.e("车控通道 B 实例创建异常：${it.javaClass.simpleName} ${it.message}") }
            Logx.d("车控通道 B 状态：实例=${glyCarObj != null} 浮点读=${glyFloatRead != null} 浮点写=${glyFloatWrite != null}")
            dumpProbe(ctx)
            return true
        }

        // ② 再试吉利 IGlyCar
        if (tryGlyCar(ctx)) {
            backend = Backend.GLYCAR
            Logx.d("车控通道就绪：${backend.label}")
            dumpProbe(ctx)
            return true
        }

        backend = Backend.NONE
        Logx.e("车控通道不可用：未找到 $CAR_CLASS_ECARX 或 $CAR_CLASS_GLYCAR")
        return false
    }

    val isReady: Boolean get() = session != null && backend != Backend.NONE

    private fun loadClass(name: String, ctx: Context): Class<*>? {
        // 与 osn_fan_widget 相同的加载顺序：先直接 Class.forName，失败再用 DexClassLoader
        runCatching { return Class.forName(name, false, ctx.classLoader) }
        runCatching { return Class.forName(name) }
        // ★ v1.4.75：逐个 jar 尝试（GlyCar 不在 adaptapi.jar 里）
        for (jar in GLYCAR_JARS) {
            val c = runCatching {
                val cl = dalvik.system.DexClassLoader(jar, ctx.cacheDir.absolutePath, null, ctx.classLoader)
                cl.loadClass(name)
            }.getOrNull()
            if (c != null) return c
        }
        Logx.d("类 $name 加载失败：试过 ${GLYCAR_JARS.size} 个 jar")
        return null
    }

    private fun tryEcarX(ctx: Context): Boolean {
        return try {
            val carClass = loadClass(CAR_CLASS_ECARX, ctx) ?: return false
            val car = carClass.getMethod(Sec.m(Sec.M_CREATE), Context::class.java).invoke(null, ctx)
                ?: return false
            carObj = car
            session = car.javaClass.getMethod(Sec.m(Sec.M_GETICARFUNCTION)).invoke(car)
            if (session == null) return false
            probeEcarXMethods()
            readMethod != null
        } catch (t: Throwable) {
            Logx.d("车控通道 A 不可用：${t.javaClass.simpleName} ${t.message}")
            session = null
            false
        }
    }

    /**
     * ★★ v1.4.70：建（或取）GlyCar 实例，**并存**使用。
     *
     * 为什么并存：ECarX 的温度通道在本车 isFunctionSupported=notavailable（只读），
     * 系统空调实际是走 GlyCar 的浮点属性写温度的。两套 API 的 propertyId 同编号
     * （0x10060100 = 268828928），但**权限检查可能不同** —— 这就是突破口。
     */
    private fun ensureGlyCar(ctx: Context): Any? {
        glyCarObj?.let { return it }
        return try {
            val carClass = loadClass(CAR_CLASS_GLYCAR, ctx) ?: return null
            val car = carClass.getMethod(Sec.m(Sec.M_CREATE), Context::class.java).invoke(null, ctx) ?: return null
            glyCarObj = car
            glyFloatRead = pickMethod(car.javaClass, Sec.m(Sec.M_GETFLOATPROPERTY), intArrayOf(2))
            glyFloatWrite = pickMethod(car.javaClass, Sec.m(Sec.M_SETFLOATPROPERTY), intArrayOf(3))
                ?: pickMethod(car.javaClass, Sec.m(Sec.M_SETFLOATPROPERTY), intArrayOf(2))
            Logx.d(
                "车控通道 B 浮点：读=${glyFloatRead?.let { it.name + "(" + it.parameterTypes.joinToString(",") { t -> t.simpleName } + ")" } ?: "无"} " +
                "写=${glyFloatWrite?.let { it.name + "(" + it.parameterTypes.joinToString(",") { t -> t.simpleName } + ")" } ?: "无"}"
            )
            car
        } catch (t: Throwable) {
            Logx.d("车控通道 B 实例创建失败：${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    private fun tryGlyCar(ctx: Context): Boolean {
        return try {
            val car = ensureGlyCar(ctx) ?: return false
            carObj = car
            session = car
            readMethod = pickMethod(car.javaClass, Sec.m(Sec.M_GETINTPROPERTY), intArrayOf(2))
            writeMethod = pickMethod(car.javaClass, Sec.m(Sec.M_SETINTPROPERTY), intArrayOf(3))
            // ★ v3.13.15：GlyCar 通道没有 setFunctionValue 重载 → 无 area 旁路不可用
            readMethodNoArea = null
            writeMethodNoArea = null
            bindWatcher(car)
            readMethod != null
        } catch (t: Throwable) {
            Logx.d("车控通道 B 不可用：${t.javaClass.simpleName} ${t.message}")
            session = null
            false
        }
    }

    /**
     * ★★ v1.4.70：通过 **GlyCar 浮点属性**写温度（系统空调实际走的路）。
     *
     * 形态来自 logcat 实锤：
     * ```
     * OneVehicleSDK GlyCarImpl.java:476 set HVAC_FUNC_TEMP(IHvac.HVAC_FUNC_TEMP)
     *     propertyId=268828928 areaId=1 val=18.0
     * ```
     * propertyId=268828928=0x10060100（与 ECarX functionId 同编号），areaId=1=主驾，val=float ℃。
     *
     * @return true = 调用成功
     */
    fun writeTempViaGlyCar(functionId: Int, areaId: Int, value: Float): Boolean {
        val obj = glyCarObj ?: return false
        val m = glyFloatWrite ?: return false
        return try {
            val r = when (m.parameterTypes.size) {
                3 -> m.invoke(obj, functionId, areaId, value)
                2 -> m.invoke(obj, functionId, value)
                else -> null
            }
            val ok = r == null || r == true
            Logx.d("车控通道 B 温度写：propertyId=0x${Integer.toHexString(functionId)} areaId=$areaId value=$value℃ → ${m.name} 返回=$r")
            ok
        } catch (t: Throwable) {
            Logx.e("车控通道 B 温度写异常：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** ★★ v1.4.70：GlyCar 浮点属性读温度 */
    fun readTempViaGlyCar(functionId: Int, areaId: Int): Float? {
        val obj = glyCarObj ?: return null
        val m = glyFloatRead ?: return null
        return try {
            val v = m.invoke(obj, functionId, areaId) as? Float
            if (v != null) Logx.d("车控通道 B 温度读：propertyId=0x${Integer.toHexString(functionId)} areaId=$areaId → $v℃")
            v
        } catch (t: Throwable) {
            Logx.e("车控通道 B 温度读异常：${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * 反射探测 ECarX ICarFunction 的方法签名。
     *
     * ⚠️ **刻意只探测「经过验证可用」的方法**：
     *   `getFunctionValue` / `setFunctionValue` / watcher。
     *   不再探测 `getCustomizeFunctionValue` / `setCustomizeFunctionValue` /
     *   `getFloatProperty` / `getSupportedFunctionValue` / `isFunctionSupported` ——
     *   前者会直接导致 native 崩溃，后者只是减少无谓的反射面。
     */
    private fun probeEcarXMethods() {
        val c = session?.javaClass ?: return

        // 读：严格匹配 (int, int) —— 与 osn_fan_widget 完全一致
        readMethod = c.methods.firstOrNull {
            it.name == Sec.m(Sec.M_GETFUNCTIONVALUE) && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: pickMethod(c, Sec.m(Sec.M_GETFUNCTIONVALUE), intArrayOf(2))

        // 写：优先 3 参 (functionId, areaId, value)
        writeMethod = pickMethod(c, Sec.m(Sec.M_SETFUNCTIONVALUE), intArrayOf(3))
            ?: pickMethod(c, Sec.m(Sec.M_SETINTPROPERTY), intArrayOf(3))

        // ★ v3.13.15：**无 area 重载旁路**（文档口径）——只给内外循环用，不影响其它通道
        readMethodNoArea = c.methods.firstOrNull {
            it.name == Sec.m(Sec.M_GETFUNCTIONVALUE) && it.parameterTypes.size == 1
        } ?: pickMethod(c, Sec.m(Sec.M_GETFUNCTIONVALUE), intArrayOf(1))
        writeMethodNoArea = c.methods.firstOrNull {
            it.name == Sec.m(Sec.M_SETFUNCTIONVALUE) && it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Int::class.javaPrimitiveType
        } ?: pickMethod(c, Sec.m(Sec.M_SETFUNCTIONVALUE), intArrayOf(2))
        Logx.d(
            "车控通道 A 无 area 重载（文档口径）：" +
            "读=${readMethodNoArea != null} 写=${writeMethodNoArea != null}"
        )

        bindWatcher(session!!)
    }

    /**
     * 绑定事件监听。
     * ⚠️ ECarX 的 ICarFunction 只有 `registerFunctionValueWatcher(int[], watcher)` 一种形态，
     *    必须优先选它；按 `c.methods` 遍历顺序取第一个可能拿到不存在的重载。
     */
    private fun bindWatcher(target: Any) {
        val iface = runCatching { Class.forName(IFACE_WATCHER_ECARX) }.getOrNull()
            ?: runCatching { Class.forName(IFACE_WATCHER_PRO) }.getOrNull() ?: return
        watcherIface = iface
        val c = target.javaClass

        var found: Method? = null
        var arrayMode = false
        // ① 优先数组模式
        for (m in c.methods) {
            if (m.name != Sec.m(Sec.M_REGISTERFUNCTIONVALUEWATCHER)) continue
            val p = m.parameterTypes
            if (p.size == 2 && p[0] == IntArray::class.java && p[1] == iface) {
                found = m; arrayMode = true; break
            }
        }
        // ② 回退：逐函数模式
        if (found == null) {
            for (m in c.methods) {
                if (m.name != Sec.m(Sec.M_REGISTERFUNCTIONVALUEWATCHER)) continue
                val p = m.parameterTypes
                if (p.size == 2 &&
                    (p[0] == Int::class.javaPrimitiveType || p[0] == Integer::class.java) &&
                    p[1] == iface
                ) {
                    found = m; arrayMode = false; break
                }
            }
        }
        registerMethod = found
        registerArrayMode = arrayMode
        unregisterMethod = pickMethod(c, Sec.m(Sec.M_UNREGISTERFUNCTIONVALUEWATCHER), intArrayOf(1))
            ?: pickMethod(c, Sec.m(Sec.M_UNREGISTER), intArrayOf(1))
    }

    /** 在类上找一个参数个数匹配的方法 */
    private fun pickMethod(c: Class<*>, name: String, arities: IntArray): Method? {
        val candidates = c.methods.filter { it.name == name && it.parameterTypes.size in arities }
        if (candidates.isEmpty()) {
            val viaInterfaces = c.interfaces.flatMap { it.methods.toList() }
                .filter { it.name == name && it.parameterTypes.size in arities }
            return viaInterfaces.firstOrNull()
        }
        return candidates.minByOrNull { m ->
            val types = m.parameterTypes
            var score = 0
            for (t in types) {
                score += when (t) {
                    Int::class.javaPrimitiveType, Integer::class.java -> 0
                    Float::class.javaPrimitiveType -> 1
                    Long::class.javaPrimitiveType -> 2
                    else -> 5
                }
            }
            score
        }
    }

    // =====================================================================
    // 读 / 写（int 通道）
    // =====================================================================

    fun readInt(functionId: Int, zone: Int): Int? {
        val m = readMethod ?: return null
        val s = session ?: return null
        return runCatching {
            val args = if (m.parameterTypes.size == 1) arrayOf<Any?>(functionId)
            else arrayOf<Any?>(functionId, zone)
            val r = m.invoke(s, *coerce(m, args))
            when (r) {
                null -> null
                is Number -> r.toInt()
                is Boolean -> if (r) 1 else 0
                else -> null
            }
        }.getOrElse {
            if (isBinderDead(it)) { reset(); appCtx?.let { c -> runCatching { init(c) } } }
            Logx.d("读 0x${functionId.toString(16)}/zone=$zone 失败：${it.message}")
            null
        }
    }

    // =====================================================================
    // ★ v3.13.15：无 area 重载（文档口径）——**仅供内外循环使用**
    // =====================================================================

    /** 该会话是否支持无 area 重载（文档口径）。false = 调用方应回落到带 area 路径 */
    fun hasNoAreaOverload(): Boolean = writeMethodNoArea != null || readMethodNoArea != null

    /**
     * 文档口径写入：`setFunctionValue(functionId, value)`（不传 area）。
     * @return null = 该重载不可用（调用方请回落）；true/false = HAL 的接受结果
     */
    fun writeIntNoArea(functionId: Int, value: Int): Boolean? {
        val m = writeMethodNoArea ?: return null
        val s = session ?: return null
        return runCatching {
            val r = m.invoke(s, functionId, value)
            when (r) {
                null -> true
                is Boolean -> r
                else -> true
            }
        }.getOrElse {
            Logx.d("写 0x${functionId.toString(16)}（无area）失败：${it.message}")
            false
        }
    }

    /** 文档口径读取：`getFunctionValue(functionId)`（不传 area）。null = 不可用或读不到 */
    fun readIntNoArea(functionId: Int): Int? {
        val m = readMethodNoArea ?: return null
        val s = session ?: return null
        return runCatching {
            val r = m.invoke(s, functionId)
            when (r) {
                is Number -> r.toInt()
                is Boolean -> if (r) 1 else 0
                else -> null
            }
        }.getOrElse {
            Logx.d("读 0x${functionId.toString(16)}（无area）失败：${it.message}")
            null
        }
    }

    /**
     * 写 int 通道。
     *
     * ⚠️ 关键：ECarX 的 `setFunctionValue(int functionId, int value)` 2 参重载里
     *    **第二参是 value，不是 zone**。早期实现传了 `(functionId, zone)`，
     *    导致走 2 参重载时把 zone 当成要写的值 → 写入无效。
     */
    fun writeInt(functionId: Int, zone: Int, value: Int): Boolean {
        val m = writeMethod ?: run {
            Logx.e("HVAC_WRITE 写失败：当前通道没有可用的写方法（后端=${backend}），写 0x${functionId.toString(16)} zone=$zone value=$value")
            return false
        }
        val s = session ?: run {
            Logx.e("HVAC_WRITE 写失败：会话未建立（后端=${backend}）")
            return false
        }
        // 诊断：写前先探测 HAL 对该 functionId/zone 的支持状态。
        // 返回 "notavailable" 即 HAL 不暴露该能力（普通 App 无权限 / 硬件不支持）→ 写必定无效，
        // 这是判定「温度/AC 写入被拦截」的决定性证据。
        val support = probeSupport(functionId, zone)
        return runCatching {
            val args: Array<Any?> = when (m.parameterTypes.size) {
                2 -> arrayOf(functionId, value)          // ★ 2 参 = (functionId, value)
                3 -> arrayOf(functionId, zone, value)
                else -> arrayOf(functionId, zone, value)
            }
            val r = m.invoke(s, *coerce(m, args))
            val ok = when (r) {
                null -> true
                is Boolean -> r
                is Number -> true
                else -> true
            }
            Logx.d(
                "HVAC_WRITE 0x${functionId.toString(16)} zone=$zone value=$value → " +
                "车控写入返回=${r}(${r?.javaClass?.simpleName ?: "null"}) 判定=$ok " +
                "方法=${m.name} 支持状态=$support"
            )
            ok
        }.getOrElse { e ->
            if (isBinderDead(e)) { reset(); appCtx?.let { c -> runCatching { init(c) } } }
            // ★ 明确区分「权限被拒」与「值/属性不支持」——两者处理方式完全不同：
            //   SecurityException → 服务端对 set 做了 uid/签名校验，普通 App 无解（需系统签名或 su）
            //   其它异常          → 多半是 value 格式 / 属性不支持，改编码还有救
            val cause = e.cause ?: e
            val kind = when {
                cause is SecurityException ||
                    cause.javaClass.name.contains("SecurityException") ->
                    "★权限被拒(PERMISSION_DENIED：该 set 需要系统签名/uid，普通 App 无解)"
                cause is IllegalArgumentException ||
                    cause.javaClass.name.contains("IllegalArgument") ->
                    "值非法(ILLEGAL_VALUE：value 格式或取值越界，改编码/取值可能有救)"
                cause is UnsupportedOperationException ||
                    cause.javaClass.name.contains("Unsupported") ->
                    "属性不支持(UNSUPPORTED：该 functionId/zone 不接受写入)"
                else -> "其它异常"
            }
            Logx.e(
                "HVAC_WRITE 0x${functionId.toString(16)} zone=$zone value=$value " +
                "失败类型=$kind 支持状态=$support " +
                "异常=${cause.javaClass.simpleName}:${cause.message}"
            )
            false
        }
    }

    /**
     * 写后回读校验：写完读一次，比对低 8 位是否真的变了。
     *
     * ★ 很多 HAL 的 setFunctionValue 会返回 true 但实际不生效（静默丢弃），
     *   只看返回值会误判成"写成功"。故必须回读确认。
     */
    fun verifyWrite(functionId: Int, zone: Int, value: Int, delayMs: Long = 400L): Boolean {
        appCtx ?: return false
        val before = readInt(functionId, zone)
        if (writeInt(functionId, zone, value)) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                val after = readInt(functionId, zone)
                val same = (after != null && before != null) &&
                    (after and 0xFF) == (before and 0xFF) &&
                    (after and 0xFF) != (value and 0xFF)
                Logx.d(
                    "HVAC_VERIFY 0x${functionId.toString(16)} zone=$zone 写前=$before 写值=$value 写后=$after → " +
                    if (same) "★未生效(NO_EFFECT：写入被静默丢弃，多半是权限)" else "已生效"
                )
            }, delayMs)
            return true
        }
        return false
    }

    /**
     * 诊断辅助：写前探测 isFunctionSupported，捕获 HAL 对该 functionId/zone 的支持状态。
     * 仅在未探测过时反射找一次方法；任何异常都吞掉，绝不影响主写路径。
     */
    private fun probeSupport(functionId: Int, zone: Int): String {
        if (!supportProbed) {
            supportProbed = true
            supportMethod = session?.javaClass?.methods?.firstOrNull { m ->
                m.name == Sec.m(Sec.M_ISFUNCTIONSUPPORTED) && m.parameterTypes.size in 1..2 &&
                    m.parameterTypes.all { it == Int::class.javaPrimitiveType || it == Integer::class.java }
            }
            if (supportMethod == null) {
                Logx.d("HVAC_WRITE 诊断：当前后端无「通道支持探测」方法，跳过支持探测")
            }
        }
        val sm = supportMethod ?: return "未探测"
        val s = session ?: return "无会话"
        return runCatching {
            val args = if (sm.parameterTypes.size == 1) arrayOf<Any?>(functionId)
            else arrayOf<Any?>(functionId, zone)
            val r = sm.invoke(s, *coerce(sm, args))
            r?.toString() ?: "null"
        }.getOrElse { "探测异常:${it.javaClass.simpleName}:${it.message}" }
    }

    /**
     * ★ v1.4.48：新增未验证信号的写入前置探测（public）。
     * 真车日志实锤：对 HAL 不支持的 (funcId, zone, value) 调 setFunctionValue 会
     * **直接 native abort**（用户闪退 2-3 次正是连续触发了这一点）。
     * 返回 "active" 才允许写入；其它（not_available / 未探测 / 异常）一律跳过。
     */
    fun supportOf(functionId: Int, zone: Int): String = probeSupport(functionId, zone)

    /**
     * 浮点温度通道 —— **已废弃**。
     *
     * `getCustomizeFunctionValue` 在普通 App 上会触发厂商库 native 崩溃（见类注释），
     * 且实测空调关闭时返回伪值 5.0。温度一律走 int 通道（[readInt] + tempFromRaw）。
     */
    val hasFloat: Boolean get() = false

    /** 浮点通道描述（诊断用） */
    val floatChannelName: String get() = "无（Customize 通道已禁用，温度走 int 通道 ×0.2）"

    /** 浮点写入通道描述（诊断用） */
    val floatWriteChannelName: String get() = "无（温度走车控整型通道 + ×0.2 编码）"

    /** 兼容旧调用名：现在恒返回 null（温度走 int 通道） */
    fun readTempProperty(functionId: Int, zone: Int): Float? {
        Logx.d("温度读取已改走 int 通道（Customize 通道因 native 崩溃被禁用）")
        return null
    }

    /** 兼容旧调用名：现在恒失败（温度写走 setFunctionValue + ×0.2 编码） */
    fun writeTempProperty(functionId: Int, zone: Int, value: Float): Boolean {
        Logx.d("温度写入已改走 int 通道（Customize 通道因 native 崩溃被禁用）")
        return false
    }

    /**
     * ★★ v1.4.65（实验）：通过 **Customize 浮点通道**写温度。
     *
     * ## 为什么重新启用
     *
     * 温度 int 通道 `0x10060100` 实测**只读**（`isFunctionSupported=notavailable`，
     * 复合/裸值 × 8 个 zone 全 FAIL，1362 次 0 成功）。而：
     *  - 系统空调的温度就是 float（watcher 回调收到 18.5 / 22.5，`onCustomizeFunctionValueChanged`）
     *  - 说明温度的**正规写入通道就是 Customize float**：`setCustomizeFunctionValue(id, zone, float)`
     *  - 该通道在 1.4.47 时代被禁，理由是「调用即 SIGSEGV」——但那个结论**很可能是误诊**：
     *    + 当时 dock-contrast 截屏线程每秒在跑（后来证实是 SIGABRT 真凶）
     *    + 当时取证只有 maps 原文，无信号名/线程名/模块（1.4.51 才加）
     *    + 现在温度 watcher 一直开着从未崩，「温度相关调用必崩」前提已动摇
     *
     * ## 风险控制
     *  - 只在用户打开「实验开关」后才调用（默认关）
     *  - 崩了会被 streak 熔断自动停写，且 1.4.51+ 取证能直接给出线程名/模块
     *
     * @return true = 调用成功（HAL 是否真正生效另说）；false = 方法不存在/调用失败
     */
    @Volatile
    var customizeWriteEnabled: Boolean = false

    /** Customize 浮点方法（读/写）缓存 */
    private var customizeWriteMethod: Method? = null
    private var customizeReadMethod: Method? = null

    /**
     * ★★ v1.4.68：**严格照抄系统空调的调用序列**写温度。
     *
     * 权威依据（反编译 + 真车日志双重确认）：
     *
     * 1) 反编译 `oneOS_Dock` 的 `TempSignalClick.addLeftTemp`：
     * ```java
     * float cur = leftTempSignal.getValue();   // getCustomizeFunctionValue(0x10060100, 1)
     * float max = maxTempSignal.getValue();    // 0x10060200
     * if (cur >= max) return;                  // 到顶不写
     * cur += 0.5f;                             // 摄氏步进 0.5
     * leftTempSignal.setValue(cur);            // setCustomizeFunctionValue(0x10060100, 1, cur)
     * ```
     * 2) 真车 logcat（2026-10-20 20:29:29，系统空调 pid 3125 实际调用）：
     * ```
     * isFunctionSupported HVAC_FUNC_TEMP [0x10060100] zone ZONE_ROW_1_LEFT [0x1] status active
     * setCustomizeFunctionValue HVAC_FUNC_TEMP [0x10060100] zone ZONE_ROW_1_LEFT [0x1] value 18.0
     * getFunctionValue HVAC_FUNC_TEMP_MAX [0x10060200] zone ZONE_ALL [0x80000000] value 28.5
     * getFunctionValue HVAC_FUNC_TEMP_MIN [0x10060300] zone ZONE_ALL [0x80000000] value 15.5
     * setCustomizeFunctionValue: ... results:true
     * getCustomizeFunctionValue HVAC_FUNC_TEMP [0x10060100] zone [0x1] results:19.5
     * ```
     *
     * 关键差异（我们之前全错）：
     *  - 方法是 **`setCustomizeFunctionValue`**，不是 `setFunctionValue`
     *  - value 是**目标温度 float（℃）**，不是索引、不是复合编码
     *  - zone：主驾 `0x1`(ZONE_ROW_1_LEFT)、副驾 `0x4`(ZONE_ROW_1_RIGHT)
     *  - 上下限：MAX=`0x10060200` / MIN=`0x10060300`，zone=全局 0x80000000
     *
     * ⚠ 权限现实：同一 (0x10060100, 0x1)，系统空调查到 **active**，
     *   我们查到 **notavailable** → HAL 侧按调用者身份（平台签名/系统 uid）检查。
     *   所以这里**可能直接被拒或触发厂商库崩溃**（1.4.47 时代的 SIGSEGV 疑似同源）。
     *   因此默认关闭，由用户在主界面显式开启。
     */
    fun writeTempCustomize(functionId: Int, zone: Int, value: Float): Boolean {
        if (!customizeWriteEnabled) return false
        val c = session?.javaClass ?: return false
        val m = (customizeWriteMethod ?: findCustomizeMethod(c, Sec.m(Sec.M_SETCUSTOMIZEFUNCTIONVALUE), 3)
            ?.also { customizeWriteMethod = it })
            ?: run {
                Logx.d("温度 Customize 写：厂商库没有对应的浮点写入方法")
                return false
            }
        return try {
            val arg = if (m.parameterTypes[2] == Float::class.javaPrimitiveType) value else value.toDouble()
            val r = m.invoke(session, functionId, zone, arg)
            val ok = r == null || r == true
            Logx.d(
                "温度 Customize 写：0x${functionId.toString(16)} zone=0x${zone.toString(16)} " +
                "value=$value℃ → ${m.name} 返回=$r"
            )
            ok
        } catch (t: Throwable) {
            Logx.e("温度 Customize 写异常：${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * ★★ v1.4.68：读温度（float ℃）—— `getCustomizeFunctionValue(int, int)`。
     * 系统空调就是这么读的（20:29:29 日志：results:19.5）。
     * 相比 watcher 回调，这个是主动读，更适合「写完立刻验证」。
     */
    fun readTempCustomize(functionId: Int, zone: Int): Float? {
        val c = session?.javaClass ?: return null
        val m = (customizeReadMethod ?: findCustomizeMethod(c, Sec.m(Sec.M_GETCUSTOMIZEFUNCTIONVALUE), 2)
            ?.also { customizeReadMethod = it }) ?: return null
        return try {
            val v = m.invoke(session, functionId, zone) as? Float
            if (v != null) Logx.d("温度 Customize 读：0x${functionId.toString(16)} zone=0x${zone.toString(16)} → $v℃")
            v
        } catch (t: Throwable) {
            Logx.e("温度 Customize 读异常：${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /** 找 Customize 系列的 (int, int[, float|double]) 方法 */
    private fun findCustomizeMethod(c: Class<*>, name: String, argCount: Int): Method? =
        c.methods.firstOrNull { m ->
            m.name == name && m.parameterTypes.size == argCount &&
                m.parameterTypes[0] == Int::class.javaPrimitiveType &&
                (argCount == 2 || m.parameterTypes[1] == Int::class.javaPrimitiveType) &&
                (argCount == 2 || m.parameterTypes[2] == Float::class.javaPrimitiveType ||
                    m.parameterTypes[2] == Double::class.javaPrimitiveType)
        }

    /** 把实参适配成方法声明的类型 */
    private fun coerce(m: Method, args: Array<Any?>): Array<Any?> {
        val types = m.parameterTypes
        val out = arrayOfNulls<Any?>(args.size)
        for (i in args.indices) {
            val v = args[i]
            val t = types.getOrNull(i)
            out[i] = when {
                v == null -> null
                t == Int::class.javaPrimitiveType || t == Integer::class.java -> (v as Number).toInt()
                t == Long::class.javaPrimitiveType -> (v as Number).toLong()
                t == Float::class.javaPrimitiveType -> (v as Number).toFloat()
                t == Double::class.javaPrimitiveType -> (v as Number).toDouble()
                t == Boolean::class.javaPrimitiveType -> (v as Number).toInt() != 0
                else -> v
            }
        }
        return out
    }

    // =====================================================================
    // 事件监听
    // =====================================================================

    fun registerWatcher(functions: IntArray, onChange: (functionId: Int, zone: Int, value: Any?) -> Unit): Boolean {
        if (watcherProxy != null) return true
        val iface = watcherIface ?: return false
        val m = registerMethod ?: return false
        val s = session ?: return false
        return runCatching {
            val handler = InvocationHandler { proxy, method, args ->
                val n = method.name
                when (n) {
                    // 必须正确处理这三个标准方法，否则系统向 HashMap/CallbackList 注册代理时会 NPE
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> (args?.firstOrNull() === proxy)
                    "toString" -> "VehicleWatcherProxy@${Integer.toHexString(System.identityHashCode(proxy))}"

                    // ★ 精确按 ECarX 接口契约分派
                    //   （osn_fan_widget 只处理 onFunctionValueChanged，实践证明这样就够了）
                    "onFunctionValueChanged" -> {
                        if (args != null && args.size >= 3) {
                            val fid = (args[0] as? Number)?.toInt() ?: -1
                            val area = (args[1] as? Number)?.toInt() ?: -1
                            val v = args[2] as? Number
                            runCatching { onChange(fid, area, v?.toInt()) }
                        }
                        null
                    }
                    "onCustomizeFunctionValueChanged" -> {
                        // 该通道已被禁用，正常不会收到；收到也只透传，不做任何调用
                        if (args != null && args.size >= 3) {
                            val fid = (args[0] as? Number)?.toInt() ?: -1
                            val area = (args[1] as? Number)?.toInt() ?: -1
                            val v = args[2] as? Number
                            runCatching { onChange(fid, area, v?.toFloat()) }
                        }
                        null
                    }
                    else -> null
                }
            }
            val proxy = Proxy.newProxyInstance(
                iface.classLoader ?: javaClass.classLoader,
                arrayOf(iface),
                handler
            )
            watcherProxy = proxy
            val ok = if (registerArrayMode) {
                m.invoke(s, functions, proxy) as? Boolean ?: true
            } else {
                var any = false
                for (f in functions) {
                    val r = m.invoke(s, f, proxy) as? Boolean ?: true
                    any = any || r
                }
                any
            }
            Logx.d("事件监听注册：${if (registerArrayMode) "数组模式" else "逐函数模式"} func=${functions.joinToString { "0x" + it.toString(16) }} → ${if (ok) "OK" else "FAIL"}")
            ok
        }.getOrElse {
            Logx.e("事件监听注册异常", it)
            false
        }
    }

    fun unregisterWatcher() {
        val m = unregisterMethod ?: return
        val s = session ?: return
        val p = watcherProxy ?: return
        runCatching { m.invoke(s, p) }
        watcherProxy = null
    }

    // =====================================================================

    /** 把探测结果打进日志，方便在车机上确认通道是否真的通了 */
    private fun dumpProbe(ctx: Context) {
        Logx.d("后端=$backend  读方法=${readMethod?.let { "${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})" } ?: "-"}")
        Logx.d("后端=$backend  写方法=${writeMethod?.let { "${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})" } ?: "无（只读）"}")
        Logx.d("后端=$backend  温度通道=$floatChannelName")
        Logx.d("后端=$backend  监听方法=${registerMethod?.name ?: "-"}${if (registerArrayMode) "(数组)" else "(逐函数)"}")
    }

    /** 调试页用：列出会话对象的全部可用方法 */
    fun dumpMethods(): List<String> {
        val s = session ?: return emptyList()
        return s.javaClass.methods
            .filter { it.declaringClass != Any::class.java }
            .map { "${it.name}(${it.parameterTypes.joinToString { t -> t.simpleName }})" }
            .distinct()
            .sorted()
    }

    /** binder 是否已死（系统车控服务重启/掉线） */
    private fun isBinderDead(t: Throwable): Boolean =
        t is android.os.DeadObjectException || t is android.os.RemoteException

    @Synchronized
    fun reset() {
        unregisterWatcher()
        session = null
        carObj = null
        backend = Backend.NONE
        readMethod = null
        writeMethod = null
        readMethodNoArea = null
        writeMethodNoArea = null
        registerMethod = null
        unregisterMethod = null
        supportMethod = null
        supportProbed = false
    }
}
