# =====================================================================
# CarDockBar 加固规则（R8 / ProGuard）
# =====================================================================
# 目标：APK 被反编译后看不到类名、方法名、字段名，
#       只剩一堆 a/b/c 符号。
#
# ★ 本项目**大量使用反射**访问车机 SDK，漏一条 keep 就会运行期崩溃，
#   所以下面的 keep 规则一条都不能删。
# =====================================================================

# ---------------------------------------------------------------------
# ① 反射访问车控 SDK（ECarX / 吉利 IGlyCar）时不要混淆
#    反射目标类名在源码里是字符串常量，R8 不会自动知道它们，
#    必须显式 keep，否则 Class.forName / getMethod 会找不到类。
# ---------------------------------------------------------------------
-keep class com.ecarx.** { *; }
-keep class com.geely.os.** { *; }
-keep class android.car.** { *; }
-dontwarn com.ecarx.**
-dontwarn com.geely.os.**
-dontwarn android.car.**

# ---------------------------------------------------------------------
# ② ★ JNI 绑定（**最关键，删了会 UnsatisfiedLinkError 直接闪退**）
#    native_crash.c 里是硬编码的 JNI 符号：
#      Java_com_car_dockbar_util_NativeCrashHandler_nativeInstall
#    这个符号按 **Java 全限定类名 + 方法名** 生成，
#    一旦 R8 把 NativeCrashHandler 或 nativeInstall 改名，
#    native 侧就找不到入口 → 加载 .so 时抛 UnsatisfiedLinkError。
#    所以：整个类 + 所有 native 方法必须原样保留。
# ---------------------------------------------------------------------
-keep class com.car.dockbar.util.NativeCrashHandler { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ---------------------------------------------------------------------
# ③ 动态代理
#    VehicleBridge.registerWatcher 用 java.lang.reflect.Proxy 实现了
#    ICarFunction$IFunctionValueWatcher / IVehicleFunction$IPropertyWatcher。
#    代理实现的所有接口方法名**不能被改**，否则回调分派失效（不崩但收不到事件）。
#    ① 已 keep 住接口类本身，这里再兜底代理接口的方法。
# ---------------------------------------------------------------------
-keepclassmembers class * implements java.lang.reflect.InvocationHandler {
    public <init>(...);
}
-keep interface com.ecarx.** { *; }
-keep interface com.geely.os.** { *; }

# ---------------------------------------------------------------------
# ④ Manifest 里声明的组件（Activity / Service / Receiver / Application）
#    AGP 通常会自动生成对应规则，这里显式写一份兜底，
#    避免 R8 版本差异导致组件被改名后系统拉不起来。
# ---------------------------------------------------------------------
-keep class com.car.dockbar.App { *; }
-keep class com.car.dockbar.ui.MainActivity { *; }
-keep class com.car.dockbar.ui.DockEntriesActivity { *; }
-keep class com.car.dockbar.ui.DockOverlayService { *; }
-keep class com.car.dockbar.ui.LyricOverlayService { *; }
-keep class com.car.dockbar.ui.ForegroundAppService { *; }
-keep class com.car.dockbar.media.DockNotificationListener { *; }
-keep class com.car.dockbar.BootReceiver { *; }

# ---------------------------------------------------------------------
# ⑤ 无障碍 / 通知监听服务
#    由系统按 Manifest 反射实例化，方法名必须保持原名。
# ---------------------------------------------------------------------
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class * extends android.service.notification.NotificationListenerService { *; }

# ---------------------------------------------------------------------
# ⑥ 自定义 View
#    layout XML 里若出现 <com.car.dockbar.ui.XXX>，被混淆后 inflate 会崩。
#    当前 layout 全是系统控件，这里保留规则以防后续新增。
# ---------------------------------------------------------------------
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# ---------------------------------------------------------------------
# ⑦ 枚举
#    Builtin（Builtin.HOME / .MUSIC …）被大量 when 引用。
#    保留可读性对排障有价值（这是唯一保留的"语义名"）。
# ---------------------------------------------------------------------
-keepclassmembers enum com.car.dockbar.model.Builtin {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    public static ** entries();
    public ** *;
}

# ---------------------------------------------------------------------
# ⑧ 保留注解与签名（Kotlin 元数据 / 反射注解）
# ---------------------------------------------------------------------
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepattributes AnnotationDefault
-dontnote

# ---------------------------------------------------------------------
# ⑨ 隐藏源码文件名与行号
#    去掉 SourceFile / LineNumberTable → 崩溃栈不再暴露源码文件结构。
# ---------------------------------------------------------------------
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ---------------------------------------------------------------------
# ⑩ 抑制告警
# ---------------------------------------------------------------------
-dontwarn java.lang.invoke.**
-dontwarn sun.misc.**
-dontwarn javax.annotation.**
-dontwarn org.jetbrains.annotations.**
