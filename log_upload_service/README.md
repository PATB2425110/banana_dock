# CarDockBar 日志接收服务（线上部署版）

接收车机 App（`com.car.dockbar` 的 `LogUploader`）POST 上来的 JSON 日志，
按天落盘 `logs/YYYY-MM-DD.jsonl`，可在浏览器直接查看、下载。

## 本地运行

```bash
PORT=8080 python server.py
```

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | 任意路径（如 `/upload`） | 接收日志，返回 `{"ok": true, "saved": "..."}` |
| GET | `/` | 迷你查看页（30 秒自动刷新） |
| GET | `/health` | 健康检查 |
| GET | `/logs` | 最近 200 条记录（JSON） |
| GET | `/download?date=YYYY-MM-DD` | 下载日志纯文本 |

## 部署

单端口 HTTP 服务，仅依赖 Python 标准库：

- 启动命令：`python server.py`
- 必须读取环境变量 `PORT` 并绑定 `0.0.0.0`（已实现）
- 持久化：`logs/` 目录下的 JSONL 文件

## 车机侧配置

CarDockBar → 设置 → 日志 → 上传地址，填部署后的地址（任意路径均可，
推荐直接用根地址或 `/upload`），保存后点「测试上传」，随后刷新本页查看。

## 容量控制（环境变量，均有默认值，一般不调）

| 变量 | 默认 | 作用 |
| --- | --- | --- |
| `LOG_KEEP_DAYS` | `30` | 日志保留天数，超期每天自动清理；`0` = 全部保留 |
| `LOG_SPLIT_BYTES` | `16777216`（16MB） | 单日文件上限，超出自动续写 `<日期>.1.jsonl` |

App 端单条 payload 日志正文本身截断到 16000 字符（约 20-40KB），
无上传频率限制，正常情况下永远碰不到分片；只有异常刷量时才会续写分片。
