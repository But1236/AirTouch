# AirTouch Android 客户端

把手机当作电脑的外设：**空中鼠标**（陀螺仪控制光标）+ **触摸板**（单指移动、双指滚动）+ 文件双向传输。
配套的 Windows 服务端在 `../server/`，通信协议以 `../docs/PROTOCOL.md` 为唯一事实来源。

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
| Build Tools / Platform | 随 compileSdk 34 自动下载 | |

> **关于 `gradle-wrapper.jar`**：已随工程提供
> `gradle/wrapper/gradle-wrapper.jar`（Gradle 8.7 官方 wrapper jar，43 KB），
> `gradlew` / `gradlew.bat` 可直接使用，无需另外下载。
> 若该文件被误删，`./gradlew` 会报
> "Could not find or load main class org.gradle.wrapper.GradleWrapperMain"，
> 此时用 Android Studio 打开工程会自动补齐（或在本机装有 Gradle 时执行 `gradle wrapper`）。

## 2. 构建与运行

### 用 Android Studio（推荐）

1. `File → Open`，选择 `android/` 目录（**不是**仓库根目录）；
2. 首次打开时 Android Studio 会提示同步 Gradle、下载 SDK 34 与依赖，允许即可；
3. 等待 Sync 完成 → 连接手机（开启 USB 调试）→ 点 ▶ Run。

### 用命令行

```bash
cd android
./gradlew assembleDebug          # 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # 直接装到已连接的设备
./gradlew assembleRelease        # release 未开启混淆，可直接安装
```

Windows：

```bat
cd android
gradlew.bat assembleDebug
```

`local.properties`（SDK 路径）由 Android Studio 自动生成；命令行构建需要自己创建：

```properties
sdk.dir=C\:\\Users\\<用户名>\\AppData\\Local\\Android\\Sdk
```

## 3. 配对流程（怎么连上）

1. 电脑端启动 AirTouch 服务程序，窗口里会显示一个二维码，内容形如
   `wss://192.168.1.5:8443/ws?token=xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx`
   （协议 §1.1）；
2. 手机与电脑连同一个局域网（同一个 Wi-Fi / 热点）；
3. 手机打开 AirTouch，首次会申请**相机权限**，允许后对准二维码即可自动识别；
4. 识别成功后自动停止相机并进入控制界面，连接成功时顶部会显示
   `已连接 · 192.168.1.5:8443`。

也可以点「**手动输入地址**」，手填主机 IP、端口（留空默认 8443）和 token。
手动输入和扫码走的是**同一套校验**（`PairingUri.parse` / `fromParts`），
所以粘贴完整 URL 到「主机地址」里也能识别。

### 证书信任（TOFU，协议 §4 强制）

服务端用的是**自签名证书**，系统 CA 校验必然失败，因此客户端采用 TOFU
（Trust On First Use）：

- 第一次连接某个 IP 时，把服务端证书的 **SHA-256 指纹**记录到本机；
- 之后每次连接都必须指纹一致，否则**立即中断连接**并提示
  「证书指纹已变化…」；
- 如果你在电脑端**重新生成了服务器证书**（例如删掉了服务端的证书文件、
  或换了电脑），请回到配对界面点「**重置证书信任**」清除已记录的指纹，
  然后重新扫码；
- 客户端**不存在**任何 `TrustAll` 分支；主机名校验也不是「无条件通过」，
  只接受与本次配对地址相同的主机名。

### 断线后怎么重连

协议规定服务端每次断开都会**重新生成 token 并刷新二维码**。所以断线后：

- 手机上会 Toast 提示并自动退回配对界面；
- 直接用**新二维码**重新扫码即可（服务端绑定的 IP 不变，所以同一台手机能连上，
  别的设备仍会被拒）。
- 服务端把「用过的旧 token」识别为**重放**并返回 `token_expired`，这类失败
  **不计入**防暴破的 3 次封禁额度，所以偶尔掉线重连不会把这台手机封掉。界面会按错误码
  给出具体提示（`token_expired` → 请重新扫码；`banned` / `ip_locked` → 需要回到电脑端
  点「解除绑定并重置」；`rate_limited` → 稍等几秒；`busy` → 电脑上已有设备在连接）。

## 4. 权限说明

| 权限 | 何时申请 | 用途 | 拒绝的后果 |
| --- | --- | --- | --- |
| `CAMERA` | 进入配对界面时 | 扫描服务端二维码 | 无法扫码，可改用手动输入地址 |
| `INTERNET` | 安装时授予 | wss 连接 | 无法工作 |
| `ACCESS_NETWORK_STATE` | 安装时授予 | 网络状态 | 仅影响诊断信息 |
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
| 底部「滚轮模式」开启后，单指上下拖动 | 改为滚轮（`scroll`），按钮会变绿以示区分 |

- 灵敏度滑杆 **0.5x ~ 3.0x**，可实时调整并自动保存；
- 所有位移换算都以 **dp** 为基准（内部按 density 折算），换手机手感一致；
- 取整后的**余数会累积**，慢速拖动不会丢步。

### 空中鼠标（「空中鼠标」标签页）

- 使用陀螺仪（`SENSOR_DELAY_GAME`）。竖持手机时：**手腕向右转 → 光标右移**，
  **顶部向自己倾斜 → 光标下移**；
- 「反向 X / 反向 Y」可反转方向；灵敏度 **0.1x ~ 10.0x** 可调并保存；
- 右下角有一个**可拖动的悬浮复位按钮**，点击后发送 `reset` 并清空引擎的余数与滤波器状态；
- 设备没有陀螺仪时显示中文说明并禁用该功能（**不会崩溃**）；
  只有标准陀螺仪完全缺失时才降级使用 `TYPE_GYROSCOPE_UNCALIBRATED`，界面上会写明。

### 底部工具栏

`左键` `右键` `滚轮模式` `复位` / `发送文件` `断开`。
连接期间屏幕保持常亮（`FLAG_KEEP_SCREEN_ON`）；点「断开」或用手势返回都会干净地关闭连接。

### 文件传输

- **发送**：点「发送文件」用系统文件选择器（SAF）挑文件，按 64 KiB 分块发送，
  底部状态条显示百分比与进度；
- **接收**：服务端发来的文件自动保存到系统「**下载**」目录，
  完成/失败都会有 Toast 与状态提示；
- 单文件上限 **512 MiB**（协议 §3.4），超过会拒绝；
- 先写 `.part` 临时文件，成功后原子落盘；连接中断或字节数不符会删除临时文件，
  **不会留半成品**；
- 收到的文件名会做清洗（去掉路径分隔符、`..`、控制字符、Windows 非法字符）。

## 6. 设置存储（`SettingsStore`）

用 `SharedPreferences`（文件 `airtouch_settings.xml`，App 私有目录，未加密）保存：

- 上次连接的 host / port；
- **上次使用的 token**；
- 触摸板灵敏度、滚轮模式开关；
- 空中鼠标灵敏度、反向 X/Y；
- 每个 host 的证书 SHA-256 指纹（key 前缀 `cert_pin_`）。

**关于保存 token 的取舍（刻意决定，不是疏漏）**：
为了方便「重连」时不用重新扫码，token 会明文保存在 App 私有的
`SharedPreferences` 里。代价是：root 过的设备或备份提取可能读到它。
之所以接受这个代价，是因为协议本身给 token 加了三重限制——**一次性**
（鉴权成功即销毁）、**IP 绑定**、**同一 IP 连续 3 次失败即永久封禁**——
且只在局域网内有效；同时 `AndroidManifest.xml` 的备份规则
（`backup_rules.xml` / `data_extraction_rules.xml`）已经把该
`SharedPreferences` 文件从云备份与换机迁移中**排除**。
如果不希望保存，删掉 `SettingsStore.lastToken` 的读写与
`PairingActivity.persistAndConnect()` 中的一行即可。

## 7. 代码结构

```
app/src/main/java/com/airtouch/app/
  pairing/PairingActivity.kt      扫码配对（CameraX + 权限 + 手动输入 + 重置证书信任）
  pairing/QrCodeAnalyzer.kt       Y 平面 → zxing 解码（后台线程，TRY_HARDER）
  control/ControlActivity.kt      控制主界面：TabLayout + show/hide Fragment，连接生命周期
  control/ControlBridge.kt        Fragment ↔ Activity 的最小接口
  control/TouchpadFragment.kt     触摸板页
  control/AirMouseFragment.kt     空中鼠标页（传感器注册/注销、可拖动复位按钮）
  input/TouchpadView.kt           手势 → move/click/scroll
  input/AirMouseEngine.kt         陀螺仪 → 像素位移（纯计算）
  net/AirTouchClient.kt           OkHttp WebSocket 封装 + 消息路由 + 状态机
  net/Protocol.kt                 协议编解码（org.json，全部字段名集中在这里）
  net/PinnedTrust.kt              TOFU 证书指纹固定 + host 限定校验
  net/PairingUri.kt               配对地址解析（扫码/手动输入共用）
  file/FileSender.kt              SAF 选文件 → 64 KiB 分块发送（专用单线程）
  file/FileReceiver.kt            收文件 → .part → 公共「下载」目录
  settings/SettingsStore.kt       SharedPreferences 封装
```

## 8. 连接参数（为什么读写超时是 0）

`AirTouchClient` 的 OkHttp 配置如下：

| 参数 | 值 | 原因 |
| --- | --- | --- |
| `connectTimeout` | 10s | 建连/握手阶段的硬上限，避免连不上时长时间转圈 |
| `readTimeout` | **0（不限）** | wss 长连接空闲时本来就没有数据流动，设读超时会在空闲期把连接误判为失败 |
| `writeTimeout` | **0（不限）** | 大文件分块发送时不应被单次写超时打断 |
| `pingInterval` | 20s | 客户端主动发 WebSocket Ping 保活（比服务端的 30s 更密），链路故障时能更快触发 `onFailure`；OkHttp 自动回 Pong |

也就是说，链路的「空闲判活」完全交给 ping/pong（协议 §1.3），而不是读超时。

## 9. 已知限制

1. **未做证书有效期校验**：TOFU 只比对指纹，不检查 `notBefore` / `notAfter`。
   自签名证书的有效期由服务端决定，指纹一致才是唯一信任依据。若需要严格校验，
   应在 `PinnedTrust.checkServerTrusted` 里补充 `leaf.checkValidity()`。
2. **主机名限定为配对时的 IP**：`HostnameVerifier` 只接受与配对地址相同的主机名。
   同一台电脑换了 IP（例如从有线切到 Wi-Fi）时，需要重新扫码（指纹也是按 host 存的）。
3. **收到超限文件时无法回执**：协议里 `file_ack` 只有「服务端 → 客户端」方向，
   客户端发现 `fileSize` 超过 512 MiB 时只能丢弃后续二进制块并提示，
   无法让服务端立刻停止发送（服务端要靠自己的超时/字节校验收尾）。
4. **`file_end` 的字节数校验是「严格相等」**：如果服务端先发 `file_end` 再补发数据，
   会被判成 error。这是按协议 §3.3 实现的（先收到 `file_end` 且字节数不等 → error）。
5. **空中鼠标的轴向映射是固定映射**，不做 `SensorManager.remapCoordinateSystem`。
   横屏握持时方向会不符合直觉，需要手动开「反向 X/Y」。这是刻意的取舍：
   自动 remap 会在手机姿态变化时改变轴向，反而更难控制。
6. **不支持多指超过 2 指的手势**，第三根及以后的手指会被忽略（双指轻点/滚动只看前两指）。
7. **release 未开启混淆压缩**（`isMinifyEnabled = false`），
   `proguard-rules.pro` 已备好但未生效。启用前请重新确认反射/序列化相关规则。
8. **`VIBRATE` 权限当前没有被使用**（保留声明，便于后续加触感反馈）。
9. 未做**前台服务**：App 被切到后台或锁屏后，WebSocket 可能被系统挂起或断开，
   重新回到前台会看到「连接已关闭」并退回配对界面。这是当前版本的预期行为。
10. **不支持 0 字节文件**：协议要求 `fileSize` 真实，接收侧也会拒绝 `fileSize <= 0`，
    所以空文件两个方向都传不了。若确实需要，应在协议里为 0 字节单独定义（例如
    `file_header` 后立即 `file_end`，服务端只创建空文件）。
11. **分块大小写死为 65536**（`Protocol.CHUNK_SIZE`），没有采用服务端 `welcome.chunkSize`
    通告值。当前两边都是 65536，且服务端只按累计字节数校验，因此安全；但如果以后服务端
    把 `chunkSize` 调小，客户端需要改用 `welcome.chunkSize`。
12. **未知大小的文件无法发送**：拿不到 `OpenableColumns.SIZE` 且
    `openAssetFileDescriptor(...).length` 也为 -1 时，客户端会**本地直接失败**并提示
    「无法读取文件大小」，而不是发出 `fileSize:0` 的头（后者会被服务端按超量直接拒绝，
    整份文件白传）。
13. **`FileReceiver.abort()` 与 `write()/complete()` 之间没有加锁**：极端情况下
    （销毁界面恰好撞上正在写盘）可能关闭并删除一个正在写入的 `.part`。当前设计是
    单写者模型，加锁会让结构变复杂，先记为已知问题。
