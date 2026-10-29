#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
★ CarDockBar 加固自检脚本

检查 release APK 的 dex 里是否残留敏感明文，并统计加固效果。

用法：
    python tools/check_harden.py [apk路径]

默认检查 app/build/outputs/apk/release/CarDockBar-release.apk

判定标准：下列敏感串在 dex 里**一次都不该出现**（因为都已走 Sec 加密）。
出现即说明有漏网，需要修。
"""
import os
import re
import subprocess
import sys
import zipfile

DEFAULT_APK = "app/build/outputs/apk/release/CarDockBar-release.apk"

# 绝不能以明文出现在 dex 里的敏感串
FORBIDDEN = [
    # 车机 SDK 类名
    "com.ecarx.xui.adaptapi.car.Car",
    "com.geely.os.car.GlyCar",
    "IFunctionValueWatcher",
    "IVehicleFunction",
    # 反射方法名
    "setFunctionValue",
    "getFunctionValue",
    "setCustomizeFunctionValue",
    "getCustomizeFunctionValue",
    "isFunctionSupported",
    "registerFunctionValueWatcher",
    "unregisterFunctionValueWatcher",
    "getIntProperty",
    "setIntProperty",
    "getFloatProperty",
    "setFloatProperty",
    # 接口名
    "ICarFunction",
    "IGlyCar",
    "GlyCar",
    "adaptapi",
    "ECarX",
    "OneVehicleSDK",
    "VehicleSignal",
    "SignalHelper",
    "EcarxInputDispatcher",
    "dispatchInputEvent",
    "CycleModeSignal",
    "RecycleSignalClick",
    "AutoSwitchSignal",
    # 地址
    "https://",
    "http://",
    "agentos-app.run",
    "webhook.site",
    "lrclib.net",
    "music.163.com",
    # 路径
    "/system/framework",
]

# 这些是"自己 App 的"或"功能清单"，泄露无害，不算失败
ALLOWED = [
    "com.car.dockbar",
]


def extract_strings(data: bytes):
    return [m.group().decode("ascii", errors="replace")
            for m in re.finditer(rb"[ -~]{6,}", data)]


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_APK
    if not os.path.exists(apk):
        print(f"✗ 找不到 APK: {apk}")
        print("  先构建：JAVA_HOME=\"C:/Program Files/Android/Android Studio/jbr\" ./gradlew :app:assembleRelease --no-daemon")
        sys.exit(1)

    print(f"检查: {apk}")
    print(f"体积: {os.path.getsize(apk):,} 字节")

    with zipfile.ZipFile(apk) as z:
        dex_names = [n for n in z.namelist() if n.endswith(".dex")]
        strs = []
        for n in dex_names:
            strs.extend(extract_strings(z.read(n)))

    print(f"dex 文件: {dex_names}")
    print(f"字符串总数: {len(strs)}\n")

    leaks = []
    for t in FORBIDDEN:
        hits = [s for s in strs if t in s]
        if hits:
            leaks.append((t, len(hits), hits[0][:70]))

    print("=" * 60)
    if leaks:
        print(f"✗ 发现 {len(leaks)} 类敏感明文残留：")
        for t, n, sample in leaks:
            print(f"   ★ {t}  ({n} 处)  例: {sample}")
    else:
        print("✓ 通过：dex 中无任何敏感接口明文")
    print("=" * 60)

    # 顺带打印剩余的 http(s) 明文，人工判断
    urls = sorted({s for s in strs if "http" in s.lower() and len(s) < 120})
    if urls:
        print(f"\n含 http 的字符串 {len(urls)} 条（需人工确认是否敏感）：")
        for u in urls[:20]:
            print("   ", u)

    return 1 if leaks else 0


if __name__ == "__main__":
    sys.exit(main())
