# 接入指南：让其它应用也用这个日志服务器

面向**其它 Android 应用**（或任何能发 HTTP POST 的程序）。**本项目（banana_dock / com.car.dockbar）
本身不需要改任何代码** —— 它的日志仍然照旧落在服务端 `logs/` 根目录，两者互不影响。

---

## 一、原理

服务端收到一条 JSON 后：

- payload **带 `appId` 字段** → 落盘到 `logs/<appId>/YYYY-MM-DD.jsonl`（按应用分目录）
- payload **不带 `appId`** → 落盘到 `logs/YYYY-MM-DD.jsonl`（老行为，本项目走这条）

所以新应用只要在 payload 里多带一个 `appId` 就行，**服务端不用为每个应用改配置**。

---

## 二、Android 应用接入（推荐）

### 1. 拷贝客户端文件

把 `LogKit.kt` 拷到你的项目：

```
app/src/main/java/com/car/logkit/LogKit.kt
```

**零第三方依赖**，只用 Android 框架 API + `org.json`，不需要改 build.gradle。

### 2. 加权限

`AndroidManifest.xml`：

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

> `ACCESS_NETWORK_STATE` 是可选的（用来判断有没有网），加上体验更稳。

### 3. 初始化

`Application.onCreate`：

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LogKit.init(this, "mytool")   // ← 你的应用标识，每个应用必须不同
    }
}
```

**`appId` 规则**（服务端强校验，非法会被拒绝）：
- 字母或数字开头
- 只允许字母、数字、`.`、`_`、`-`
- 长度 ≤ 64

### 4. 打日志

```kotlin
LogKit.log("这是一条日志")                    // 攒批上传（1.5 秒或 40 行发一次）
LogKit.flushNow("手动上传")                    // 立刻发送当前缓冲
LogKit.uploadText("diag", "诊断信息")           // 上传一段指定文本
```

### 5. 崩溃时上传（可选但推荐）

```kotlin
Thread.setDefaultUncaughtExceptionHandler { thread, t ->
    LogKit.uploadCrash("主线程崩溃", t)   // 同步上传，尽量抢救
    // 然后交回默认处理（或自行结束进程）
}
```

> 注意：native 崩溃（SIGSEGV）Java 层收不到，需要像本项目那样用 NDK 信号处理器写文件、
> 下次启动再回捞。LogKit 本身不含 native 部分。

---

## 三、最简接入示例（不想拷整个文件时）

只要能发 HTTP POST 就行，5 行代码：

```kotlin
val payload = JSONObject().apply {
    put("appId", "mytool")        // ← 关键：应用标识
    put("kind", "live")
    put("device", "dev-001")
    put("app", "1.0(1)")
    put("time", "2026-11-02 17:00:00")
    put("log", "hello")
}.toString()

Thread {
    val c = URL("https://28059b4b3e6a450db00a72ad75ea95a0.sg2.agentos-app.run/upload")
        .openConnection() as HttpURLConnection
    c.requestMethod = "POST"
    c.doOutput = true
    c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
    c.connectTimeout = 10000
    c.readTimeout = 10000
    OutputStreamWriter(c.outputStream, "UTF-8").use {
        it.write(payload); it.flush()
    }
    c.responseCode
}.start()
```

---

## 四、服务端接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | 任意路径（如 `/upload`） | 接收日志。带 `appId` 分目录，不带落根目录 |
| GET | `/` | 查看页（30 秒自动刷新，顶部有应用筛选） |
| GET | `/apps` | 列出所有已接入的应用及各自记录数 |
| GET | `/logs` | 最近记录 JSON。`?date=YYYY-MM-DD` 按天查、`?app=xxx` 按应用筛、`?limit=500` 调条数（上限 5000） |
| GET | `/download` | 全部日志纯文本。`?date=` 只下一天、`?app=` 只下某应用 |
| GET | `/health` | 健康检查（含已接入应用列表） |

### 查询示例

```bash
# 某天的全部记录
curl "https://.../logs?date=2026-11-02"

# 只看某个应用
curl "https://.../logs?app=mytool"

# 某应用某天，拉 500 条
curl "https://.../logs?app=mytool&date=2026-11-02&limit=500"

# 有哪些应用接入
curl "https://.../apps"
```

---

## 五、容量与限制

| 项 | 限制 |
| --- | --- |
| 单条 payload | 服务端 8MB 硬上限；LogKit 客户端自己截断到 22000 字符 |
| 单日单文件 | 16MB → 自动续写 `.1.jsonl` / `.2.jsonl` |
| 保留天数 | 30 天（服务端 `LOG_KEEP_DAYS` 环境变量可调，0 = 永久保留） |
| 上传频率 | 服务端不限制；LogKit 客户端自己按 1.5 秒 / 40 行攒批 |
| 离线队列 | 客户端本地 512KB，超出丢最老的 |

---

## 六、安全现状（请知悉）

**当前服务端没有任何鉴权**：

- 任何拿到 URL 的人都能 `POST` 灌日志
- 任何拿到 URL 的人都能 `GET /logs` 读走全部日志（含设备 ID、机型、App 版本）

日志内容里**不含**账号、密码、位置等个人隐私，但会暴露设备指纹和车控原始值。

因此：

1. **排查完就下线服务**（README 里有这句提醒）
2. 如果要长期运行，建议加一个共享 token —— 改服务端 `server.py` 的 POST 路径判断即可，
   客户端在 URL 后带一段路径即可，不需要改 header

---

## 七、常见问题

**Q：服务端要改吗？**
A：本次已改好并部署。其它应用直接带 `appId` 即可，不用为每个应用改服务端。

**Q：本项目（banana_dock）的日志会乱吗？**
A：不会。它不带 `appId`，行为与改造前完全一致，历史日志也原样保留。

**Q：appId 能改吗？**
A：能，但**改了就是另一个应用**（新目录），旧目录的历史日志留在原处。
建议一个应用固定一个标识。

**Q：上传地址怎么换？**
A：`LogKit.serverUrl = "..."`，或改 `LogKit.kt` 里的默认值。

**Q：明文 HTTP 行不行？**
A：服务端地址是 HTTPS。如果换成自建的局域网 IP（`http://192.168.x.x:8080`），
其它 App 需要在自己的 `network_security_config.xml` 里放行明文流量
（Android 9 起默认禁止明文）。本项目的配置已经放行了私网段。
