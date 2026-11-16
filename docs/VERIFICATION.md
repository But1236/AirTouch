# 验证记录 (Verification log)

本文件记录 AirTouch 在本机实际跑过的验证、可复现命令，以及**没有**被验证的部分。
每次改代码后请重新执行 §2 的两条命令并更新 §1 的数字。

---

## 1. 结论

| 套件 | 命令 | 结果 |
| --- | --- | --- |
| .NET 自检（单元 + 端到端 + 真实输入 + WPF） | `AirTouch.SmokeTest.exe .smoke` | **120 通过 / 0 失败 / 0 跳过** |
| 独立 TLS 探测（Node + OpenSSL，手写 RFC 6455） | `pwsh -File tools\verify-wss.ps1` | **32 通过 / 0 失败** |

覆盖到的行为：

* **安全策略**：token 为 UUID v4；正确 token 放行；首个 IP 绑定；连接成功后 token 立即销毁；
  其他 IP 被拒（`ip_locked`）；同 IP 第二条连接被拒（`busy`/1013）；断开后 token 轮换、旧 token 失效、
  绑定 IP 可用新 token 重连；**手机掉线后用旧 token 重连会被判为「重放」并返回 `token_expired`，
  且不计入失败次数**（用 `token_invalid → token_invalid → banned` 的序列反证重放没有占用封禁额度），
  因此偶尔掉线不会把正常手机封掉；连续 3 次错误 token → 永久封禁；封禁后正确 token 也被拒；
  封禁只影响肇事 IP；重置后解封；错误路径 `bad_path`；超出速率限制 `rate_limited`。
* **文件名与路径加固**：相对路径、Windows 分隔符、绝对路径、结尾点、控制字符、设备名（`CON`）、
  空名称、`..`、超长名、中文名、emoji、目录内外判定、绝对路径逃逸。
* **协议**：`move`/`click`/`scroll`/`reset`/`file_header` 解析与范围截断、字符串数字、未知类型、
  非法 JSON、非对象 JSON、未知字段忽略、二维码 URL 格式、`welcome`、`file_ack` 省略 null 字段、
  中文文件名 JSON 往返（服务端转义为 `\uXXXX`，Android `org.json` 可还原）。
* **证书**：生成带私钥的自签名证书、有效期 > 1 年、SHA-256 指纹、包含局域网 IP 的 SAN、
  重启复用不重新生成、私钥写入密钥容器（wss 可用）、PFX 与口令落盘、二维码为 PNG。
* **TLS 传输**：`wss://` 握手成功、证书指纹匹配；Node/OpenSSL 侧为 **TLSv1.3**、
  `Sec-WebSocket-Accept` 计算正确、`101 Switching Protocols`。
* **端到端（真实 socket）**：welcome、绑定 IP 记录、并发第二条连接被拒、全部控制指令被真实执行、
  `move` 相对位移正确、`move` 截断到上限、设备名上报、文本 ping/pong、心跳期间连接存活
  （客户端 `KeepAliveInterval=5s`，服务端 2s ping + 自动回 pong）、非法帧之后连接仍可用。
* **文件**：上传 300 KiB 逐字节一致、文件名清洗、落在下载目录内、无残留 `.part`、`FileReceived` 事件、
  超过上限被拒且不落盘、无 header 的二进制帧被丢弃、手机收到 `file_header` 后接收 220 KiB 逐字节一致、
  收到 `file_end`、断开后 token 轮换。
* **真实鼠标（非 mock）**：`reset` 把光标精确放到主屏中心（期望 640,400 / 实际 640,400）、
  `move` 改变真实光标位置、滚轮与点击被 `SendInput` 接受；绑定局域网 IP 后 127.0.0.1 被拒绝。
* **WPF 界面**：窗口启动、服务处于运行状态、渲染二维码（560 字节）、地址形如
  `wss://<ip>:<port>/ws?token=`、展示证书指纹。
* **独立探测（Node/OpenSSL）**：TLS 握手、指纹一致、伪造 token 收到 `error{token_invalid}` 并
  以 1008 关闭、正确 token 收到 welcome（`chunkSize=65536`、`maxFileSize=536870912`）、
  文本 ping 得到 pong、上传 180 KiB 命中路径穿越文件名后仍落在下载目录且逐字节一致、
  **完整复刻 Android 客户端的发送序列**（`hello` → `file_header` → 2 个分块 → `file_end`）
  并验证中文文件名 `报告 2026.pdf` 端到端不被破坏、落盘逐字节一致、`file_end` 不产生重复
  `file_ack`、`file_end` 之后连接仍可用、未知类型被忽略且连接存活、
  服务端 WebSocket Ping 帧被观测到且客户端回 Pong 后连接存活。

---

## 2. 复现命令

```powershell
# 构建（Core + Server + SmokeTest，不依赖 .sln）
pwsh -File tools\build.ps1

# 1) 完整自检（会真实移动鼠标、真实弹出一个 WPF 窗口，约 20 秒）
Remove-Item -Recurse -Force .smoke -ErrorAction SilentlyContinue
server\AirTouch.SmokeTest\bin\Debug\net8.0-windows\AirTouch.SmokeTest.exe .smoke

# 2) 独立 TLS 探测：无界面服务端 + Node/OpenSSL 客户端
pwsh -File tools\verify-wss.ps1
```

Tool 说明：

* `AirTouch.SmokeTest.exe --serve <数据目录> <端口> [秒数]` 以生产配置（默认开 TLS）启动无界面服务端，
  并把 `AIRTOUCH_URL=` / `AIRTOUCH_TOKEN=` / `AIRTOUCH_FINGERPRINT=` / `AIRTOUCH_DOWNLOAD=`
  打印到 stdout，供外部客户端配对使用。
* `tools/wss-probe.mjs` 用 Node 的 OpenSSL 做 TLS，并手写 RFC 6455 帧（客户端帧按规范加掩码），
  因此它与 .NET 的 Schannel 是完全独立的一条实现路径。

---

## 3. 本机环境导致的注意事项（不是代码问题）

1. **沙箱内无法提供 TLS**：Schannel 要求私钥位于真实密钥容器（`UserKeySet`）。受限环境下写入
   `%APPDATA%\Microsoft\Crypto` 被拒绝（`CryptographicException: 拒绝访问`），程序会退回内存临时密钥，
   而 Schannel 明确不支持临时密钥，于是 `AirTouchServer.Start()` **主动拒绝启动 wss** 并给出可操作提示
   （不会静默降级）。在沙箱里跑自检时，你会看到且只看到这两项失败 + 一项跳过：
   `证书私钥已写入密钥容器（wss 可用）`、`窗口中服务处于运行状态`、`SKIP wss 握手`。
   在普通桌面会话（或管理员）下重新运行即为 120/0/0。
2. **classic `.sln` 无法在本机 SDK 上还原**（`_FilterRestoreGraphProjectInputItems` / `MSB4276`），
   与代码无关；请用 `tools/build.ps1`。`server/AirTouch.sln` 仅供 Visual Studio 使用。
3. **NuGet 出网不可用**（Schannel `SEC_E_NO_CREDENTIALS`），依赖已离线内置，见 README §7。
4. **.NET SDK 升级会抬高 net8.0 targeting pack 的补丁版本**：本机 SDK 从 10.0.114 升到 10.0.115 后，
   构建开始索要 `Microsoft.NETCore.App.Ref 8.0.34`（离线源里只有 8.0.33）并报 `NU1102`。
   重新执行 `node tools/fetch-nuget.mjs --out .nuget-local` 会自动取 8.0. 波段里最新的那几个包即可修复。

---

## 4. 未验证的部分（务必知悉）

1. **Android 工程从未编译或运行过**——本机没有 JDK、没有 Android SDK。全部结论来自逐行审阅
   （API 是否存在、资源与 ViewBinding 引用、JSON 字段、线程使用）。第一次
   `gradlew assembleDebug` 才是真正的检验；风险点列在 `android/README.md`。
2. **没有真机 ↔ PC 的实机会话**：所有端到端连接都由本机自动化客户端完成（回环 + 一个用于 IP 绑定
   测试的真实第二来源地址）。相机扫码、陀螺仪、触摸手势需要人工验证。
3. **未在真实校园网环境测试**（AP 隔离、跨网段、防火墙策略）。首次运行需要在 Windows 防火墙弹窗中
   允许专用网络，否则手机无法连接。
