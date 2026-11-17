# AirTouch 通信协议 (v2)

本文件是 Windows 服务端与 Android 客户端的**唯一事实来源**。两端实现如有冲突，以本文件为准。

- 传输层：`wss://`（TLS 1.2+，服务端自签名证书）
- 通信模型：全双工 WebSocket，局域网直连（手机与电脑互访，不依赖任何云服务、无 NAT 穿透）
- 文本帧：UTF-8 JSON 控制指令
- 二进制帧：文件分块数据，**必须**紧跟在同方向的 `file_header` 文本帧之后
- 协议版本：`2`

v1 → v2 的三处结构性变化，先看这段就能理解全篇：

1. **认证代替「token 即身份」**：`token` 只在首次配对时用一次，用完即毁；之后手机用自己的
   ECC 私钥签名证明身份（§1.2）。
2. **不再绑定来源 IP**：身份是「设备公钥」，不是「IP」。换 Wi-Fi、路由器重新分配 IP 都能直接重连，
   同时也只允许一台设备（§1.2、§4）。
3. **新增 `moveto`（绝对定位）与 `diag_*`（通信测试）**：前者用于绕开 Windows 指针加速、
   得到可复现的轨迹；后者用于把「卡顿到底卡在哪一层」量化（§2.4、§5）。

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
 "deviceName":"Xiaomi 14","appVersion":"2.0.0"}
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
{"type":"welcome","protocolVersion":2,"serverVersion":"2.0.0","serverIp":"192.168.1.7","port":8443,
 "sid":"…","maxFileSize":536870912,"chunkSize":65536,"screenWidth":2560,"screenHeight":1440,
 "displayHz":180,"deviceName":"Xiaomi 14","keyId":"…","authMode":"resume","trustedCount":1}
```

| 字段 | 说明 |
| --- | --- |
| `protocolVersion` | 协议版本，当前 `2` |
| `serverVersion` | 应用版本号（不是协议版本） |
| `serverIp` | 服务端认为自己所在的本机地址（仅供参考，客户端应以实际连接地址为准） |
| `sid` | 服务器身份，客户端应持久化用于后续重连 |
| `maxFileSize` / `chunkSize` | 单文件上限与分块大小（§3） |
| `screenWidth` / `screenHeight` | 主屏像素尺寸（`moveto` 与画圆测试用得到；`0` 表示未知） |
| `displayHz` | 主显示器刷新率（**v2 追加，可选**；`0` 表示查不到）。这是感知流畅度的上限：发送频率超过它不会有额外收益 |
| `deviceName` / `keyId` | 服务端记下的本设备信息 |
| `authMode` | `pair` 或 `resume`，客户端据此提示「首次配对 / 免扫码重连」 |
| `trustedCount` | 服务端当前信任的设备数量 |

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
| `move` | `dx:int`, `dy:int`, `seq?:int` | 鼠标相对移动（像素，右/下为正）。`dx`/`dy` 被截断到 `[-4096, 4096]`；`seq` 仅用于通信测试统计 |
| `moveto` | `x:int`, `y:int`, `seq?:int` | **绝对定位**到主屏坐标（`SetCursorPos`，左上为原点）。截断到 `[-32767, 32767]`，实际落点再夹到主屏范围内。绕开 Windows 指针加速，轨迹可与手机端算出的一致 |
| `click` | `button:"left"\|"right"` | 单击；`button` 缺省视为 `left` |
| `scroll` | `amount:int` | 滚轮；正数上滚，负数下滚。截断到 `[-120, 120]` |
| `reset` | 无 | 光标复位到主屏中心 |
| `auth_req` | 见 §1.2 | 认证应答 |
| `diag_begin` | `testKind:string`, `mode:string`, `planned:int`, `targetHz:int`, `circleMs:int` | 通信测试开始（§2.4） |
| `diag_report` | 见 §2.4 | 客户端侧的测试统计 |
| `file_header` | `fileName:string`, `fileSize:int`, `fileId?:string` | 文件传输开始（见 §3） |
| `file_end` | `fileId?:string` | 可选：显式声明本次传输结束（正常实现靠字节数判定） |
| `hello` | `deviceName?:string`, `appVersion?:string` | 可选：认证通过后上报设备信息，供服务端 UI 显示 |
| `ping` | 无 | 可选心跳 |

示例：

```json
{"type":"move","dx":10,"dy":-5}
{"type":"moveto","x":540,"y":960,"seq":17}
{"type":"click","button":"left"}
{"type":"scroll","amount":3}
{"type":"reset"}
{"type":"file_header","fileName":"test.txt","fileSize":1024}
```

### 2.2 服务端 → 客户端

| type | 载荷 | 说明 |
| --- | --- | --- |
| `auth_challenge` | 见 §1.2 | 连接建立后的第一帧 |
| `welcome` | 见 §1.2 | 认证通过后的第一帧 |
| `diag_report` | 见 §2.4 | 服务端侧的测试统计（仅在测试期间） |
| `file_header` | `fileName:string`, `fileSize:int`, `fileId?:string` | 服务端向手机发文件（见 §3） |
| `file_end` | `fileId?:string` | 可选：显式结束 |
| `file_ack` | `fileName`, `status:"ok"\|"error"`, `bytes:int`, `savedPath?:string`, `message?:string` | 服务端对**收到**的文件的回执 |
| `error` | `code:string`, `message:string` | 协议层错误（不影响连接时也会发） |
| `pong` | 无 | 心跳应答 |

### 2.3 未知字段与未知类型

- 接收方**必须忽略**未知字段。
- 接收方收到未知 `type` 时应忽略（可选：回 `error` 且 `code:"unknown_type"`，但不得关闭连接）。
- `dx`/`dy`/`amount`/`fileSize` 解析失败（非数字）时按 `0` 处理。

### 2.4 通信测试（`diag_*`）

用途：手机「在动」而电脑光标「不动」时，把原因定位到三层中的一层 —— **手机没发 / 网络丢帧 /
Windows 拒绝输入注入**。做法是让手机按固定频率发已知轨迹，每帧带递增 `seq`，PC 端统计到达情况。

时序：

```
客户端                                      服务端
  |-- diag_begin {testKind,mode,planned,targetHz,circleMs} -->|  开始按 seq 记账、记录起始光标位置
  |-- move/moveto {…,seq=1} ------------------------------->|
  |-- move/moveto {…,seq=2} ------------------------------->|
  |                        …                                |
  |-- move/moveto {…,seq=N} ------------------------------->|
  |-- diag_report {…客户端自测数据…} ---------------------->|  计算 lost/duplicated/outOfOrder/gap
  |<-- diag_report {…服务端统计…} --------------------------|  两端数据合并成结论
```

`diag_report` 字段（两个方向同名同义，客户端上报时省略服务端才有的字段）：

| 字段 | 方向 | 说明 |
| --- | --- | --- |
| `testKind` | 双向 | 目前只有 `circle` |
| `mode` | 双向 | `abs`（`moveto`）或 `rel`（`move`） |
| `planned` | 双向 | 计划发送的帧数 |
| `sent` | 手机 → PC | 手机实际发出的帧数 |
| `received` | PC → 手机 | PC 收到的帧数 |
| `firstSeq` / `lastSeq` | 双向 | 首末序号（用于判断是否中途重启） |
| `elapsedMs` | 手机 → PC | 手机自报耗时 |
| `durationMs` | PC → 手机 | PC 测得的持续时间 |
| `targetHz` / `circleMs` | 双向 | 目标频率、每圈耗时 |
| `maxSendGapMs` | 手机 → PC | 手机侧两次发送之间的最大间隔（手机自己卡没卡） |
| `maxGapMs` / `meanGapMs` | PC → 手机 | 到达间隔（网络抖不抖） |
| `lost` / `duplicated` / `outOfOrder` | PC → 手机 | 按 `seq` 算出的丢帧、重复、乱序 |
| `sendInputFailures` | PC → 手机 | `SendInput` 失败累计次数（Windows 拒绝注入） |
| `startX` / `startY` / `endX` / `endY` | PC → 手机 | 测试首末光标位置（用于核对轨迹是否被指针加速改写） |
| `screenWidth` / `screenHeight` | PC → 手机 | PC 主屏尺寸 |
| `cursorUpdates` / `cursorPollHz` / `cursorMaxGapMs` | PC → 手机 | **v2 追加，可选**：PC 侧用 `GetCursorPos` 高频轮询测出的「光标位置真正变化了几次」、轮询频率、两次变化之间的最大间隔 |
| `displayHz` | PC → 手机 | **v2 追加，可选**：PC 主显示器刷新率（`0` 表示查不到） |

结论（两端算法一致，也写在两端 UI 上）：

| 现象 | 结论 |
| --- | --- |
| 手机 max send gap 大 | 手机端卡顿（UI/线程被阻塞，帧根本没发出去） |
| 到达 gap 大、丢了帧、但手机侧 gap 正常 | 网络/Wi-Fi 链路问题 |
| 帧全到、gap 正常，但 `sendInputFailures` 增长或光标未动 | Windows 输入注入被拒绝（权限/UAC/游戏反作弊） |
| 帧全到、gap 正常，但 `cursorUpdates / duration` 明显低于 `targetHz` | 「每次位移不足 1 物理像素」——光标只能整像素跳，慢速移动时更新次数必然低于发送次数（把目标频率降到屏幕刷新率附近或调高灵敏度） |

> `cursorUpdates` 是三个「帧率」里唯一来自**可见侧**的数字：
> `sent` 是手机发的、`received` 是 PC 收到的、`cursorUpdates` 才是屏幕上真正动了几次的。
> 三者都存在时，「是服务端出问题了吗」这个问题就不需要猜了。

### 2.5 传输层要求：`TCP_NODELAY` 必须两端都开

控制帧是 40 字节级的**小包**，每秒几十到几百个。这类流量对 Nagle 算法极其敏感：

- 发送方开着 Nagle（Linux/Android 默认开；**OkHttp 4.12 从不调用 `setTcpNoDelay`**）：有未确认
  数据时小包被攒住；
- 接收方（Windows）延迟 ACK 最多等 200ms 才回 ACK；
- 于是出现「平均间隔只有几毫秒、每隔一会儿冻结 200ms 级」的典型症状 —— 真机实测
  `maxGapMs = 236` / `meanGapMs = 6` 就是这个特征。实体鼠标走 USB HID 中断传输，与 Nagle 无关，
  所以它一直看起来流畅，这个对比一度让人怀疑服务端。

因此：**服务端**（Fleck 已设置 `NoDelay = true`）与**客户端**（Android 端用自定义 `SocketFactory`
设置 `tcpNoDelay = true`）都必须开启。诊断时用上表把「手机自报发送间隔」与「PC 到达间隔」
并列比较即可定位责任方。

画圆轨迹（两端必须一致，方便肉眼核对）：

- 半径 `r = screenHeight / 2`；
- 圆 1 圆心 `(r, screenHeight/2)` → 同时与**左、上、下**三条边相切；
- 圆 2 圆心 `(screenWidth - r, screenHeight/2)` → 同时与**右、上、下**三条边相切；
- 采样点数 `samples = round(circleMs/1000 * targetHz)`，共 `planned = 2 × samples` 帧；
- 使用绝对时间点调度（`postAtTime`），误差不累积。

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
- 数值字段统一使用整数（除 `fileId`、`sid`、`keyId`、`fp`、`nonce`、`sign`、`pubKey` 等字符串字段）。
- 时间统一为 Unix 秒（UTC），仅在认证阶段使用，且允许 ±120s 偏差。
