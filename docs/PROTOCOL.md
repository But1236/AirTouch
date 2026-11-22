# AirTouch 通信协议 (v2)

本文件是 Windows 服务端与 Android 客户端的**唯一事实来源**。两端实现如有冲突，以本文件为准。

- 传输层：`wss://`（TLS 1.2+，服务端自签名证书）
- 通信模型：全双工 WebSocket，局域网直连（手机与电脑互访，不依赖任何云服务、无 NAT 穿透）
- 文本帧：UTF-8 JSON 控制指令
- 二进制帧：文件分块数据，**必须**紧跟在同方向的 `file_header` 文本帧之后
- 协议版本：`2`

v1 → v2 的两处结构性变化，先看这段就能理解全篇：

1. **认证代替「token 即身份」**：`token` 只在首次配对时用一次，用完即毁；之后手机用自己的
   ECC 私钥签名证明身份（§1.2）。
2. **不再绑定来源 IP**：身份是「设备公钥」，不是「IP」。换 Wi-Fi、路由器重新分配 IP 都能直接重连，
   同时也只允许一台设备（§1.2、§4）。

---

## 1. 连接与鉴权

### 1.1 地址与二维码

```
wss://{IP}:{PORT}/ws?token={TOKEN}&sid={SID}&fp={FP}
```

| 字段 | 说明 |
| --- | --- |
| `IP` | 服务端所在电脑的局域网 IPv4 地址（由服务端 UI / 二维码给出） |
| `PORT` | 默认 `8443`，可在服务端 UI 修改 |
| `TOKEN` | **一次性**配对票据：服务端启动时生成、配对成功即销毁并轮换。手动输入地址时可留空（纯重连） |
| `SID` | 服务器身份标识：`base64url(SHA-256(公钥)[0..16])` → 22 个字符。用于把证书固定值绑定到「这台电脑」而不是某个 IP |
| `FP` | 服务端证书的 **SPKI 指纹**（同 `SID` 的算法，但输入是叶证书的公钥）。首次配对时用于严格校验 |

二维码内容就是这个 URL 本身（约 121 字符，纠错级别 M）。客户端扫码后：

- 若 URL 带 `token` → 进入**配对模式**（`mode=pair`），并把 `sid`/`fp` 一并保存；
- 若 URL 不带 `token` → 进入**重连模式**（`mode=resume`），只靠设备私钥证明身份；
- 手动输入地址时 `sid`/`fp` 可以留空：此时证书固定退化为 TOFU（首次连接记录，之后严格比对），
  但**仍然必须**按 `sid` 记录固定值，否则换 IP 后会被判为「证书变了」。

### 1.2 认证握手（三步）

```
客户端                                            服务端
  |--- WebSocket 握手（wss，验证证书固定值）-------->|
  |<-- 文本 auth_challenge --------------------------|   {auth_v,sid,nonce,serverTime,expiresIn,mode}
  |--- 文本 auth_req ------------------------------->|   {auth_v,keyId,token?,pubKey?,sign,ts,deviceName,appVersion}
  |<-- 文本 welcome --------------------------------|   认证通过
  |--- 文本 hello（可选）-------------------------->|
```

**第 1 步 `auth_challenge`（服务端 → 客户端）**

```json
{"type":"auth_challenge","auth_v":1,"sid":"…","nonce":"…","serverTime":1731300000,"expiresIn":12,"mode":"pair"}
```

| 字段 | 说明 |
| --- | --- |
| `auth_v` | 认证协议版本，当前 `1` |
| `sid` | 服务器身份；客户端必须用它选取证书固定值（不再用 IP） |
| `nonce` | 本次挑战的随机值，**每个 socket 只允许使用一次** |
| `serverTime` | 服务端 Unix 秒；客户端用它校正本机时钟偏差 |
| `expiresIn` | 挑战有效期（秒），默认 `12` |
| `mode` | `pair` 或 `resume`（服务端已经根据 URL 里有没有 `token` 判定好） |

客户端在收到 `auth_challenge` 之前**不得**发送任何控制指令（`hello` 也不行）；违规指令会被直接丢弃。

**第 2 步 `auth_req`（客户端 → 服务端）**

```json
{"type":"auth_req","auth_v":1,"keyId":"…","token":"…","pubKey":"…","sign":"…","ts":1731300000,
 "deviceName":"Xiaomi 14","appVersion":"2.0.2"}
```

| 字段 | 说明 |
| --- | --- |
| `keyId` | `base64url(SHA-256(设备公钥 SPKI)[0..16])`，22 字符 |
| `token` | **仅配对模式**携带；重连模式**不得**携带（带了也会被当成 `token_invalid`） |
| `pubKey` | 设备公钥（X.509 SPKI 的 base64url）。配对模式必填；重连模式可省略，服务端用 `keyId` 查已信任公钥 |
| `sign` | 对下面这串字节做的 ECDSA P-256 / SHA-256 签名，**ASN.1 DER** 编码的 base64url |
| `ts` | 客户端发出时的 Unix 秒（已用 `serverTime` 校正过本机偏差） |
| `deviceName` / `appVersion` | 可选，供服务端 UI 显示 |

待签名字节（**逐字节**，行尾是 `\n`，UTF-8）：

```
AirTouch-Auth-v1\n{sid}\n{nonce}\n{keyId}\n{ts}
```

**第 3 步 `welcome`（服务端 → 客户端）**

```json
{"type":"welcome","protocolVersion":2,"serverVersion":"2.0.2","serverIp":"192.168.1.7",
 "sid":"…","displayHz":180,"keyId":"…","authMode":"resume"}
```

| 字段 | 说明 |
| --- | --- |
| `protocolVersion` | 协议版本，当前 `2` |
| `serverVersion` | 应用版本号（不是协议版本） |
| `serverIp` | 服务端认为自己所在的本机地址（仅供参考，客户端应以实际连接地址为准） |
| `sid` | 服务器身份，客户端应持久化用于后续重连 |
| `displayHz` | 主显示器刷新率（**v2 追加，可选**；`0` 表示查不到）。这是感知流畅度的上限：发送频率超过它不会有额外收益 |
| `keyId` | 服务端记下的本设备密钥 id |
| `authMode` | `pair` 或 `resume`，客户端据此提示「首次配对 / 免扫码重连」 |

**证书校验规则（客户端，必须实现）**

1. 校验服务端证书链/自签名有效性，并计算叶证书 SPKI 指纹（base64url，22 字符）。
2. 若本次 URL 带了 `fp`：指纹必须**严格相等**，否则立即中断并提示重新扫码。
3. 否则按 `sid`（无 `sid` 时退化为 host）查已保存的固定值：有则严格比对（TOFU 之后的强校验）；
   没有则记录为首次信任（TOFU）。
4. **禁止**无条件 `TrustAll`。

### 1.3 校验顺序、错误码与限制

URL 与 `auth_req` 的校验顺序固定为：
`路径 → 封禁 → 速率限制 → 待认证连接数上限 → token（仅配对模式）→ 公钥/签名/时间戳`。
前一步不通过时不会执行后续检查。

`error{code,message}` 的 `code` 取值（关闭码使用 WebSocket 标准码）：

| code | 关闭码 | 含义 | 客户端应如何处理 |
| --- | --- | --- | --- |
| `bad_path` | 1008 | 路径不是 `/ws` | 检查 URL |
| `banned` | 1008 | 该 IP 因连续认证失败被封禁 | 电脑端「断开并重置」 |
| `rate_limited` | 1008 | 同一 IP 10 秒内尝试超过 20 次 | 稍后自动重试 |
| `too_many_pending` | 1008 | 未完成认证的连接超过 `MaxPendingSockets=4` | 稍后自动重试 |
| `busy` | 1013 | 已有**另一台**已信任设备在线（单控制器独占） | 稍后自动重试 |
| `token_invalid` | 1008 | 配对模式下 token 缺失/不匹配（计入失败计数） | 重新扫码 |
| `token_expired` | 1008 | 重放了已消费的 token（**不**计入失败计数） | 切换到设备密钥重连 |
| `auth_required` | 1008 | 认证完成前发了控制指令 | 忽略该帧，不得关闭 |
| `auth_unsupported` | 1008 | 客户端 `auth_v` 高于服务端支持（旧客户端连新服务端才会出现） | 提示升级客户端 |
| `bad_key` | 1008 | `pubKey` 无法解析、不是 P-256，或 `keyId` 与其不匹配 | 重置证书信任后重新扫码 |
| `sig_invalid` | 1008 | 签名验证失败（换过设备密钥/被中间人篡改） | 重新扫码 |
| `stale_auth` | 1008 | `ts` 超出 `±120s` 窗口 | 用新的 `serverTime` 重试 |
| `key_unknown` | 1008 | 重连模式的 `keyId` 不在信任列表里 | 必须重新扫码配对 |
| `trust_revoked` | 1008 | 该设备刚在电脑端被移除信任 | 必须重新扫码配对 |

服务端限制常量：

| 常量 | 值 | 含义 |
| --- | --- | --- |
| `AuthTimeout` | `12s` | 未在此时限内完成认证的 socket 被回收 |
| `MaxPendingSockets` | `4` | 同时处于待认证状态的连接数上限 |
| `MaxAuthFailures` | `5` | 同一 IP 连续认证失败次数上限（达到即封禁） |
| `MaxTokenFailures` | `3` | 同一 IP 连续 token 校验失败次数上限（达到即封禁） |
| `AuthClockSkew` | `120s` | `ts` 允许的时钟偏差 |
| 设备上限 | `16` | 信任列表最多保存的设备数，超出时淘汰最久未用的 |

**成功后的服务端动作**：把该设备公钥写入信任列表（最多 16 台）→ 若本次是配对模式则
**销毁 token 并生成新 token**（二维码刷新）→ 发送 `welcome`。断开连接时**不再**轮换 token。

**单设备独占**：`keyId` 相同的重连会替换旧连接（旧连接收到关闭码 `1001`、原因 `replaced`）；
`keyId` 不同的已信任设备在第一条活动连接存在时收到 `busy`/`1013`。

**断线与重连**：客户端断线后按 1s → 2s → 4s → 8s → 15s 退避自动重连，重连时 URL **不带 token**。
只有收到 `key_unknown` / `trust_revoked` / `banned` / `bad_key` / `sig_invalid` / `auth_unsupported`
才会停止重连并要求重新扫码。

### 1.4 心跳

- 服务端每 `30s` 发送一个 **WebSocket Ping 帧**（RFC 6455 控制帧）。
- 客户端必须回 **Pong 帧**。OkHttp 会自动回 Pong，业务代码无需处理；Android 端也不应关闭这一行为。
- 客户端若使用 OkHttp 的 `pingInterval`，服务端同样会回 Pong（`OnPing → SendPong`），
  因此客户端不会因「ping 超时」而误断线。
- 服务端 `90s` 未收到任何帧（控制帧或数据帧）即判定链路失效并主动关闭。
- 任一方也可发送文本帧 `{"type":"ping"}`，对端**应**回 `{"type":"pong"}`（兼容不支持控制帧的场景）。

---

## 2. 消息定义

### 2.1 客户端 → 服务端（控制指令）

| type | 载荷 | 说明 |
| --- | --- | --- |
| `move` | `dx:int`, `dy:int` | 鼠标相对移动（像素，右/下为正）。`dx`/`dy` 被截断到 `[-4096, 4096]` |
| `click` | `button:"left"\|"right"` | 单击；`button` 缺省视为 `left` |
| `button` | `button:"left"\|"right"`, `down:bool` | **鼠标按键的一次状态变化**（只有按下或只有抬起，与 `key` 同构，§2.6）。它存在的唯一理由是拖拽：按下 → 一串 `move` 帧 → 抬起。`button` 缺省视为 `left`；`down` 缺省（或不是布尔值）按 `false`（抬起）处理 —— 抬起一个没按下的键是无害的空操作，而缺省「按下」会在电脑上留下一个永远不松开的鼠标键，整个桌面会一直处于拖拽框选中。**抬起帧由客户端负责发**；服务端在连接结束时（§2.6 同款卡键保护）与收到 `reset` 时会把所有按下的鼠标键抬起 |
| `scroll` | `amount:int` 或 `delta:int`, `axis?:"v"\|"h"` | 滚轮。`amount` 是「格」（正数上滚，负数下滚），截断到 `[-120, 120]`；`delta` 是**原生滚轮单位**（`120` = 1 格，可以远小于 120），截断到 `[-1200, 1200]`。**`delta` 存在时优先于 `amount`**；`amount` 保持旧语义 `amount × 120`。`axis:"h"` 走横向滚轮（`MOUSEEVENTF_HWHEEL`），其它值按纵向 |
| `touch` | `phase:"down"\|"move"\|"up"`, `scale?:double`, `dx?:int`, `dy?:int` | **双指捏合/张开的几何流**。`phase` 是 `down`（两指出手）、`move`（携带相对缩放倍数与手势中心位移）、`up`（抬手）；`phase` 不是这三个值之一时整帧被忽略。客户端只发几何量，**用触控注入还是 `Ctrl`+高精度滚轮由服务端决定**（见下文），实际用的是哪条会通过 `zoom_status` 告知 |
| `zoom_mode` | `mode:"auto"\|"native"\|"wheel"` | **缩放偏好**（大小写不敏感）。`auto`（默认）= 有触控栈就用原生触控、否则滚轮；`native` = 优先原生触控，但没有触控栈时照样回退，并通过 `zoom_status.available=false` 如实告知；`wheel` = 即使有触摸屏也强制 `Ctrl`+滚轮。`mode` 缺失、`null`、数字或拼错一个字母时**整帧被忽略**（与 `touch.phase` 同一条规则）—— 静默按 `auto` 处理只会让客户端的拼写错误看起来像设置成功了 |
| `zoom_probe` | 无 | **立刻重新探测触控能力**：服务端重读 `SM_MAXIMUMTOUCHES`（`>0` 时再试 `InitializeTouchInjection`），若因此变得可用就**热切换**到 `InjectTouchInput` —— 不需要重启服务端，也不需要断开手机。无论结论是否变化都会回一帧 `zoom_status` |
| `reset` | 无 | 光标复位到主屏中心 |
| `key` | `vk:int`, `down:bool`, `ext?:bool` | **键盘按键的一次状态变化**（§2.6）。`vk` 为 Win32 虚拟键码（`1..255`，越界被截断）；`down=true` 是 KEY_DOWN、`false` 是 KEY_UP；`ext` 可选，显式指定 `KEYEVENTF_EXTENDEDKEY` |
| `text` | `s:string` | **注入一段 Unicode 文本**（§2.7）。与键盘布局无关，中文/emoji 都走这条；`s` 截断到 256 个 UTF-16 码元。缺 `s` 或 `s` 不是 JSON 字符串时整帧被忽略 |
| `auth_req` | 见 §1.2 | 认证应答 |
| `file_header` | `fileName:string`, `fileSize:int`, `fileId?:string` | 文件传输开始（见 §3） |
| `file_end` | `fileId?:string` | 可选：显式声明本次传输结束（正常实现靠字节数判定） |
| `hello` | `deviceName?:string`, `appVersion?:string` | 可选：认证通过后上报设备信息，供服务端 UI 显示 |
| `ping` | 无 | 可选心跳 |

示例（拖拽 = 第 2 行的按住、若干 `move`、第 4 行的松开，缺一不可）：

```json
{"type":"move","dx":10,"dy":-5}
{"type":"click","button":"left"}
{"type":"button","button":"left","down":true}
{"type":"button","button":"left","down":false}
{"type":"scroll","amount":3}
{"type":"scroll","delta":15}
{"type":"scroll","delta":-20,"axis":"h"}
{"type":"reset"}
{"type":"key","vk":17,"down":true}
{"type":"text","s":"你好"}
{"type":"file_header","fileName":"test.txt","fileSize":1024}
{"type":"touch","phase":"down"}
{"type":"touch","phase":"move","scale":1.35,"dx":-4,"dy":2}
{"type":"touch","phase":"up"}
{"type":"zoom_mode","mode":"wheel"}
{"type":"zoom_probe"}
```

#### 双指捏合（`touch`）与高精度滚动（`scroll.delta`）

**为什么是一条几何流，而不是「放大 / 缩小」两条命令**：手机并不知道电脑当前的缩放级别，也不该
知道 —— 它能测到的只有「两指间距是手势开始时的几倍」和「手势中心移动了多少」。服务端拿这两个量
复原手势，至于用哪个 Windows API，是服务端的实现细节：

| 服务端后端 | 条件 | 实现 |
| --- | --- | --- |
| 触控注入 | 偏好不是 `wheel`，且本机有触摸输入栈（`SM_MAXIMUMTOUCHES > 0` 且 `InitializeTouchInjection` 成功） | `InjectTouchInput` 放两个水平对称的虚拟触点：间距 = `BaseSpanPx(240) × scale`，中心 = 手势开始时的光标位置 + 累计 `dx`/`dy` |
| 滚轮回退 | 其它情况（偏好为 `wheel`，或本机没有触控栈） | `Ctrl` + 高精度滚轮：两指间距每变化 **12%** 记 1 格，即 `units = ln(scale ÷ 上一帧 scale) ÷ ln(1.12) × 120`；单帧最多 120 单位，多出来的留到后续帧 |

两条路对 `touch` 帧本身完全等价：这条帧里没有、也不会有「用哪个后端」这种字段（后端是服务端的实现
细节）。但服务端**会**通过 `zoom_status`（以及 `welcome` 里的快照字段）主动告诉手机当前实际生效的是
哪一条，并对手机发来的偏好（`zoom_mode`）作出响应 —— 见下一小节。服务端启动时探测一次能力，并在窗口
日志里写一句人话（例如「本机没有触摸数字化仪（`SM_MAXIMUMTOUCHES=0`），双指缩放改用 `Ctrl`+高精度
滚轮」）。

| 字段 | 说明 |
| --- | --- |
| `phase` | `"down"` / `"move"` / `"up"`，大小写不敏感。**不是这三个值之一（缺字段、`null`、数字、拼错）时整帧被忽略**，与 `text` 缺 `s` 同一条规则（§2.7）：静默当成某个阶段，只会让客户端的一个拼写错误看起来像手势生效了。`down` 之后是一串 `move`，最后应当发 `up` |
| `scale` | double，「当前两指间距 ÷ 手势开始时的间距」，**相对值**，因此与手机的屏幕尺寸、dpi 无关。缺省或非数字按 `1.0`（按 0 会让一次捏合变成缩到最小）；截断到 `[0.05, 20]`。只在 `move` 上有意义 |
| `dx`/`dy` | int，手势中心相对**上一帧**的移动（屏幕像素，右 / 下为正），截断到 `[-4096, 4096]`，缺省 0。触控后端用它移动两个触点；**回退后端忽略它** —— 捏合不该把用户的光标拖着走，双指平移请用 `scroll` 帧 |

`up` 之后所有手势状态清零，再次 `down` 是新手势。没有 `down` 的 `move` 被忽略（不会凭空造出一对
没人按下的触点）；没有 `up` 就断线时由服务端收尾（见下）。

**高精度滚动**：`delta` 用原生滚轮单位，`120` 是一格，但 `15`/`20` 这种小于一格的量 Windows 会替
应用累加，所以双指上下 / 左右滑可以按像素级细粒度发送 —— 这正是「丝滑滚动」与「一格一格跳」的
区别。`amount` 保持旧语义，老客户端逐字节不变：`{"type":"scroll","amount":3}` 与
`{"type":"scroll","delta":360}` 在服务端产生同一个滚轮事件。`delta` 只要**存在**就优先于 `amount`，
即使它写不出数字（按 `0` 处理，§2.3），也不会静默退回 `amount` —— 那会让一个拼写错误看起来像成功。

**卡住保护（服务端必须实现）**：与鼠标按键（§2.1）、键盘修饰键（§2.6）同一条规则 —— 会话结束时
（socket 关闭 / 被踢 / 看门狗超时 / 撤销信任 / 服务停止 / 收到 `reset`），服务端必须收掉还没结束的
手势：触控后端抬起（或取消）两个虚拟触点，回退后端保证 `Ctrl` 已经抬起。回退后端每次滚轮注入都是
自成一体的一组 `Ctrl down → wheel → Ctrl up`，绝不长时间按住 `Ctrl`。一个卡住的触点和一个卡住的
`Ctrl`，都会让用户的桌面变得不可用。

#### 缩放偏好、运行期重探与后端状态（`zoom_mode` / `zoom_probe` / `zoom_status`）

谁用哪个后端本来完全由服务端自己决定，客户端看不见也管不着。这一节加的三件事是：**偏好**（客户端
想用哪个）、**状态**（实际用的是哪个）、**重探**（PC 刚接上触控设备，立刻切过去）。一句话概括：
`zoom_mode` 写偏好，`zoom_probe` 让服务端重读硬件，`zoom_status` 把结果告诉手机。

| 偏好（`preference`） | 有触控栈的机器 | 没有触控栈的机器 |
| --- | --- | --- |
| `auto`（默认） | `native_touch` | `ctrl_wheel_fallback`，`available=false` |
| `native` | `native_touch` | `ctrl_wheel_fallback`，`available=false`（**如实报告不可用**） |
| `wheel` | `ctrl_wheel_fallback`，`available=true` | `ctrl_wheel_fallback`，`available=false` |

`preference` 与 `mode` 是两件事，别混：`mode` 只说**实际生效**的后端，永远只取 `native_touch` /
`ctrl_wheel_fallback` 两个值之一；想知道用户选了什么，看 `preference`。

`zoom_status` 的发送时机（三条都要做到）：

1. `welcome` 之后**立刻**再发一帧（`welcome` 里也带了同样的快照字段 `zoomMode`/`zoomAvailable`/
   `zoomReason`/`zoomPreference`，这是刻意的冗余：手机重连后第一帧就是对的，不必等第二次状态变化）；
2. 状态**发生变化**时 —— 手机改偏好、PC 窗口改偏好、PC 窗口点「重新检测」、热切换成功或失败；
3. 收到 `zoom_mode` / `zoom_probe` 后立刻回一帧；`zoom_probe` **即使结论没变也要回**：用户按了按钮，
   必须看到反馈。

**热切换会打断正在进行的手势**：如果一个捏合正做到一半（发了 `down`、还没发 `up`），而此时后端从滚轮
切成原生触控（或反向），服务端会先把旧后端收干净（抬触点 / 抬 `Ctrl`），然后**忽略**后续 `move`，直到
客户端重新发 `down`。所以客户端收到 `zoom_status` 且 `mode` 与上次不同时，应当把手上的手势当成已被
服务端结束：要么重新发 `down` 续上，要么等用户松手后再开始新的捏合。**绝不能假设「切后端不打断
手势」** —— 那样会在电脑上留下一个永远没人抬起的触点。

**为什么不随包提供虚拟触摸驱动**：AirTouch 与服务端都是 MIT，定位是「装完即用」——不申请证书、不要求
测试模式、不安装任何驱动、不改系统设置。所以原生触控这条路的**唯一前提是这台电脑本身已有触控数字化仪**
（笔记本触摸屏、USB 触摸屏、外接触控显示器）：有就用，没有就自动回退 `Ctrl`+高精度滚轮，两条路都不需要
用户做任何配置。`zoom_probe` 因此只解决一件事：**用户给电脑接上/启用了触控设备之后，不必重启服务端、
不必让手机重连**，点一下「重新检测」就当场重读 `SM_MAXIMUMTOUCHES` 并热切换。将来若真有可自由再分发、
微软签名的虚拟触摸驱动，它也只是让 `SM_MAXIMUMTOUCHES` 变成 `>0` 而已 —— 协议、这三条帧与客户端都
不需要再改，服务端也不需要新增任何「安装驱动」的接口。

> 这三条帧都是**纯新增**：老客户端不发它们，服务端照旧按「启动时探测一次」的结果工作；协议版本仍是
> `2`（§5）。

### 2.2 服务端 → 客户端

| type | 载荷 | 说明 |
| --- | --- | --- |
| `auth_challenge` | 见 §1.2 | 连接建立后的第一帧 |
| `welcome` | 见 §1.2；另带初始缩放快照 `zoomMode`/`zoomAvailable`/`zoomReason`/`zoomPreference`（与 `zoom_status` 同义，刻意冗余） | 认证通过后的第一帧 |
| `zoom_status` | `mode:"native_touch"\|"ctrl_wheel_fallback"`, `available:bool`, `reason:string`, `preference:"auto"\|"native"\|"wheel"`, `maxTouches:int`, `digitizer:int` | 当前缩放状态：`mode` 是**实际生效**的后端，`preference` 是偏好，`reason` 只在不可用时非空（中文句子），后两项是 `SM_MAXIMUMTOUCHES`/`SM_DIGITIZER` 的原始值，供手机端诊断展示。发送时机见 §2.1 |
| `file_header` | `fileName:string`, `fileSize:int`, `fileId?:string` | 服务端向手机发文件（见 §3） |
| `file_end` | `fileId?:string` | 可选：显式结束 |
| `file_ack` | `fileName`, `status:"ok"\|"error"`, `bytes:int`, `savedPath?:string`, `message?:string` | 服务端对**收到**的文件的回执 |
| `error` | `code:string`, `message:string` | 协议层错误（不影响连接时也会发） |
| `pong` | 无 | 心跳应答 |

### 2.3 未知字段与未知类型

- 接收方**必须忽略**未知字段。
- 接收方收到未知 `type` 时应忽略（可选：回 `error` 且 `code:"unknown_type"`，但不得关闭连接）。
- `dx`/`dy`/`amount`/`fileSize` 解析失败（非数字）时按 `0` 处理。

> **编号说明**：§2.4（通信测试 `diag_*`）已在本次改动中整体删除（服务端与手机端同时移除）。
> 编号刻意保留、不再复用，以免历史提交与代码注释里对「协议 §2.x」的引用失效。

### 2.5 传输层要求：`TCP_NODELAY` 必须两端都开

控制帧是 40 字节级的**小包**，每秒几十到几百个。这类流量对 Nagle 算法极其敏感：

- 发送方开着 Nagle（Linux/Android 默认开；**OkHttp 4.12 从不调用 `setTcpNoDelay`**）：有未确认
  数据时小包被攒住；
- 接收方（Windows）延迟 ACK 最多等 200ms 才回 ACK；
- 于是出现「平均间隔只有几毫秒、每隔一会儿冻结 200ms 级」的典型症状 —— 真机实测
  「平均间隔 6 ms、最大间隔 236 ms」就是这个特征。实体鼠标走 USB HID 中断传输，与 Nagle 无关，
  所以它一直看起来流畅，这个对比一度让人怀疑服务端。

因此：**服务端**（Fleck 已设置 `NoDelay = true`）与**客户端**（Android 端用自定义 `SocketFactory`
设置 `tcpNoDelay = true`）都必须开启。

### 2.6 键盘（`key`）

手机端的「键盘模式」把屏幕上的按键阵列变成电脑上的真实按键。协议层只有**一个**消息类型，
并且它表达的是**一次按键状态变化**，不是一次「组合键」：

```json
{"type":"key","vk":17,"down":true}     ← Ctrl 按下
{"type":"key","vk":67,"down":true}     ← C 按下
{"type":"key","vk":67,"down":false}    ← C 抬起
{"type":"key","vk":17,"down":false}    ← Ctrl 抬起
```

上面四条就是一次 `Ctrl+C`。**为什么不做成一条「组合键」消息**：

1. 长按（游戏里持续前进、按住 Ctrl 多选）必须能把「按下」和「抬起」分开两个时刻发出去，
   组合消息无法表达「C 抬起之后 Ctrl 还按着」这种状态；
2. 一次组合消息要求服务端自己维护「按顺序按下修饰键 → 按键 → 逆序松开」，等于把一份状态机
   复制到两端；而现在两端只需要对「一次状态变化」达成一致；
3. 手机端的**粘滞修饰键**（单击锁定一次组合 / 双击持续锁定）本质上就是「什么时候发修饰键的
   down、什么时候发 up」，天然属于客户端；服务端不必知道 Ctrl 为什么按着。

| 字段 | 说明 |
| --- | --- |
| `vk` | Win32 虚拟键码。`0`（缺省或非数字）表示「没有按键」，该帧被忽略；大于 `255` 被截断到 `255`。左右修饰键用区分码：`VK_LSHIFT(0xA0)` / `VK_RSHIFT(0xA1)` / `VK_LCONTROL(0xA2)` / `VK_RCONTROL(0xA3)` / `VK_LMENU(0xA4)` / `VK_RMENU(0xA5)` / `VK_LWIN(0x5B)` / `VK_RWIN(0x5C)` |
| `down` | `true` = KEY_DOWN，`false` = KEY_UP。接受 JSON 布尔、`"true"`/`"false"` 字符串与 `0`/`1`；**缺省按 `false`（抬起）处理** —— 抬起一个没按下的键是无害的空操作，而缺省「按下」会在电脑上留下一个永远不松开的按键，之后用户碰真实键盘的每一次输入都会变成快捷键 |
| `ext` | 可选。是否带 `KEYEVENTF_EXTENDEDKEY`。**缺省时由服务端按 `vk` 的标准扩展键集合判定**，客户端只在虚拟键码无法区分键位时才显式给出：小键盘回车与主回车共用 `VK_RETURN(0x0D)`，只有它需要写 `"ext":true` |

**扩展键（`KEYEVENTF_EXTENDEDKEY`）**：`↑↓←→`、`Insert/Delete/Home/End/PgUp/PgDn`、
`PrintScreen`、`NumLock`、小键盘 `/`、`右 Ctrl`、`右 Alt`、`Win`、`菜单键` 在 PS/2 协议里
是「`0xE0` 前缀 + 扫描码」。不设这个标志时 Windows 会把扫描码解析成**小键盘上的孪生键**：
NumLock 打开时「左箭头」会变成小键盘 `4`，「Delete」会变成小数点。服务端维护的标准集合：

```
0x21 0x22 0x23 0x24 0x25 0x26 0x27 0x28   PgUp PgDn End Home ← ↑ → ↓
0x2C 0x2D 0x2E                            PrintScreen Insert Delete
0x5B 0x5C 0x5D                            LWin RWin Menu
0x6F 0x90                                小键盘 / · NumLock
0xA3 0xA5                                右 Ctrl · 右 Alt
```

**卡键保护（服务端必须实现）**：手机端的粘滞键会把 `Ctrl/Shift/Alt/Win` **持续**按在电脑上
（双击锁定就是为了「一只手按 Ctrl 再点一串字母」）。如果连接在锁住的状态下断掉，电脑上就会留下
一个物理卡住的修饰键 —— 用户之后碰真实键盘时每一次输入都会变成快捷键。因此：

- 服务端必须记住**自己注入过**哪些键还按着，并在**会话结束时**（socket 关闭 / 被踢 / 看门狗超时）
  把它们全部抬起；只释放自己注入的键，绝不碰用户在实体键盘上按着的键；
- 抬起时**必须沿用按下时用过的 `ext` 标志**，否则松开的不是同一个键；
- 客户端在键盘不可见（切页 / 退出全屏 / 断开）时也应主动把锁定的修饰键抬起，两端都做才安全。

键盘帧与鼠标帧走同一条 WebSocket、同一套认证与连接状态管理，没有单独的心跳或通道。
服务端不做节流（与前缀一致：只夹取范围、不拒绝帧）；「按键重复」（长按连发）由手机端实现，
所以不需要依赖 Windows 的键盘重复延迟。

### 2.7 文本（`text`）

手机的「键盘模式」还需要能输入**中文、日文、emoji**。这些字符在 `key` 帧里根本表达不出来：
虚拟键码描述的是**键位**，不是**字符**，一个汉字要经过输入法、候选词、上屏才变成一个字符，
中间那串按键对电脑端毫无意义；emoji 与 `€`、`@` 这类只有 AltGr 才打得出的字符同样没有唯一的
键序列。所以另开一条消息，直接传字符本身：

```json
{"type":"text","s":"你好"}
```

| 字段 | 说明 |
| --- | --- |
| `s` | 要注入的文本。**必须存在且为 JSON 字符串**，缺 `s`、`s:null`、`s` 是数字或对象时整帧被忽略（不会退化成空串，也不会去猜） |

服务端把每个 UTF-16 码元变成一个 `KEYEVENTF_UNICODE` 的 `INPUT`：`wVk = 0`、
`wScan = 码元`、`dwFlags = KEYEVENTF_UNICODE(0x0004)`，每个码元先按下再抬起，整串在**一次**
`SendInput` 调用里发完（中途被别的注入插进来字符顺序就乱了）。这个标志让 Windows **绕开键盘
布局**，直接把字符交给前台窗口的 `WM_CHAR`，因此：

- 中文、日文、韩文不需要电脑上装任何输入法、也不需要切到某个布局；
- emoji 这类**补充平面字符**是两个 UTF-16 码元，会被拆成两个 `INPUT`——这不是「按了某个键」，
  只是 Windows 的 Unicode 注入单位本来就是码元；
- 一个空格是**真实字符**，会照常注入，不是「空白所以跳过」。

**截断规则（`MaxTextLength = 256`）**：按 UTF-16 码元截断到 256。如果第 256 个码元正好是**高代理
项**（emoji 的第一个码元），就再多丢一个、只保留 255 个：它的低代理项已经被切掉了，留下的是一个
无效的孤立高代理项，注入出去只会变成一个乱码方块。也就是说 emoji 要么完整，要么整个不出现，
绝不会被切成半个。

**没有按键状态，因此不参与卡键保护**：`text` 帧不表达「哪个键按着」，服务端不为它记账，
`ReleaseAll()`（§2.6）也不会为它松开任何东西——每个码元都是当场按下并立刻抬起，不存在「按住的
中间状态」。`key` 帧的卡键保护逻辑完全不受影响。

**兼容性**：这是**追加帧**，协议版本仍然是 `2`。按 §2.3，旧服务端读到未知 `type` 只记一条日志
然后忽略，连接照常存活；手机端在发之前也不需要协商能力，最坏情况就是「这次输入没发生」。

**已知限制**：只认扫描码的程序收不到这段文本。用 Raw Input / DirectInput 读键盘的游戏、部分
远程桌面与自动化工具是直接从设备栈拿扫描码的，而 `KEYEVENTF_UNICODE` 注入的是字符级的
`WM_CHAR`、背后没有对应的物理按键，这类程序会「什么都没收到」。普通编辑框、浏览器、聊天软件、
终端、Office 都能正常收到。

---

## 3. 文件传输

两个方向规则完全一致。

### 3.1 时序

```
发送方                                  接收方
  |-- 文本 file_header ------------------->|   开始写入 <下载目录>/<安全文件名>.part
  |-- 二进制 第 1 块 --------------------->|
  |-- 二进制 第 2 块 --------------------->|
  |             ...                       |
  |-- 二进制 第 N 块 --------------------->|   累计字节 == fileSize → 落盘为最终文件名
  |-- 文本 file_end (可选) --------------->|
  |<-- 文本 file_ack (status=ok|error) ----|
```

### 3.2 分块

- 分块大小 `chunkSize = 65536`（64 KiB）。最后一块可以更小。
- 二进制帧中**只有原始文件字节**，不带任何前缀/长度头。
- 一个方向同一时刻只允许一个进行中的传输。

### 3.3 结束与错误判定

- 接收方以「累计字节数 == `fileSize`」为正常结束条件；
- 收到 `file_end` 时若字节数不等，视为 `error` 并删除 `.part`；
- 连接中断 → 删除 `.part`（不留半成品）；
- 超过 `fileSize` 的多余字节 → 判定 `error`，终止本次传输（多余字节被丢弃，不写入磁盘）。

### 3.4 服务端接收侧安全约束（必须实现）

1. `fileName` 必须经过 `Path.GetFileName()` 清洗，再剔除 `..`、路径分隔符、控制字符、Windows 非法字符与结尾的 `.`/空格；结果为空时回退为 `file_<timestamp>.bin`；
2. 写入目录**固定**为配置的下载目录（默认 `%USERPROFILE%\Downloads`），写入前用 `Path.GetFullPath()` 校验最终路径确实位于该目录内（防目录穿越 / 符号链接逃逸）；
3. 单次文件大小上限默认 `512 MiB`（`maxFileSize`），超过则直接拒绝并回 `file_ack(status="error")`；
4. 先写 `<name>.part` 再原子重命名，重名时追加 `(1)`、`(2)`…；
5. 磁盘剩余空间不足时回 `error` 并清理 `.part`。

### 3.5 客户端接收侧

- 保存到系统「下载」目录（API ≥ 29 用 `MediaStore.Downloads`；API < 29 用直接文件写入 + `WRITE_EXTERNAL_STORAGE`），
  同样先写临时文件再提交，文件名做同等清洗；
- **必须告诉用户文件到底存到哪了**：`onCompleted` 回调返回的是可直接照着找的路径
  （`内部存储/Download/<文件名>`，重名时是 MediaStore 回读后的真实名字），以及一个
  可交给系统「打开方式」的内容 URI（API ≥ 29 用 MediaStore 的 `content://`；
  更低版本用 `FileProvider`）。界面据此显示路径并提供「打开」按钮。

---

## 4. 安全策略

| 策略 | 行为 |
| --- | --- |
| 传输加密 | 仅 `wss://`；证书为启动时生成并持久化的自签名证书（含本机所有局域网 IP 的 IP SAN）；仅允许 TLS 1.2 / 1.3 |
| 一次性 token | 每次启动/每次**配对成功**后重新生成 UUID；配对成功即销毁；重放旧 token 返回 `token_expired`（不计失败） |
| 服务器身份 `sid` | `base64url(SHA-256(证书公钥 SPKI)[0..16])`，22 字符，持久化在服务端；二维码携带它，客户端据此绑定证书固定值 |
| 设备身份 | 手机侧 ECC P-256 私钥存于 `AndroidKeyStore`（不可导出）；公钥（SPKI）+ `keyId` 存于电脑端信任列表 |
| 签名 | 每次连接签一次 `AirTouch-Auth-v1\n{sid}\n{nonce}\n{keyId}\n{ts}`（ECDSA P-256/SHA-256，ASN.1 DER）；无会话令牌、无长连接期内的重复签名 |
| 重放防护 | `nonce` 每个 socket 只用一次；`ts` 偏差超过 `±120s` 拒绝；旧 socket 的挑战不能用于新 socket |
| 免扫码重连 | 重连不带 token，只带 `keyId` + 签名；换 IP、换 Wi-Fi 都能连 |
| 单控制器独占 | 同一 `keyId` 的新连接替换旧连接（`1001 replaced`）；其他已信任设备在占用时得到 `busy`/`1013` |
| 防暴破 | 同一 IP 连续 5 次认证失败或 3 次 token 失败 → 封禁该 IP（UI 手动解锁或重启服务恢复） |
| 撤销 | 电脑端可单独移除某台设备的信任（`trust_revoked`）或清空信任列表；被撤销的设备下次连接得到 `key_unknown`，必须重新扫码 |
| 文件安全 | 见 §3.4 |
| 客户端证书校验 | 见 §1.2「证书校验规则」；**禁止**无条件 `TrustAll` |

> 实现备注（Windows 服务端）
>
> - 只有当证书私钥能落入真实密钥容器（`UserKeySet`）时才监听 `wss://`；若系统拒绝写入用户密钥容器，
>   启动会直接失败并给出可操作的提示，**不会**静默降级为明文。
> - 不提供明文模式：没有任何环境变量或界面开关能把监听器降级为 `ws://`。自检夹具在宿主机
>   无法提供 TLS 时，会在代码层面改用回环 `ws://` 继续验证协议逻辑，该开关不对外暴露。
> - 持久化文件都在 `%LOCALAPPDATA%\AirTouch`：`airtouch-server.pfx`（证书）、`airtouch-server.key`、
>   `airtouch-server.id`（`sid`）、`airtouch-devices.json`（信任列表，最多 16 台）。
> - Android 侧无需第三方密码库：`AndroidKeyStore` 的 `secp256r1` + `SHA256withECDSA` 即可满足全部需求
>   （注意 Java 默认输出 DER 签名，服务端按 `DSASignatureFormat.Rfc3279DerSequence` 验证）。

---

## 5. 兼容性备注

- 协议版本：`2`。`welcome.serverVersion` 为应用版本号，不是协议版本。
- v1 客户端连 v2 服务端：因为 v1 不认 `auth_challenge`，会被 `auth_timeout`（12s）断开；
  这是有意的，不提供 v1 兼容路径。
- 新增消息类型属于向后兼容变更：接收方忽略未知类型即可（§2.3）。
  键盘模式的 `key`（§2.6）就是这样加进来的：旧服务端收到它会走「忽略未知指令」分支，
  手机端的键盘区表现为「按下没反应」，但鼠标、触摸板、文件传输全部不受影响。
  同样地，双指捏合的 `touch` 帧与 `scroll` 的 `delta`/`axis` 也是纯新增：旧服务端收到 `touch` 会
  走「忽略未知指令」，收到带 `delta` 的 `scroll` 会按 `amount`（缺省 0）处理，其余功能不受影响。
  因此协议版本仍然是 `2`；如果要用键盘模式或双指缩放，服务端与手机端都要升级到带 §2.6 / §2.1
  「双指捏合」的版本。
- 缩放偏好与状态（`zoom_mode` / `zoom_probe` / `zoom_status`，§2.1）同样是纯新增：旧服务端收到
  `zoom_mode` / `zoom_probe` 走「忽略未知指令」，新服务端在没有它们的手机上照旧按启动时探测的结果
  工作；`welcome` 多出的 `zoomMode`/`zoomAvailable`/`zoomReason`/`zoomPreference` 对不认它们的客户端
  只是未知字段（§2.3 要求忽略）。协议版本仍是 `2`。
- 数值字段统一使用整数（除 `fileId`、`sid`、`keyId`、`fp`、`nonce`、`sign`、`pubKey` 等字符串字段）。
- 时间统一为 Unix 秒（UTC），仅在认证阶段使用，且允许 ±120s 偏差。
