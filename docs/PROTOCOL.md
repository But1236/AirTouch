# AirTouch 通信协议 (v1)

本文件是 Windows 服务端与 Android 客户端的**唯一事实来源**。两端实现如有冲突，以本文件为准。

- 传输层：`wss://`（TLS 1.2+，服务端自签名证书）
- 通信模型：全双工 WebSocket，局域网直连（校园网内手机与电脑互访，无 NAT 穿透）
- 文本帧：UTF-8 JSON 控制指令
- 二进制帧：文件分块数据，**必须**紧跟在同方向的 `file_header` 文本帧之后

---

## 1. 连接与鉴权

### 1.1 地址格式

```
wss://{IP}:{PORT}/ws?token={TOKEN}
```

| 字段 | 说明 |
| --- | --- |
| `IP` | 服务端所在电脑的局域网 IPv4 地址（由服务端 UI / 二维码给出） |
| `PORT` | 默认 `8443`，可在服务端 UI 修改 |
| `TOKEN` | 服务端启动时生成的随机 UUID v4（小写，含连字符） |

该 URL 即二维码内容。客户端扫码后**原样**使用（不得改写 host/port/path/query）。

### 1.2 握手结果

服务端在 WebSocket 握手完成后（`OnOpen`）立即执行校验；校验失败时按顺序：
1. 若链路仍可用，发送一帧文本 `{"type":"error","code":"<CODE>","message":"..."}`；
2. 调用 `Close(code)` 关闭连接。

`code` 取值（关闭码使用 WebSocket 标准码）：

| code | 关闭码 | 含义 |
| --- | --- | --- |
| `bad_path` | 1008 | 路径不是 `/ws` |
| `banned` | 1008 | 该 IP 因连续 3 次 token 校验失败已被封禁 |
| `rate_limited` | 1008 | 同一 IP 10 秒内连接尝试超过 20 次 |
| `ip_locked` | 1008 | 服务端已绑定其他 IP，拒绝新 IP 接入 |
| `busy` | 1013 | 已有一条来自绑定 IP 的活动连接（同一 IP 同时只允许一条连接） |
| `token_invalid` | 1008 | token 缺失或不匹配（计入该 IP 的失败计数） |
| `token_expired` | 1008 | 重放了已销毁的 token（**不**计入失败计数，避免正常重连被误封） |

校验顺序固定为：`路径 → 封禁 → 速率限制 → IP 绑定 → 单连接 → token`。
前一步不通过时不会执行后续检查，因此被绑定时一律得到 `ip_locked`，即使 token 也不对。

校验通过后必须按以下顺序处理：
1. 立即**销毁 token**（一次性）；
2. 若尚未绑定 IP，将本连接来源 IP 绑定为唯一允许 IP；
3. 回复 `welcome`。

客户端断线后服务端**重新生成新 token 并刷新二维码**；已绑定的 IP 保持不变，因此同一台手机可用新 token 重连，其他 IP 依旧被拒。

### 1.3 心跳

两端都需要维持连接存活，规则如下：

- 服务端每 `30s` 发送一个 **WebSocket Ping 帧**（RFC 6455 控制帧）。
- 客户端必须回 **Pong 帧**。OkHttp 会自动回 Pong，业务代码无需处理；Android 端也不应关闭这一行为。
- 客户端若使用 OkHttp 的 `pingInterval`，服务端同样会回 Pong（服务端已实现 `OnPing → SendPong`），
  因此客户端不会因「ping 超时」而误断线。
- 服务端 `90s` 未收到任何帧（控制帧或数据帧）即判定链路失效并主动关闭。
- 任一方也可发送文本帧 `{"type":"ping"}`，对端**应**回 `{"type":"pong"}`（兼容不支持控制帧的场景）。

---

## 2. 消息定义

### 2.1 客户端 → 服务端（控制指令）

| type | 载荷 | 说明 |
| --- | --- | --- |
| `move` | `dx:int`, `dy:int` | 鼠标相对移动（像素，右/下为正）。`dx`/`dy` 会被服务端截断到 `[-4096, 4096]` |
| `click` | `button:"left"\|"right"` | 单击；`button` 缺省视为 `left` |
| `scroll` | `amount:int` | 滚轮；正数上滚，负数下滚。截断到 `[-120, 120]` |
| `reset` | 无 | 光标复位到主屏中心 |
| `file_header` | `fileName:string`, `fileSize:int` | 文件传输开始（见 §3） |
| `file_end` | `fileId?:string` | 可选：显式声明本次传输结束（正常实现靠字节数判定） |
| `hello` | `deviceName?:string`, `appVersion?:string` | 可选：连接后上报设备信息，供服务端 UI 显示 |
| `ping` | 无 | 可选心跳 |

示例：

```json
{"type":"move","dx":10,"dy":-5}
{"type":"click","button":"left"}
{"type":"scroll","amount":3}
{"type":"reset"}
{"type":"file_header","fileName":"test.txt","fileSize":1024}
```

### 2.2 服务端 → 客户端

| type | 载荷 | 说明 |
| --- | --- | --- |
| `welcome` | `serverVersion`, `serverIp`, `port`, `boundIp`, `maxFileSize`, `chunkSize` | 鉴权通过后的第一帧 |
| `file_header` | `fileName:string`, `fileSize:int`, `fileId?:string` | 服务端向手机发文件（见 §3） |
| `file_end` | `fileId?:string` | 可选：显式结束 |
| `file_ack` | `fileName`, `status:"ok"\|"error"`, `bytes:int`, `savedPath?:string`, `message?:string` | 服务端对**收到**的文件的回执 |
| `error` | `code:string`, `message:string` | 协议层错误（不影响连接时也会发） |
| `pong` | 无 | 心跳应答 |

### 2.3 未知字段与未知类型

- 接收方**必须忽略**未知字段。
- 接收方收到未知 `type` 时应忽略（可选：回 `error` 且 `code:"unknown_type"`，但不得关闭连接）。
- `dx`/`dy`/`amount`/`fileSize` 解析失败（非数字）时按 `0` 处理。

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

- 保存到系统「下载」目录（API ≥ 29 用 `MediaStore.Downloads`；API < 29 用直接文件写入 + `WRITE_EXTERNAL_STORAGE`）；
- 同样先写临时文件再提交，文件名做同等清洗。

---

## 4. 安全策略（服务端强制）

| 策略 | 行为 |
| --- | --- |
| 传输加密 | 仅 `wss://`；证书为启动时生成并持久化的自签名证书（含本机所有局域网 IP 的 IP SAN）；仅允许 TLS 1.2 / 1.3 |
| 一次性 token | 每次启动/每次断开后重新生成 UUID；鉴权成功即销毁；重放旧 token 返回 `token_expired` |
| IP 绑定 | 首个鉴权成功的 IP 被锁定；之后其他来源 IP 一律 `ip_locked` |
| 防暴破 | 同一 IP 连续 3 次 token 校验失败 → 永久封禁该 IP（重启服务或 UI 手动解锁才恢复） |
| 单连接 | 绑定 IP 同时只允许 1 条活动连接，多余连接 `busy` |
| 文件安全 | 见 §3.4 |
| 客户端证书校验 | Android 端必须做证书指纹固定（TOFU）：首次连接记录服务端证书 SHA-256，之后指纹不一致立即中断连接并提示重新配对；**禁止**无条件 `TrustAll` |

> 实现备注（Windows 服务端）
>
> - 只有当证书私钥能落入真实密钥容器（`UserKeySet`）时才监听 `wss://`；若系统拒绝写入用户密钥容器，
>   启动会直接失败并给出可操作的提示，**不会**静默降级为明文。
> - 仅本机排障可用 `AIRTOUCH_INSECURE_NO_TLS=1` 启动未加密的 `ws://`（日志会打出醒目警告）；
>   该模式下消息格式完全相同，但**不得**在局域网使用。

---

## 5. 兼容性备注

- 协议版本：`1`。`welcome.serverVersion` 为应用版本号，不是协议版本。
- 新增消息类型属于向后兼容变更：旧客户端忽略未知类型即可。
- 数值字段统一使用整数（除 `fileId` 等字符串字段）。
