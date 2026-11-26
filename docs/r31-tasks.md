# r31 / r32 / r33：UI 修复记录

## r33（已构建交付）

APK：`E:\wenjian\1\airtouch-2.0.3-r33.apk`，SHA256 `A589398B9274C7ECF0DF7C899F5FCB461FE08CB1056CC253F2D098BDC6810132`。

- **震动第三修（关键）**：`rememberAirTouchHaptics()` 原来把开关值当 `remember` key、构造时就定死 `enabled`；而键帽/物理键/滚轮都在 `pointerInput(Unit)`/`remember` 里**长期捕获**该实例，开关一动那些实例永远停在旧值 → 设置页的 `test()`（刻意不看开关）能振、所有按钮不震。现在实例只跟 `context` 绑定，开关值由 `SideEffect` 写进 `@Volatile enabled`，`available` 改为调用时实时算。
- 删掉 IME 行最右的 `⌫` 圆钮（用户不要）；输入框改回占满整行；「已发送: 」前缀从 `RedesignContract` **源头**去掉；输入框与「最近发送按键」改同款卡片（同圆角/底色/描边，只有高度不同，锁死 60–68dp）。
- `HandPointerIcon` 重画：不画大拇指，中指/无名指/小拇指蜷起且等高（原来一根比一根短、像张开的巴掌）。两处调用都是键盘页的「小触控板开关圆钮」。
- 设置页返回 → **回到来时的页面**（controller 记 `routeBeforeSettings`，新增 `"返回上一页"` 分支；`"返回主页"` 语义不变）。半屏键盘的小触控板显示状态与拖动位置改用 `rememberSaveable`，切页返回不再丢。

---

## r32（前一轮）

APK：`E:\wenjian\1\airtouch-2.0.3-r32.apk`，SHA256 `3493D46D01F82414845AF413B10DFAA40DC02E009B79214D3348D6E4D35FAFC7`。

- **震动第二修**：r31 用 `VibrationEffect.createPredefined(EFFECT_TICK/CLICK)` —— 它受系统「触摸反馈」开关管、很多 ROM 映射成极弱或无动作。改为 `createOneShot` + 显式振幅（tick 15ms/120、click 25ms/200），不再用预定义。设置页打开「线性马达震动反馈」时会放一次 **80ms 最大振幅自检**（不看开关状态），副标题会写明「本机没有振动马达」或提示去查系统振动开关。
- **半屏（竖屏）键盘页**：删掉装饰标签 `PURE FLAT MODE`；根背景 `bgBase` → `bgCanvas`（"深浅不一"的横带就是页底与按钮条底色不同造成），横屏键盘页同样改过来；截图小样写死颜色改成 token；小触控板**默认停最右**（右侧留白 = 键盘自己的 8dp），可拖动、越界夹回；IME 提示行最右加 `⌫` 键（直接发 `PressKey("Backspace")`，**绕开 `composition` 判断**，组词中也能删）；IME 输入框锁单行 32–40dp 永不伸长，小触控板移出 `weight(1f)` 可压缩区、上半区改为可滚动 → 输入法弹出挡不住触控板/按钮排。
- `HomeScreen.kt` 的 `BUILD_TAG` = `r32`。服务端无改动。

真机待验：小触控板最右的观感与 40dp 删除键是否协调；矮屏上键盘 Deck 会不会被切；震动自检那一下有没有感觉（据此判断是"没马达"还是"系统振动关了"）。

---

## r31（前一轮）

## 完成情况（r31 已构建交付）

APK：`E:\wenjian\1\airtouch-2.0.3-r31.apk`，SHA256 `751F5DB68495AF9677DD3C1BFE4417E385AE25C2A734F575D7E5233F566FBF96`（已放入自动推送目录）。
`HomeScreen.kt` 的 `BUILD_TAG` = `r31`。服务端无改动、无需重启。

| # | 任务 | 结果 |
|---|---|---|
| 1 | 首页删「已连接 · 服务端 x.y.z」行 | ✅ 连上时不再写 `statusText`（错误/断开仍会写，那一行按需出现） |
| 2 | 延迟 12ms 永远是 12 | ✅ 根因：`ConnectionPill(connected = …)` **没传 latencyMs**，一直吃形参默认值 12；RTT 本身是实时的（Ping/Pong → `state.latencyMs`）。已显式传参、默认值改 -1（显示 `-- ms`），半屏键盘页也补了同一行 |
| 3 | 按钮位置统一 + 补设置按钮 | ✅ 鼠标页加 `Spacer(weight(1f))` 把「灵敏度/返回/暂停/传文件」压到底部（原来浮在中间）；触控板页顶部「手势说明」位置换成**设置齿轮**（和鼠标页同一 `Click("打开设置")`）；半屏键盘页也补了胶囊 + 齿轮 |
| 4 | 触控板页删手势说明文字 | ✅ 删掉那行；拖拽模式提示仍留在触控板右下状态标签（有用） |
| 5 | 半屏键盘页加小触控板 | ✅ `MiniTouchpadBubble` 抽到新 `MiniTouchpad.kt`（`internal`），半屏页开关圆钮在「传文件」左边、面板在按钮排正上方（4:3 / 132×176dp，可拖动），**默认显示**（与全屏页 `touchpadVisible = true` 一致） |
| 6 | 系统输入法删不掉字 | ✅ 真因：`SystemImeInputBar.onValueChange` 只转发「末尾追加」，**删除从没发到电脑**。现在末尾删除按字符数补发 Backspace（上限 64），组词中不发、中间插字仍忽略。**不用加删除键** |
| 7 | 震动从来没管用 | ✅ 真因：只有 `performHapticFeedback(TextHandleMove)`（ROM 常映射为空 + 受系统「触摸反馈」开关管），而且 `FlatKeycap` **压根没接震动线**。新增 `Haptics.kt`（真 `VibrationEffect`，`VIBRATE` 权限本来就有），tick=10ms/60、click=20ms/120，无马达/异常静默降级，开关关闭即空操作 |

真机待验：小触控板在短屏上 132dp 是否压到按钮行；震动强度（60/120 与 10/20ms 是估的）；
`createPredefined` 被个别 ROM 拒绝时的 `createOneShot` 兜底路径。

---

> 这份文件是**上下文压缩**用：触控/拖拽那条线到此为止（结论见下），后面只按本清单做 UI。

## 触摸/拖拽线：已收尾（不要再改，除非用户新提）

- 交付：`E:\wenjian\1\airtouch-2.0.3-r30.apk`（r8–r30 都在该目录），手机跑 r30。
- 设置页有开关「**触摸零延时**」（`SettingsStore.touchZeroLatency`，key `touch_zero_latency`，默认 false）。
- 模式 A（关，默认）：第一下轻点**扣住 click 160ms**，窗内无动作就补发（普通单击）；
  窗内第二下落下：没怎么动就抬起 = 双击（连发两次 click）；按住 >120ms 或滑动 ≥4px = **吞掉第一下 click**，
  只在原点按一次左键进拖拽 → 拖拽起点 **0px 偏移**（Windows 看不到「点击在前、按下在后」）。
- 模式 B（开）：抬手立刻 click（零延时）；500ms 内第二下落下先不发左键，滑动到**电脑端光标累积位移 ≥3px**
  → 先发这 3px、紧接着按左键，不回位（起点约 3px 偏移）。
- **多指一律熔断**（`MultiTouch` 哨兵 / `event.changes.count { it.pressed } > 1`）：滚动、捏合全交回
  `detectTransformGestures`。r29 的"双指滑动没了"就是这个原因，r30 已修。
- 电脑端 `MouseController.Button(down)` 有兜底：上一次是左键单击 + 光标还在 `SM_CXDOUBLECLK`(4px) 内 +
  还在 `GetDoubleClickTime()` 内时，用 `MOUSEEVENTF_ABSOLUTE` 偏 8px 按下再精确回位。
- 服务端诊断日志（保留）：`位移合计 dx/dy`、`注入前系统认为左键…`；服务端日志
  `%LOCALAPPDATA%\AirTouch\airtouch.log`。**服务端闪避尚未在真机/真窗口验证过**（那次实验拿到失效句柄）。

## 构建 / 交付

- APK：`$env:JAVA_HOME=<repo>\.jdk\jdk-17*`、`GRADLE_USER_HOME=<repo>\.gradle-home`、
  `ANDROID_HOME`/`ANDROID_SDK_ROOT=<repo>\.android-sdk`、`ANDROID_USER_HOME=<repo>\.android-home`、
  `AIRTOUCH_DEBUG_KEYSTORE=<repo>\.android-home\.android\debug.keystore`，然后
  `<gradle 8.7>\bin\gradle.bat -p android assembleDebug --console=plain`。
- 交付：拷到 `E:\wenjian\1\airtouch-2.0.3-r<N>.apk` + 报 SHA256，再拷进
  `%LOCALAPPDATA%\AirTouch\push`（电脑端 2 秒轮询自动推给手机）。
- 服务端：先 `Stop-Process -Name AirTouch.Server -Force`，再 `pwsh -NoProfile -File tools\dotnet.ps1 build server\AirTouch.sln -v q`，再 `Start-Process`。
- 每轮在 `HomeScreen.kt` 的 `BUILD_TAG` 上加一行说明（当前 `r30`）。

## 本轮任务（r31）

1. **首页状态**：删掉「已连接服务端 2.0.2」这一行。
2. **延迟显示**：鼠标页/触控板页的「已连接 12ms」——
   - 确认这个 12 是不是实时值（用户反映一直是 12）；若写死/未更新，改成真实往返延迟（Ping/Pong 已有）；
   - **半屏键盘页也加上这一行**。
3. **按钮布局统一**：鼠标页与触控板页的「灵敏度调节」「返回 / 暂停 / 传文件」「设置」位置要对齐
   （触控板页与键盘页现在**没有设置按钮**，要补）。以鼠标页为基准。
4. **触控板页**：删掉「轻点左键 · 双击左键双击 · 双击按住拖拽 · 双指捏合缩放」这行说明文字。
5. **半屏（竖屏）键盘页**：加一个小触控板，
   - 按钮位置：在「传文件」按钮**左边**；小触控板在**这两个按钮的上方**；
   - UI 与全屏（横屏）键盘页的小触控板一致（复用 `MiniTouchpadBubble`）。
6. **系统输入法删除**：手机系统输入法下删不掉文字。先查能否修；不行就在「点这里调出系统输入法…」最右边加一个删除键。
7. **震动马达从来没管用过**：查明 `hapticEnabled` / `prefs.haptic` 到底有没有接到振动器（Vibrator / HapticFeedback），修到真能震。
