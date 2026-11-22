# AirTouch Android 客户端

把手机当作电脑的外设：**鼠标**（3D 陀螺仪姿态 / 2D 桌面平移）+ **触摸板**（单指移动、双指滚动）+ **键盘**
（QWERTY / 九键拼音中文 / 横屏完整 104 键）+ 文件双向传输。配套的 Windows 服务端在 `../server/`，
通信协议以 `../docs/PROTOCOL.md`（v2）为唯一事实来源。

---

## 1. 环境要求

| 项目 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17**（必须） | Android Gradle Plugin 8.5.2 要求 JDK 17；Android Studio 自带的 JBR 即可 |
| Gradle | 8.7 | 由 wrapper 指定（`gradle/wrapper/gradle-wrapper.properties` 里的 `gradle-8.7-bin.zip`） |
| Android Gradle Plugin | 8.5.2 | |
| Kotlin | 1.9.24 | |
| compileSdk / targetSdk | 34 | |
| minSdk | 26（Android 8.0） | |
| AndroidKeyStore | EC P-256（API 23+） | 设备身份私钥，见 §4 |
| Build Tools / Platform | 随 compileSdk 34 自动下载 | |

> **关于 `gradle-wrapper.jar`**：已随工程提供
> `gradle/wrapper/gradle-wrapper.jar`（Gradle 8.7 官方 wrapper jar，43 KB），
> `gradlew` / `gradlew.bat` 可直接使用，无需另外下载。
> 若该文件被误删，`./gradlew` 会报
> "Could not find or load main class org.gradle.wrapper.GradleWrapperMain"，
> 此时用 Android Studio 打开工程会自动补齐（或在本机装有 Gradle 时执行 `gradle wrapper`）。
>
> Gradle 需要在自己的用户目录（默认 `%USERPROFILE%\.gradle`）写 wrapper 锁与依赖缓存；
> 若报 `gradle-8.7-bin.zip.lck (拒绝访问)`，把 `GRADLE_USER_HOME` 指到可写目录再构建。

## 2. 构建与运行

### 用 Android Studio（推荐）

1. `File → Open`，选择 `android/` 目录（**不是**仓库根目录）；
2. 首次打开时 Android Studio 会提示同步 Gradle、下载 SDK 34 与依赖，允许即可；
3. 等待 Sync 完成 → 连接手机（开启 USB 调试）→ 点 ▶ Run。

### 用命令行

```bat
cd android
set JAVA_HOME=<你的 JDK 17 路径>
gradlew.bat assembleDebug          :: 产物：app\build\outputs\apk\debug\app-debug.apk
gradlew.bat installDebug           :: 直接装到已连接的设备
```

`local.properties`（SDK 路径）由 Android Studio 自动生成；命令行构建需要自己创建：

```properties
sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```

## 3. 配对流程（怎么连上）

1. 电脑端启动 AirTouch 服务程序，窗口里会显示一个二维码，内容形如
   `wss://192.168.1.5:8443/ws?token=<一次性票据>&sid=<服务器身份>&fp=<证书SPKI指纹>`（协议 §1.1）；
2. 手机与电脑连同一个局域网（同一个 Wi-Fi / 热点）；
3. 手机打开 AirTouch，首次会申请**相机权限**，允许后对准二维码即可自动识别；
4. 识别成功后自动停止相机并进入控制界面。连接成功后顶部显示
   `已连接 · 192.168.1.5:8443 · 服务端 2.0.2 · 设备密钥 xxxxxxxx`，
   并用一条提示说明本次是「首次配对」还是「免扫码重连」。

也可以点「**手动输入地址**」，手填主机 IP、端口（留空默认 8443）和 token。
手动输入和扫码走的是**同一套校验**（`PairingUri.parse` / `fromParts`），
所以粘贴完整 URL 到「主机地址」里也能识别。手动输入时 **token 可以留空** —— 那就等价于
「用设备密钥重连」，前提是这台手机之前配对过、且电脑端没有撤销它。

### 认证过程（协议 v2 §1.2，三步）

```
手机                                                   电脑
  |--- wss 连接（校验证书固定值）---------------------->|
  |<-- auth_challenge {sid, nonce, serverTime, mode} ---|   每个连接一个新 nonce，12 秒内有效
  |--- auth_req {keyId, [token], [pubKey], sign, ts} -->|   sign = ECDSA(P-256/SHA-256, DER)
  |<-- welcome {protocolVersion, screenWidth, …} ------|   之后才允许发控制指令
```

待签名字节是固定的规范串（逐字节，`\n` 分隔）：

```
AirTouch-Auth-v1\n{sid}\n{nonce}\n{keyId}\n{ts}
```

- **设备身份**：手机第一次连接时在 `AndroidKeyStore` 里生成一把 **EC P-256** 私钥
  （别名 `airtouch_device_identity_v1`，`PURPOSE_SIGN` + `DIGEST_SHA256`，**不可导出**），
  公钥以 X.509 SPKI 形式上报；`keyId = base64url(SHA256(SPKI)[0..16])`（22 字符）。
- **一次性 token**：只在**首次配对**时使用。配对成功电脑端立即销毁并生成新的，
  所以同一个 token 不可能被用第二次。重连时 URL 里**不带 token**。
- **签名时机**：每个连接只签一次（握手时），不产生会话密钥、不在连接期内重复签名。

### 证书信任（协议 §4 强制）

服务端用的是**自签名证书**，系统 CA 校验必然失败，因此客户端采用固定（pinning）：

- **按服务器身份 `sid` 绑定**，而不是按 IP。所以电脑换了 IP（有线 → Wi-Fi、路由器重新分配）
  仍然认得同一台电脑；换了电脑（`sid` 不同）则当作新的服务器；
- 二维码里的 `fp` 是**严格校验**：本次连接计算出的 SPKI 指纹必须完全相等，
  否则立即断开并提示重新扫码；
- 手动输入地址（没有 `fp`）时退化为 **TOFU**：首次连接记录指纹，之后严格比对；
- 电脑端**重新生成证书**后，请到配对界面点「**重置证书信任**」清除已记录的指纹，
  然后重新扫码；
- 客户端**不存在**任何 `TrustAll` 分支。`HostnameVerifier` 是有意写成「直接通过」的：
  这里的身份来自**带外**的 SPKI 指纹（二维码），而不是证书里的 SAN —— 自签名证书的 SAN
  是服务端启动时按本机网卡生成的，把主机名再校验一遍只会让「换 IP」变得不可用，
  并不会增加任何安全性。

### 断线后怎么重连（v2 的核心改进）

- 网络抖动/电脑重启导致的断开，**不会退出到配对界面**：状态栏变成
  「连接断开，正在自动重连（第 N 次）…」，客户端按 1s → 2s → 4s → 8s → 15s 退避重试；
- 重连**不需要 token、不需要重新扫码**，只用设备私钥重新签一次挑战；
- 只有以下情况会停止重连并给出「需要重新扫码」的提示：
  `key_unknown`（电脑端不认识这台设备）、`trust_revoked`（被撤销信任）、`banned`（本机被封禁）、
  `bad_key` / `sig_invalid`（设备密钥不被接受）、`auth_unsupported`（协议版本过新）；
- `busy` / `too_many_pending` / `rate_limited` / `token_expired` / `replaced` 都属于可自愈，
  客户端会继续按退避重试；
- 配对界面还有「**免扫码重连上次的电脑**」按钮，用于 App 重启后一键连回上次的电脑。

## 4. 权限说明

| 权限 | 何时申请 | 用途 | 拒绝的后果 |
| --- | --- | --- | --- |
| `CAMERA` | 进入配对界面时 | 扫描服务端二维码 | 无法扫码，可改用手动输入地址 |
| `INTERNET` | 安装时授予 | wss 连接 | 无法工作 |
| `ACCESS_NETWORK_STATE` | 安装时授予 | 预留（当前版本没有任何代码读取网络状态，属于冗余声明） | 无 |
| `WRITE_EXTERNAL_STORAGE`（仅 `maxSdkVersion="28"`） | 进入控制界面时（**只在 Android 9 及以下**） | 把收到的文件 / 发送的文件落到公共「下载」目录 | 无法收发文件，控制功能不受影响 |
| `VIBRATE` | 安装时授予 | 预留（当前版本未使用振动反馈，属于冗余声明） | 无 |

Android 10（API 29）及以上使用 `MediaStore.Downloads`，**不需要任何存储权限**。

## 5. 功能与操作

### 触摸板（「触摸板」标签页）

| 手势 | 行为 |
| --- | --- |
| 单指拖动 | 移动光标（`move`，dx/dy 右下为正） |
| 单指轻点（<200ms、位移 <12dp） | 左键单击（`click button=left`） |
| 双指上下拖动 | 滚轮（`scroll`）。手指**下滑 = 下滚**，对应 `amount < 0`，符合协议 §2.1「正数上滚，负数下滚」 |
| 双指轻点 | 右键单击（`click button=right`） |
| 底部「滚轮模式」开启后，单指上下拖动 | 改为滚轮（`scroll`），按钮会变绿、提示文字变主色以示区分 |

- 灵敏度滑杆 **0.5x ~ 3.0x**，默认 **1.0x**，可实时调整并自动保存；
- 所有位移换算都以 **dp** 为基准（内部按 density 折算），换手机手感一致；
- 取整后的**余数会累积**，慢速拖动不会丢步；
- **合并采样点会被逐个还原**：Android 会把短时间内的多个 `ACTION_MOVE` 合并成一个事件
  （界面一忙就合并得更多），只读 `event.x/y` 会丢掉中间轨迹、表现为「手指在动、光标一顿一顿」。
  `TouchpadView` 现在遍历 `getHistoricalX/Y` 逐点累加，因此帧率下降时轨迹依然连续。

### 鼠标（「鼠标」标签页）

- 使用陀螺仪，采样周期取 **`max(2ms, 传感器最快周期)`，也就是「最快但不超过 500Hz」**，
  并注册在**专用 HandlerThread**（`airtouch-sensor`）上：主线程再忙也不会让传感器事件排队或丢失
  （这是「手机在动、光标不动」的主要根因）。早期版本固定 10ms（100Hz），
  界面显示的是**实测**采样率（手机只支持 200Hz 时就会显示 200Hz）；
  提到 500Hz 的理由：Windows 光标是整物理像素，慢速移动时「每秒更新几次」由像素数决定，
  采样越密、1 像素步进越均匀，观感才连续（电脑侧实测 `SetCursorPos` 到 1000Hz 都能 100% 落地，
  500Hz 在 Wi-Fi 上也只有约 20KB/s）；
- **死区作用在原始角速度上**（0.02 rad/s）。旧实现把死区加在**滤波后**的值上，
  于是「慢慢转」会被低通压到死区以下整段丢弃，看起来就像光标不动；
- 低通滤波按**时间常数**（50ms）计算系数 `1 - exp(-dt/tau)`，因此把采样率从 50Hz 提到 500Hz
  手感不变；`dt` 上限放宽到 100ms，卡顿后的真实手部运动不会被截掉；
- 状态行显示**实测采样率、实发帧率、发送间隔（均值/最大）与总帧数**，例如
  `陀螺仪 500 Hz · 实发 90 帧/秒 · 发送间隔 11.1/12.3 ms · 共 3412 帧`。
  如果一直在转却显示 `0 Hz`，说明传感器根本没出货（手机侧问题，不是链路问题）。
  **「实发帧率」才是电脑上光标每秒更新的次数**：慢速移动时每帧位移不足 1 物理像素，引擎会攒够
  1 像素才发，因此它会明显低于采样率（光标只能整像素跳）；
  **「发送间隔」的最大值是判断停顿位置的钥匙**：手机侧最大间隔只有十几毫秒、而电脑侧报告的
  「最大到达间隔」是 200ms 级，就说明帧是在链路上被攒起来一起发的（见下一节）；
- **发送限速**：陀螺仪照旧按「最快不超过 500Hz」采样，但**发送**按电脑报告的屏幕刷新率限速
  （60Hz 屏 → 90 帧/秒，高刷 → 144 帧/秒）。位移是可加量，攒够一次发出去不会丢任何运动，
  只是少占了空口（500Hz 的原始帧率对 60Hz 屏幕毫无收益，只会让 Wi-Fi 更容易积压）；
- **TCP_NODELAY 必须自己开**：OkHttp 4.12 从不调用 `setTcpNoDelay`，而 Android/Linux 默认
  **Nagle 是打开的**；服务端 Fleck 又明确设了 `NoDelay = true`。于是「手机 Nagle 开 + 电脑
  延迟 ACK（Windows 最多等 200ms）」会让 40 字节级的小包攒一批再发 —— 真机实测到的
  「平均到达间隔 6ms、最大 236ms」正是这个特征（实体鼠标走 USB HID，与 Nagle 无关，所以它一直很流畅）。
  `AirTouchClient` 因此挂了自定义 `SocketFactory` 给每个 socket 设 `tcpNoDelay = true`；
- **轴向映射：左右用 Z 轴、上下用 X 轴**（`gyro[2]` / `gyro[0]`），Y 轴（滚转）不使用。
  握持方式为**手机大致放平、屏幕朝上、顶部朝前**，像拿一支激光笔指着屏幕：

  | 手的动作 | 转轴 | 效果 |
  | --- | --- | --- |
  | 左右转手腕（水平面内扫过） | **Z 轴**（垂直屏幕，`gyro[2]`） | 光标左右 |
  | 顶部上下俯仰 | **X 轴**（屏幕水平方向，`gyro[0]`） | 光标上下 |
  | 拧手腕（手机绕自身长轴滚转） | Y 轴（`gyro[1]`） | **忽略** |

  早期版本用的是 X+Y（转动 Y 轴 = 拧手腕），于是「左右移动光标」变成了越拧越别扭的动作；
- **上下方向的默认值已经翻正**：默认就是「顶部向自己倾斜 = 光标上移」，不再需要一直勾着
  「反向（上下）」。升级时 `SettingsStore` 会把老设置**一次性取反**（老 `true` → 新 `false`），
  所以升级后手感不变；
- 「反向（左右）」「反向（上下）」可反转方向；灵敏度 **0.1x ~ 10.0x** 可调并保存；
- **滚轮模式**：在本页按底部工具栏的「滚轮模式」，手机上下俯仰即变滚轮（`scroll`），
  光标不再移动；页面上的模式行会写明当前状态（`已滚 N 格`）。它与触摸板的滚轮模式是
  **两个独立开关**：早期版本共用一个持久化开关，在鼠标页按下它会把触摸板静默切成滚轮模式，
  回到触摸板页就表现为「怎么拖都不动」；
- 右下角有一个**可拖动的悬浮复位按钮**，点击后发送 `reset` 并清空引擎的余数与滤波器状态；
- 设备没有陀螺仪时显示中文说明并禁用该功能（**不会崩溃**）；
  只有标准陀螺仪完全缺失时才降级使用 `TYPE_GYROSCOPE_UNCALIBRATED`，界面上会写明。

### Wi-Fi 低延迟锁

连接期间手机持有一个 **Wi-Fi 低延迟锁**（`WIFI_MODE_FULL_LOW_LATENCY`，API<29 用
`HIGH_PERF`）：Wi-Fi 省电会把帧攒着一起发，是「发送频率不低但看起来一顿一顿」的头号嫌疑。
**拿不到锁时鼠标页会直接显示原因**（以前只写日志，界面上完全看不见，等于无法排查）。

### 移动看着不连续时先看哪两个数字

真机上遇到「移动不连续」时，先看鼠标页的「发送间隔 最大」：

| 手机侧「发送间隔 最大」 | 现象与结论 |
| --- | --- |
| 十几毫秒 | 手机侧正常：问题在链路或电脑端注入 |
| **很大（200ms 级）** | 手机自己卡住了（线程被抢、编码/GC） |

手机侧正常而光标仍一顿一顿，通常是帧在链路被攒着一起发：① Wi-Fi 省电/漫游扫描
（换 5GHz、关蓝牙、确认低延迟锁）② Nagle + 延迟 ACK（本版已开 TCP_NODELAY）。
最快的交叉验证是用 **USB 网络共享**（手机设置 → 热点与网络共享 → USB 共享）再走一遍：
手感变好 → 问题在 Wi-Fi 空口；照旧 → 回到手机侧找。

### 底部工具栏

`左键` `右键` `滚轮模式` `复位` / `发送文件` `断开`。
连接期间屏幕保持常亮（`FLAG_KEEP_SCREEN_ON`）；点「断开」或用手势返回都会干净地关闭连接。

### 文件传输

- **发送**：点「发送文件」用系统文件选择器（SAF）挑文件，按 64 KiB 分块发送，
  底部状态条显示百分比与进度；
- **接收**：服务端发来的文件保存到系统「**下载**」目录，完成后提示的是**可以直接照着找的路径**
  （`内部存储/Download/<文件名>`，重名时是 MediaStore 回读到的真实名字，如 `报告 (1).pdf`），
  状态行保留该提示 12 秒并带一个「**打开**」按钮，一键交给系统合适的应用打开；
- 单文件上限 **512 MiB**（协议 §3.4），超过会拒绝；
- 先写 `.part` 临时文件，成功后原子落盘；连接中断或字节数不符会删除临时文件，
  **不会留半成品**；
- 收到的文件名会做清洗（去掉路径分隔符、`..`、控制字符、Windows 非法字符）。

## 6. 设置存储（`SettingsStore`）

用 `SharedPreferences`（文件 `airtouch_settings.xml`，App 私有目录，未加密）保存：

- 上次连接的 host / port / **sid** / **fp**（`last_sid` / `last_pin`）/ 设备 `keyId`；
- 上次使用的 token（仅用于手动输入对话框回填与排错，重连不再需要它）；
- 触摸板灵敏度、触摸板滚轮模式开关；
- 鼠标灵敏度、反向（左右/上下）、鼠标滚轮模式开关（与触摸板的开关相互独立）；
- 键盘布局（QWERTY / 九键）与键盘在全屏时的拖动位置；
- 每个服务器身份的证书固定值（key 前缀 `cert_pin_`，另存 `cert_hex_` 便于人工核对）。

**关于保存 token 的取舍**：v2 里 token 是一次性的，重连完全靠设备私钥，所以这个字段已经**不是**
信任凭据（读到它也无法连上已被消费的配对票据）。之所以仍然保存，只是为了手动输入时回填和排错。
`AndroidManifest.xml` 的备份规则（`backup_rules.xml` / `data_extraction_rules.xml`）已经把该
`SharedPreferences` 文件从云备份与换机迁移中**排除**；设备私钥本身在 `AndroidKeyStore` 里，
不可导出、也**不会**进入备份。

## 7. 代码结构

```
app/src/main/java/com/airtouch/app/
  pairing/PairingActivity.kt      扫码配对（CameraX + 权限 + 手动输入 + 免扫码重连 + 重置证书信任）
  pairing/QrCodeAnalyzer.kt       Y 平面 → zxing 解码（后台线程，TRY_HARDER）
  control/ControlActivity.kt      控制主界面：TabLayout + show/hide Fragment，连接生命周期与错误码映射
  control/ControlBridge.kt        Fragment ↔ Activity 的最小接口
  control/TouchpadFragment.kt     触摸板页
  control/AirMouseFragment.kt     鼠标页（专用传感器线程、可拖动复位按钮、实时采样率）
  input/TouchpadView.kt           手势 → move/click/scroll（含历史采样点还原）
  input/AirMouseEngine.kt         陀螺仪 → 像素位移/滚轮（纯计算，死区在原始值上，左右取 Z 轴）
  net/AirTouchClient.kt           OkHttp WebSocket 封装 + 认证状态机 + 自动重连 + 消息路由
  net/Protocol.kt                 协议编解码（org.json，全部字段名集中在这里）
  net/AuthCrypto.kt               规范串 / base64url / keyId / 指纹（与 C#、Node 逐字节一致）
  net/DeviceIdentity.kt           AndroidKeyStore 里的 P-256 设备密钥（含软件回退路径）
  net/PinnedTrust.kt              按 sid 的证书固定 + 首次 TOFU
  net/WifiLatencyLock.kt          连接期间抓住 Wi-Fi 低延迟锁（避免省电导致的成串到达）
  net/PairingUri.kt               配对/重连地址解析（扫码、手动输入、上次连接共用）
  net/AppVersion.kt               版本号（welcome/hello 用）
  file/FileSender.kt              SAF 选文件 → 64 KiB 分块发送（专用单线程）
  file/FileReceiver.kt            收文件 → .part → MediaStore/公共下载目录 + 可打开的 content URI
  settings/SettingsStore.kt       SharedPreferences 封装
```

## 8. 连接参数（为什么读写超时是 0）

`AirTouchClient` 的 OkHttp 配置如下：

| 参数 | 值 | 原因 |
| --- | --- | --- |
| `connectTimeout` | 10s | 建连/握手阶段的硬上限，避免连不上时长时间转圈 |
| `readTimeout` | **0（不限）** | wss 长连接空闲时本来就没有数据流动，设读超时会在空闲期把连接误判为失败 |
| `writeTimeout` | **0（不限）** | 大文件分块发送时不应被单次写超时打断 |
| `pingInterval` | 20s | 客户端主动发 WebSocket Ping 保活（比服务端的 30s 更密），链路故障时能更快触发重连；OkHttp 自动回 Pong |

也就是说，链路的「空闲判活」完全交给 ping/pong（协议 §1.3），而不是读超时。

## 9. 已知限制

1. **未做证书有效期校验**：固定只比对指纹，不检查 `notBefore` / `notAfter`。
   自签名证书的有效期由服务端决定，指纹一致才是唯一信任依据；另外**有意**不做主机名校验
   （见 §3「证书信任」）。
2. **`keyId` 与设备绑定，重装 App 会换新身份**：`AndroidKeyStore` 里的密钥随应用卸载而消失，
   重装后是一台「新设备」，必须重新扫码配对（电脑端可在信任列表里移除旧条目）。
3. **收到超限文件时无法回执**：协议里 `file_ack` 只有「服务端 → 客户端」方向，
   客户端发现 `fileSize` 超过 512 MiB 时只能丢弃后续二进制块并提示，
   无法让服务端立刻停止发送（服务端要靠自己的超时/字节校验收尾）。
4. **`file_end` 的字节数校验是「严格相等」**：如果服务端先发 `file_end` 再补发数据，
   会被判成 error。这是按协议 §3.3 实现的（先收到 `file_end` 且字节数不等 → error）。
5. **鼠标的轴向映射是固定映射**（左右 = Z 轴、上下 = X 轴），不做
   `SensorManager.remapCoordinateSystem`。横屏或竖直握持时方向会不符合直觉，
   需要手动开「反向（左右）/反向（上下）」。这是刻意的取舍：
   自动 remap 会在手机姿态变化时改变轴向，反而更难控制。
6. **传感器精度取决于手机**：采样周期请求「最快但不超过 500Hz」（`max(2ms, minDelay)`），
   实际速率由硬件决定（界面会显示实测值）。部分设备的陀螺仪噪声较大，可能需要调低灵敏度。
7. **不支持多指超过 2 指的手势**，第三根及以后的手指会被忽略（双指轻点/滚动只看前两指）。
8. **release 未开启混淆压缩**（`isMinifyEnabled = false`），
   `proguard-rules.pro` 已备好但未生效。启用前请重新确认 `org.json` 与反射相关规则。
9. **`VIBRATE` 权限当前没有被使用**（保留声明，便于后续加触感反馈）。
10. **未做前台服务**：App 被切到后台或锁屏后，WebSocket 可能被系统挂起或断开；
    回到前台后客户端会自动重连（v2 不再退回配对界面），但后台期间的输入不会被补发。
11. **不支持 0 字节文件**：协议要求 `fileSize` 真实，接收侧也会拒绝 `fileSize <= 0`，
    所以空文件两个方向都传不了。
12. **未知大小的文件无法发送**：拿不到 `OpenableColumns.SIZE` 且
    `openAssetFileDescriptor(...).length` 也为 -1 时，客户端会**本地直接失败**并提示
    「无法读取文件大小」，而不是发出 `fileSize:0` 的头（后者会被服务端按超量直接拒绝，
    整份文件白传）。
13. **`FileReceiver.abort()` 与 `write()/complete()` 之间没有加锁**：极端情况下
    （销毁界面恰好撞上正在写盘）可能关闭并删除一个正在写入的 `.part`。当前设计是
    单写者模型，加锁会让结构变复杂，先记为已知问题。
14. **`fp` 只在二维码里出现**：手动输入地址时无法严格校验指纹，只能 TOFU。
    要严格校验请用扫码。
