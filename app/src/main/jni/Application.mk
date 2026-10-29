# 车机是 Qualcomm CX11（Android 9 / API 28）。
# arm64-v8a 与 armeabi-v7a 都编上，避免 ABI 不匹配导致 .so 加载失败；
# x86_64 供模拟器/其他设备调试。
APP_ABI := arm64-v8a armeabi-v7a x86_64
APP_PLATFORM := android-28
APP_OPTIM := release
