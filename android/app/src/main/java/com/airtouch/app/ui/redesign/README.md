# AirTouch Android UI 重构 · 当前交付（唯一 UI，已接真实接口）

> 依据：`docs/airtouch_android_ui_blueprint.html` + 《AirTouch 按钮与间距统一设计规范》。
> **旧版 View/XML 界面（PairingActivity / ControlActivity / 三个 Fragment / 自绘 View / 全部 layout）
> 已整体删除**，全 App 只有这一套 Jetpack Compose UI。
> 页面只发 `RedesignAction`，副作用集中在 `integration/RedesignController`，
> 复用现有 `AirTouchClient` / `AirMouseEngine` / `KeyboardSession` / `FileSender` / `SettingsStore`，
> 不改动任何网络 / 加密 / 传感器底层逻辑。

---

## 1. 文件清单

```
ui/redesign/
├── theme/
│   ├── AirTouchColors.kt     CSS Variables 1:1 转写：浅/深两套色值 + 4 套强调色
│   ├── AirTouchTheme.kt      AirTouchTheme / CompositionLocal / 几何 Token / 只读访问器
│   └── RedesignContract.kt   6 条路由 + RedesignUiState + RedesignAction + 预览用 reducer
├── TactileComponents.kt      ★ 新拟物基础组件库（drawBehind / drawWithContent + Brush + BlurMaskFilter）
├── FlatComponents.kt         ★ 纯扁平键盘组件库（FlatKeycap / 粘滞态 / LinearSwipeJoystick / FixedGrid）
├── camera/QrFrameDecoder.kt   纯 JVM 解码核心（Y 平面重排 + zxing，含反相重试；可离线自测）
├── camera/QrCodeAnalyzer.kt   CameraX 分析器：**就地**解码、close() 放最后以保住 KEEP_ONLY_LATEST 背压
├── RedesignIcons.kt          蓝图 SVG 线性图标的 Canvas 手绘转写（零额外依赖）
├── CommonUi.kt               连接胶囊 / 灵敏度块 / 底部控制坞 / 点阵触控板
├── HomeScreen.kt             1. 连接与主页（内嵌 1:1 真实扫码） Route "home"
├── SettingsScreen.kt         2. 设置                Route "settings"
├── AirMouseScreen.kt         3. 鼠标模式（圆柱拟态滚轮） Route "air_mouse"
├── TouchpadScreen.kt         4. 触控板模式          Route "touchpad"
├── FlatKeyboardScreen.kt     5. 竖屏键盘（扁平）    Route "keyboard_portrait"
├── LandscapeKeyboardScreen.kt 6. 全屏横屏键盘       Route "keyboard_landscape"
├── RedesignScreenHost.kt     页面路由 + **三个模式页共享同一个带弹簧过渡动画的滑块**
├── RedesignActivity.kt       唯一 Activity / 唯一 LAUNCHER + 手动连接 / 文本输入对话框
├── integration/
│   ├── RedesignPrefs.kt      外观偏好（独立 SharedPreferences）
│   └── RedesignController.kt Action → 网络 / 传感器 / 键盘 / 文件 / 设置的唯一适配层
├── RedesignPreviews.kt       深色 / 4 强调色 / 左手 / 9键 / 悬浮微触控板 变体 @Preview
└── README.md                 本文件
```

`@Preview` 分布在 6 个页面文件（浅色基准）与 `RedesignPreviews.kt`（状态变体）中，
Android Studio 里打开文件即可预览；开启 **Interactive Mode** 可点击开关 / 色板 / 粘滞键。

---

## 2. 色彩 Token 映射（严格复刻，勿凭感觉改）

| 蓝图 CSS Variable | Kotlin | 浅色 | 深色 |
| --- | --- | --- | --- |
| `--bg-canvas` | `colors.bgCanvas` | `#E2E7EE` | `#121418` |
| `--bg-base` | `colors.bgBase` | `#EDF1F5` | `#1C1F24` |
| `--bg-surface-top` | `colors.bgSurfaceTop` | `#F6F8FB` | `#23272E` |
| `--bg-surface-bottom` | `colors.bgSurfaceBottom` | `#E4E9F0` | `#181B20` |
| `--bg-well` | `colors.bgWell` | `#E4E9F0` | `#15181C` |
| `--shadow-light` | `colors.shadowLight` | `#FFFFFF` | `#272B33` |
| `--shadow-dark` | `colors.shadowDark` | `#C8D1DC` | `#0E1013` |
| `--border-subtle` | `colors.borderSubtle` | `rgba(255,255,255,.78)` | `rgba(255,255,255,.07)` |
| `--border-contrast` | `colors.borderContrast` | `rgba(15,23,42,.08)` | `rgba(255,255,255,.09)` |
| `--text-primary / secondary / muted` | `colors.text*` | `#181C24 / #5B6678 / #8C97A8` | `#F1F5F9 / #9CA6B8 / #646E80` |
| `--dot-color` | `colors.dotColor` | `rgba(24,28,36,.24)` | `rgba(241,245,249,.22)` |
| `--kb-bg / --kb-key-bg` | `colors.kbBg / kbKeyBg` | `#E3E8EF / #F6F8FB` | `#14161A / #252A32` |
| `--kb-key-fn` | `colors.kbKeyFn` | `#D9E0EA` | `#1D2128` |
| `--kb-key-border` | `colors.kbKeyBorder` | `rgba(15,23,42,.09)` | `rgba(255,255,255,.07)` |
| `--kb-key-pressed` | `colors.kbKeyPressed` | `#CFD8E3` | `#323945` |

**4 套强调色**（`AccentPreset`，默认 `YELLOW` = `#F5B800`）：

| preset | accent | light | dark | accentText |
| --- | --- | --- | --- | --- |
| `YELLOW` | `#F5B800` | `#FFD447` | `#D19A00` | `#14171D` |
| `CORAL` | `#F25C3B` | `#FF7E61` | `#CC4123` | `#FFFFFF` |
| `EMERALD` | `#10B981` | `#34D399` | `#059669` | `#FFFFFF` |
| `BLUE` | `#3B82F6` | `#60A5FA` | `#2563EB` | `#FFFFFF` |

`accentGlow` = `accent.copy(alpha = 0.38f)`（蓝图 `rgba(..., 0.38)`）。

几何（质感升级后）：`radiusPhone 38 / radiusCard 26 / radiusBtn 20 / radiusKey 6 / radiusPill 999`，
`tactileEdge 9 / segmentedHeight 52 / sliderTrackHeight 24`（dp）。

---

## 3. 组件库映射与「混合风格隔离」

### 3.1 新拟物（`TactileComponents.kt`）—— 仅主页 / 设置 / 鼠标 / 触控板 / 开关 / 滑块

| 蓝图 | Compose | 实现要点（质感升级后） |
| --- | --- | --- |
| `.neu-well` | `NeumorphicWell` | `drawBehind` + `bgWell` 底 + 加强的 `BlurMaskFilter` inset 内阴影 + 顶部极淡内高光；**无灰色描边** |
| `.neu-btn` | `TactileSurfaceButton` | `drawWithContent` + `Brush.linearGradient(145°)` 微弱色差 + 左上→右下曲面高光 + 加大模糊半径/偏移的亮部高光与暗部投影；**整圈灰色 border 已删除**，只留顶部一道极淡高光；默认 padding 加大到 18/14dp；按下 `animateFloatAsState` 凸转凹 + `scale(0.975)` + HapticFeedback |
| `.neu-btn-accent` | 同上 `accentLit = true` | `accentLight → accentDark` 渐变 + `accentGlow` 光晕 |
| `.neu-active-ring` | 同上 `activeRing = true` | 柔和 `accentGlow` 光晕（不再是硬朗彩色描边） |
| `.neu-btn cursor-default` | `TactileSurface` | 只画面，不带点击/震动（设置页外壳） |
| `.tactile-switch` | `TactileToggleSwitch` | 放大到 72×40dp，32dp 曲面滑块 + 两条防滑纹路；`animateFloat` 位移 32dp |
| `.tactile-range` | `TactileSlider` | **第 3 项**：纯内凹槽（24dp 高）+ 强调色立体圆角圆柱填充（竖直渐变 + 顶部高光 + 底部暗边 + glow）；**圆球 Thumb 已删除**；槽内点击/横向拖动 |
| 分段控件 | `TactileSegmentedControl` | **第 4 项**：52dp 一体式内凹长胶囊背板，3 段文字直接印在背板上无间隙；强调色立体滑块用 `animateDpAsState(spring)` 平滑滑动 |
| `.dot-matrix-bg` | `Modifier.dotMatrixBackground()` | 16dp 网格 / 1.45dp 点 / 8dp 偏移 |
| `.scroll-wheel-ribs` | `Modifier.ribbedScrollWheel()` / `ribbedScrollWheel(State<Float>)` | 每 6dp「2dp 亮纹 + 4dp 暗纹」；带 State 的重载让刻度纹路跟随滚轮手势实时滚动且只重绘不重组 |

> `BlurMaskFilter` 在软件渲染（含 Layoutlib `@Preview`）下表现最完整；真机若 View 被硬件加速，
> HWUI 可能丢弃模糊，第二步接入宿主 View 时可用 `setLayerType(LAYER_TYPE_SOFTWARE, null)` 兜底。

### 3.2 纯扁平（`FlatComponents.kt`）—— 仅竖屏键盘 / 横屏全屏键盘

- `FlatKeycap`：**零阴影、零渐变**、`6dp` 圆角、1dp `kbKeyBorder`；按下 `kbKeyPressed` + `scale(0.95)`。
- `Ctrl / Shift / Alt / Win / Caps`：`modifierName` 非空时单击触发 `ToggleSticky`，
  锁定态整块填**全局强调色** + `accentText` + 透明描边（蓝图 `.sticky-locked`）。
- `FlatKeyRow` / `FixedGrid`：分别复刻 `grid-cols-N` 与 `row-span-2 / col-span-2`。
- `LinearSwipeJoystick`（**第 5 项，取代原 `CrossJoystick`**）：一字形胶囊滑轨 + 中心可拖动滑块；
  左右拖过阈值触发 ← / →（连续步进）、上下拨动触发 ↑ / ↓；`HapticFeedbackConstants.CLOCK_TICK`
  轻震动；松手 `Animatable + spring` 物理回弹归位。
- `FlatSegmentedControl`：第 4 项的扁平对应版 —— 一体式胶囊背板 + 纯色强调滑块
  `animateDpAsState(spring)` 滑动，零阴影零渐变，**不复用** `TactileSegmentedControl`。

> 键盘页文件只 import `FlatComponents` / `RedesignIcons` / `theme` / `CommonUi(DotMatrixTouchpad)`，
> 不引用任何拟物按键；横屏页唯一的拟物元素是上方功能条里的三个圆钮与微触控板气泡
> （蓝图明确要求「扁平打字 + 拟物微调光标」）。

---

## 4. 动态主题与交互细节

| 蓝图交互 | 第一步实现 |
| --- | --- |
| 浅 / 深两套色值 | `AirTouchTheme(darkTheme)` → `lightAirTouchColors` / `darkAirTouchColors` |
| 4 套强调色 | `AirTouchTheme(accent)`；设置页 4 色板发 `SetAccent` |
| 设置驱动全局重组 | `CompositionLocalProvider`（`LocalAirTouchColors` 等）+ `RedesignUiState.reduce`；第二步换 DataStore Flow |
| 鼠标左手水平镜像 | `AirMouseScreen` 按键簇 `graphicsLayer { scaleX = -1f }`，文字用 `.unmirror()` 二次翻转保持可读 |
| 滚轮垂直拖动 | **第 2 项**：`detectVerticalDragGestures` + 刻度纹路实时滚动 + `CLOCK_TICK` 连续机械震动 + `onScroll(delta)` → `RedesignAction.MouseScroll` |
| 复位键居中下置 | **第 2 项**：从右下角移到滚轮凹槽正下方 `Alignment.BottomCenter`，对称便于拇指盲操 |
| 触控板物理键在点阵区正下方 | `TouchpadScreen` 的 `左键 / 滚轮 / 右键` Row 紧贴 `DotMatrixTouchpad` 下方（高度加大到 56dp） |
| 竖屏 9 键(5×4) / 全键盘切换 | `FlatKeyboardScreen` 的 `state.t9Mode` + `T9KeyboardDeck`（`FixedGrid(5,4)`） |
| 一字形方向摇杆 | **第 5 项**：`LinearSwipeJoystick` 连续步进 + `Animatable(spring)` 回弹 + `CLOCK_TICK` |
| 横屏迷你触控板零遮挡 | **第 6 项**：上下两段布局，功能条占 40%（4:3 气泡 + 3 圆钮在此展开），压扁键盘占 60%；两者是兄弟布局而非叠层，**按键 100% 完整露出** |
| 底部模式切换 | **第 4 项**：一体式背板 + 弹簧滑块，发 `RedesignAction.SetMode(index)` 由宿主切路由 |
| 粘滞键组合显示 | `RedesignUiState.reduce(PressKey)` 把已锁定修饰键拼成 `Ctrl + Alt + A` 写入监视器 |
| 灵敏度滑块 | `TactileSlider` 0.5x~3.0x 纯内嵌圆柱，鼠标页与触控板页共用同一 state |
| 按下震动 | `TactileSurfaceButton` / `TactileToggleSwitch` 读 `LocalHapticEnabled`，可被设置页关闭 |

---

## 5. 编译与预览

仅对 `android/app/build.gradle.kts` 做了三处必要改动（**不触碰任何现有业务代码**）：

1. `buildFeatures { compose = true }` + `composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }`
   （匹配工程既有 Kotlin 1.9.24）；
2. 追加 `ui / ui-tooling-preview / foundation / material3 / activity-compose` 依赖，
   `debugImplementation(ui-tooling)` 供 `@Preview` 渲染；
3. 可选的 `AIRTOUCH_DEBUG_KEYSTORE` 环境变量签名覆盖 —— 只在变量存在时生效，
   未设置时完全保持 AGP 默认 `~/.android/debug.keystore` 行为（见 5.1）。

```bat
cd android
set JAVA_HOME=E:\code\airtouch\.jdk\jdk-17.0.21+9
gradlew.bat assembleDebug
```

在 Android Studio 打开 `android/`，Sync 后直接打开任一 `*Screen.kt` 或 `RedesignPreviews.kt` 查看预览。

### 5.1 直接装到手机上看

**全 App 只有一个 Activity（`RedesignActivity`）、一个 `AirTouch` 图标，点开就是新版 UI。**
旧版 `PairingActivity` / `ControlActivity` / 三个 Fragment / 全部 `res/layout` / 旧 drawable
已删除；扫码直接内嵌在新版主页的 **1:1 CameraX 取景框**里，`QrCodeAnalyzer` 解码后
由 `RedesignController.connectFromQr()` 直接建立连接，全程不跳任何 Activity。

- 真机入口：`ui/redesign/RedesignActivity.kt` + `RedesignScreenHost.kt` +
  `integration/RedesignController.kt`。
- 顶部无导航标签栏、无模拟状态栏（09:41 / Wi-Fi 98% 已删除）；
  三个模式页共用 Host 层同一个 `TactileSegmentedControl`，切页时滑块弹簧滑动。
- `@Preview` 仍在各 `*Screen.kt` / `RedesignPreviews.kt` 中，Android Studio 可直接预览。

产物：`android/app/build/outputs/apk/debug/app-debug.apk`，交付时用纯英文文件名
（中文文件名在部分文件管理器 / adb 下会安装失败）。拷到手机用文件管理器安装，
或 `adb install -r <apk>`。

> 若手机上装过用 Android Studio 默认 `debug.keystore` 签名的旧包，会因签名不同覆盖失败，
> 先卸载旧包再装。命令行构建可用环境变量 `AIRTOUCH_DEBUG_KEYSTORE` 指向一份固定 debug
> 密钥库；未设置时保持 AGP 默认行为，AS 常规构建不受影响。

---

## 6. 接口对接现状（第二步已完成）

真机入口 `RedesignActivity` + 适配层 `integration/RedesignController` 已把 `RedesignAction`
映射到现有接口，页面 Composable 的签名、布局与色彩 Token 保持不变。

| 页面 / 控件 | 已绑定接口 |
| --- | --- |
| HomeScreen 1:1 扫码 / 连接 / 状态徽章 | 内嵌 CameraX `PreviewView` + `QrCodeAnalyzer` → `connectFromQr()`；`AirTouchClient`、`PairingUri.parse/resume/fromParts`、`PinnedTrust.PinListener` |
| SettingsScreen 4 个开关 | `RedesignPrefs`（独立 SP，落盘）驱动 `AirTouchTheme`；灵敏度/键盘布局写 `SettingsStore` |
| AirMouseScreen 左右键 / 滚轮 / 复位 | `AirTouchClient.sendClick/sendScrollDelta/sendReset` + `AirMouseEngine`（专用 `HandlerThread` 陀螺仪） |
| TouchpadScreen 点阵区 | `detectTransformGestures` + 指针计数 → `move/click/scroll/touch` 帧（单指 move、轻点/双击、双指滚轮、捏合） |
| 全键盘 / 九键按键 | `KeyboardSession`（粘滞修饰键顺序）+ `Vk` 映射 → `sendKey`；中文标点/数字串 → `sendUnicodeText` |
| 横屏迷你触控板 | 同一套 `DotMatrixTouchpad` 回调，位于上 30% 控制区，可自由拖动 |
| 截图按钮 | `Vk.SNAPSHOT` 按键（**协议无截屏回传帧**，见下方限制） |
| 传文件按钮 | SAF `OpenDocument` → `FileSender`；`onFileAck/onFileReceived` 回写状态 |
| 暂停 / 恢复传感 | `RedesignController.startSensors()/stopSensors()` |
| 底部三段切换 | `RedesignAction.SetMode` → `RedesignController.route` 切页 |
| 实时延时 | `sendPing()/onPong()` 2s RTT |

**仍待补齐**：T9 拼音选字（旧 `T9Composer`/`PinyinDictionary` 已随旧 UI 删除，需重新实现）、
三指系统快捷键、截屏回传协议帧（需扩展 `Protocol` 与服务端）。远控模式方案见交付目录中的
《AirTouch_远控模式实现方案.md》（仅方案，未落地代码）。

---

## 7. v1.3 补充

- `ModeControls` = 灵敏度 + 返回/暂停/传文件，鼠标页 / 触控板页调用完全一致；竖屏键盘页放在键盘 Deck 正上方（全拟物样式）。
- `LandscapeKeyboardScreen`：上 30% 为 `TopControlArea`（4:3 `MiniTouchpadBubble` 可拖动，👆 与退出全屏两个圆钮成组跟随，默认贴右边缘），下 70% 为键盘；功能行 `FunctionRow` 的 F1–F12 用 `weight(0.62f)` 缩小 + `Spacer(weight(1f))` 保证 Del 右端与 Bksp/Enter 对齐；`Num◀/Num▶` 一键收起小键盘。
- `LinearSwipeJoystick` 中间滑块改为 16dp 小圆球；竖屏键盘工具行高度 = `portraitKeyHeight × 0.6`。
- 自由选色：`AccentTokens` / `customAccentTokens()` / `accentFromHue()`；设置页 `HuePicker` 色相滑杆 → `RedesignAction.SetCustomAccent` → `RedesignPrefs.customAccentArgb` 落盘；`AirTouchTheme(customAccent = …)` 派生整套强调色。选 4 个预设之一会清除自定义色。
- 鼠标页复位按钮去掉 🎯，只保留文字。

## 8. v1.4 补充

- **扫码修复（v1.4）**：`QrScannerWell` 的 `PreviewView` 改由 `AndroidView` 工厂创建并挂载后再绑定；相机在主页显示期间常驻；点击取景框可重试权限/相机绑定；底部实时显示相机状态。
- **扫码解码重写（v1.5）**：见 §9。
- 竖屏键盘页移除 `ModeControls`，键盘 Deck 正上方只放 3 个拟态圆钮：返回 / 全屏 / 传文件。
- 全屏键盘：`MiniTouchpadBubble` 去掉标题文字与 ✕，描边 2dp→1dp、内边距 8dp→3dp，点阵区尽量占满上 30%；顶部只留 8dp 拖动把手。
- 自绘图标：`HandPointerIcon`（触控按钮）、`MouseKeyboardModeIcon`（I 光标 + 指针）、`RemoteModeIcon`（平板）；两张模式卡片统一为 `ModeEntryCard`，尺寸完全一致，无 emoji。
- 全屏键盘 ZXCV 行去掉右 Shift，其位置拆成 `▲`(↑) + `Num◀/Num▶`；功能行不再有 Num 键。
- 主页删除副标题「Android 无线键鼠与空间外设」。

## 9. v1.5 补充（二维码解码重写）

- `camera/QrDecoder.kt`（新增）：`Bitmap → RGBLuminanceSource`，`HybridBinarizer` + `GlobalHistogramBinarizer` × 0/90/180/270 旋转，共 5 次尝试。
- `camera/QrCodeAnalyzer.kt`：`YUV_420_888 → NV21 → YuvImage → JPEG → Bitmap → QrDecoder.decode`，替换原来手工 repack Y + `PlanarYUVLuminanceSource`（CameraX 1.3 下易把图像下半填 0，导致「有画面但扫不出」）。
- `HomeScreen.kt`：`PreviewView` 用 `COMPATIBLE` 以启用 `getBitmap()`，新增 180ms 轮询的冗余解码路径；`rememberUpdatedState` 保证回调最新；底部显示「相机状态 · 分析 N 帧 / 已扫码：…」可见诊断。
- `RedesignController.connectManual` 支持直接粘贴完整 `wss://` URL；`connectFromQr` 增加扫码内容日志。

## 10. v1.6 补充（找到扫码真正根因）

- **结论**：旧版 `PreviewView` 是全屏 `match_parent`，新版改成 1:1 小框。CameraX 按 surface 尺寸协商流，
  小框让 `ImageAnalysis` 只拿到低分辨率帧（用户看到的清晰预览是放大后的），二维码像素不足 → zxing 永远解不出。
- **做法**：解码器从 git 逐字恢复旧版 `QrCodeAnalyzer`（Y 平面 + `PlanarYUVLuminanceSource`），
  不再重写解码；`Preview` 与 `ImageAnalysis` 都加 `ResolutionSelector` +
  `ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY` 强制高分辨率流。
- 去掉覆盖整框的 `pointerInput`（会吃掉 `PreviewView` 的点击对焦），重试改为底部「重试相机」小按钮。
- 取景框底部新增帧尺寸显示 `W×H`，可直接确认高分辨率是否生效；保留 `getBitmap()` 冗余解码路径。
- `camera/QrDecoder.kt`（v1.6 的冗余解码路径）已在 v1.7 随 `getBitmap()` 轮询一起删除；
  解码统一走 `camera/QrFrameDecoder.kt`。

## 11. v1.7 补充（真机三个问题的定位与修复）

### 11.1 闪退（已由上一轮去掉高分辨率 `getBitmap()` 轮询解决）
`PreviewView` 改成 `COMPATIBLE`(TextureView) 后每 180ms `getBitmap()` 一次，配合
`HIGHEST_AVAILABLE_STRATEGY` 就是每 180ms 申请一张全分辨率 ARGB 位图 —— 秒级 OOM。
现已回到默认 `PERFORMANCE`/SurfaceView，且不再有任何 `getBitmap()` 路径。

### 11.2 扫码：根因是**服务端深色主题的二维码是反相的**
服务端 `MainWindow.UpdateQrCode()` 的渲染规则是：

| 主题 | 模块色 | 底色 |
| --- | --- | --- |
| 浅色 | `#000000` | `#FFFFFF` |
| 深色 | 当前强调色（`#F5B841` / `#38BDF8` / `#34D399` / `#FB7185` / `#A78BFA`） | `#000000` |

zxing 默认只解「深模块 + 浅底」，深色主题那种「浅模块 + 深底」必须显式打开
`DecodeHintType.ALSO_INVERTED` 才会自动重试反相。**这就解释了「只有白底黑块能扫出来」。**

修复：解码核心拆成纯 JVM 的 `camera/QrFrameDecoder.kt`，提示集加入
`ALSO_INVERTED`（并保留 `TRY_HARDER`、限定 `QR_CODE`）。
服务端不用改：QRCoder 已经把 4 模块静默区画进 PNG，且静默区颜色 = 底色，
`QrTrayBrush` 也是同色，所以托盘不会吃掉静默区。

**离线自测**（没有手机也能验证，脚手架在 gitignore 的 `.qrtest/` 下）：
`QrFrameDecoder` 不引用任何 `android.*`，可以用 `kotlinc` 单独编译后跑合成帧，
覆盖「黑模块/白底」「白模块/黑底」「6 个强调色 × 黑底」「rowStride 带 padding」
「3px/模块的小码」「噪声负例」，并带一个去掉 `ALSO_INVERTED` 的**对照实验**
（确认反相码确实解不出来，即根因成立）。13 项全部通过。

### 11.3 陀螺仪串页：鼠标页的姿态位移在触控板/键盘页也在发
`RedesignController.onSensorChanged()` 之前只判断了 `state.paused`，
既不看当前路由也不看前后台，于是连上以后陀螺仪一直在采样，
在触控板页手一转手机光标就乱飞。

修复：新增 `sensorShouldRun()` = 前台 **且** 已连接 **且** 未暂停 **且** 当前在鼠标页，
由 `refreshSensors()` 统一决定注册/注销；所有页面切换收敛到唯一的 `enterRoute()`
（不能叫 `setRoute()`：会和 `var route` 自动生成的 setter 撞 JVM 签名）；
`onSensorChanged()` 里再挡一道（双保险）。顺带把「此设备没有陀螺仪」的提示
从「连上就弹」改成「进鼠标页才弹」。

## 12. v1.8 补充（连上关镜头 / 上下反向 / 两个反相开关）

### 12.1 连上以后关掉相机，取景框变「镜头已关闭」的镜头盖
`QrScannerWell(active = !state.connected)`：连上后 `scanning` 变 false，
绑定相机的 `DisposableEffect` 走到空分支，上一个实例的 `onDispose` 会 `unbindAll()` ——
**相机是被真正释放的**，不只是不显示。

取景框换成一片**新拟物的两片式镜头盖**（`ShutterClosedLens`；**r5 已被机械镜头盖取代，见 §14**）：
材质直接复用按钮/卡片那套面 —— 调 `TactileComponents.drawTactileFace()`，
尺寸和几何用**同一个** `tactileFaceRect()`，所以它和别的控件是同一块料，
而不是另画一个长得像的圆（这两个 helper 因此从 `private` 改成 `internal`）。
盖子上只有：下半片压暗一档、横贯的中缝（上暗下亮，和整套 UI 画内凹阴影用同一套光）、
盖沿一圈极淡收边。**没有六片光圈叶片** —— 用户明确否掉了那版：
「不要六片的，只要那种两片的最外面的镜头盖」。
四角定位框和扫描线只在真正扫码时出现；镜头关着时状态条只剩诊断信息，
操作提示由盖子下面那行「点按打开镜头」承担（不再重复一个「重新扫描其他电脑」链接）。

**同时修掉「一对着二维码就不停重新连接」**：以前扫码成功后取景框里那张码每 1.5 秒
（`REPEAT_INTERVAL_MS`）就再触发一次 `connectFromQr`。现在两道防线：

1. 镜头关了，根本没有新帧；
2. `RedesignController` 记住 `connectTarget = "host:port"`，同一个目标直接忽略 ——
   连「已经扫到、正在握手但 `state.connected` 还是 false」的那个窗口也一起挡住；
   只有主动「断开」才会清空它，所以「断开 → 重扫同一台电脑」仍然可用。

遮光罩可以点一下临时把镜头再打开（状态条里也有「重新扫描其他电脑」），
扫到东西后会自动收回去。

### 12.2 鼠标上下反向：真正的根因是「迁移在没存过该键的机器上也取反」
`SettingsStore.airMouseInvertY` 的 v2.1 迁移本意是「把老语义的取值取反」，
但它没有先判断 `prefs.contains(KEY_AIRMOUSE_INV_Y)`：全新安装 / 清过数据的机器上
`getBoolean(KEY, false)` 返回 `false`，取反后变成 `true` —— **默认就替用户勾上了「上下反向」**。
于是 v2.1 声称「默认 false 就是正确方向」，实际发到用户手里的默认值却是 `true`，方向一直是反的。

修复：`migrateInvertY()` 分两步 —— 只在**确实存在**老值时按老语义取反；
再用 `KEY_AIRMOUSE_INV_Y_MIGRATED_V22` 把 v2.1 那次误置一次性纠正回 `false`。
用户在设置页手动拨过开关之后（setter 会写 V22 标记）不再被任何迁移覆盖。

顺带把 `AirMouseEngine` 类注释里**写反了的符号表**改正：原文写
「顶部向自己 → `gyro[0] > 0`」，按右手定则其实是 `gyro[0] < 0`。
那行注释的符号和结论互相自洽，照它「修正」系数会把方向真的改反 ——
上下方向在历史上被来回改过好几次，根源就是这行注释。
现在的判据是：**抬前端 = 光标上移**（与激光笔一致），对应 `ky = -1`。

### 12.3 设置页新增「鼠标反向（上下）/（左右）」
- 契约：`RedesignUiState.airMouseInvertX/Y` + `RedesignAction.SetAirMouseInvertX/Y`；
- 控制器：写回 `SettingsStore`，并直接改 `engine.invertX/invertY` + `updateAxisMapping()`（立即生效）；
- 设置页用两个 `SettingToggleRow`，并给内容区补上 `verticalScroll`，避免条目变多后在小屏上溢出。


## 13. v1.9 补充（拖拽 / 九键拼音）

### 13.1 拖动：协议一直支持，是新界面没接
`Protocol.buildButton(button, down)` 与 `AirTouchClient.sendButton()` 本来就在，注释里还专门写了
「拖到一半被打断必须补一次 `down = false`」。但重构后的 `RedesignAction` 只有 `TouchpadMove`（移动）
和 `TouchpadClick`（单击 —— 电脑上只得到一次完整的 click），**没有任何「按住」动作**，
所以鼠标模式（陀螺仪推光标）和触控板模式都拖不动东西；旧实现里那个「双击后按住不放」的判定
（`input/TouchpadView.kt`）随旧文件一起被删了。

修法：**左键 / 右键改成「按住即按下、松手才抬起」**（新组件 `TactileHoldButton` +
`RedesignAction.SetButton(button, down)`）。

- 轻点一下再松开 = 一次普通单击（电脑收到 down + up），点阵区「单指轻点 = 左键 / 双击 = 右键」
  的手势映射完全没动；
- 按住不放再移动 = 拖动：鼠标模式倾斜手机，触控板模式另一根手指滑点阵区，两种模式共用同一套代码。

拖拽最怕「手指还按着、电脑上的左键却一直按着」，所以 `RedesignController` 用 `heldButtons`
记着按住了哪些键，并在**切页 / 进后台 / 断开 / 退出**四条路径上统一补一次抬起
（`releaseHeldButtons()`）；`TactileHoldButton` 自身也在 `onDispose` 里兜一次 —— 切页时组件被
移出组合，`onPress` 之后的代码不会执行，只能靠这里。

一个 Compose 细节：`pointerInput` **不能**以回调为 key。`state.heldButton` 一变就会重组，
key 里带着回调的话手势检测会被自己的重组重启、按下状态当场丢失；所以按钮内部用
`rememberUpdatedState` 取最新回调，`pointerInput(enabled)` 只跟 `enabled`。

### 13.2 九键：拼音栈被删掉了，数字就直接发给电脑了
`input/keyboard/t9/`（`PinyinDictionary` / `PinyinEngine` / `T9Composer`）和词库资源
`assets/pinyin_dict.txt` 在工作区里是被删掉的状态，而 `pressKey()` 里还留着 `"2 ABC" -> "2"`
这类映射 —— 于是九键每个数字都当普通文本注入电脑，退化成数字小键盘
（`input/keyboard/KeySpec.kt` 里 `T9_DIGIT` 的注释早就警告过这一点）。

现在三个文件与词库都恢复，并接进新界面：

- 键位描述 `FlatKeySpec` 新增 `t9Digit: Int?`：非空表示该键属于拼音输入，**不产生任何协议帧**；
- 契约新增 `T9Digit / T9Backspace / T9Commit / T9Clear`，以及 `t9Composition / t9Candidates` 两个状态；
- 控制器持有 `T9Composer`，词库在**第一次按九键数字**时才用 `PinyinDictionary.loadAsync()`
  后台加载（约 130KB、近 6000 行，主线程解析会卡住第一帧）；候选与组合串写回 state；
- 键盘页在九键上方多一条**扁平候选条**（左侧组合串 `64'426`，右侧候选，第一个用强调色标出），
  点候选 = `T9Commit` → Unicode 文本注入上屏（协议 §2.7）；
- 组合中 `⌫` 退掉一位数字、`空格` 上屏第一个候选、「重输」清空组合；**组合为空时 `⌫` / `空格`
  恢复成真正的按键帧**。切布局 / 断线 / 进后台都会清空组合。

离线验证在 `.qrtest/t9/`（`PinyinDictionary` 去掉 Android 部分的副本 + 真实词库）：
`64426 → 你好 / 你高 / 你号 …`、`9426 → 议案 / 西安 / …`、乱按 `0000` 有兜底候选、
空词库不崩且 `isDictionaryBroken = true`，全部通过。

已知遗留：九键那一行的 `123` 键现在仍然是「注入文本 123」（`pressKey()` 里的映射），
它既不是布局切换、也不是拼音键 —— 要打真正的数字请切到全键盘。这一条没动，等确认需求。

## 14. v2.0.3-r5 补充（机械镜头盖：拉丝钢圈 + 两片 S 型阶梯叶片 + 对角开合）

r4 那片「纯白圆片 + 一条横线」被用户否掉了：「太单调了，而且没有动画」。现在照用户给的
`lens_cover_spec.html` 复刻成卡片相机那种机械镜头盖，实现在 `HomeScreen.kt` 的
`LensCover()` / `drawLensCover()` / `drawArcEngraving()`。

### 14.1 坐标系统：规范和代码逐行对得上
所有坐标都写在 **240×240 的设计空间**里（就是规范里 `viewBox="0 0 240 240"`），
绘制时统一乘 `s = 镜头边长 / 240` 映射到画布（`X()` / `Y()` 两个局部函数）。
于是规范里的 `<path d="...">` 可以逐条抄成 `Path.moveTo/lineTo/cubicTo`，不做任何换算 ——
以后要改形状，改的是同一组数字。

### 14.2 层级（从下到上）
1. **镜座底板**：整块取景区挖掉中间那个圆角矩形取景窗口（`PathFillType.EvenOdd`）。
   CameraX 的 `PreviewView` 仍然铺满整块取景区，但底板必须是「挖了洞」的，否则会把画面整个盖住 ——
   **预览只从窗口里露出来**，这就是「镜头盖打开后看到的东西」。
2. **钢圈外投影**（规范 cx=120 / cy=124 / r=108，硬边 0.55 黑；同样挖掉窗口）。
3. **最外层拉丝银钢圈**：环宽 82→108（≈24.dp）。用 `Brush.sweepGradient`，亮银 `#E6ECF5` /
   中灰 `#8D99AE` / 暗钢 `#4A5568` 交替**四轮** → 四道高光带，收口回到起点所以扫掠一圈没有接缝；
   外沿 1.dp 亮银高光，内沿 2.dp 深色倒角阴影。
4. **内侧斜坡倒角**（86→82，右下亮 / 左上暗）。
5. **钢圈上的参数刻字**：`4x ZOOM`（上弧 68% 处起）、`0.55`（下弧 22%）、`2mm/`（下弧 56%）。
   Compose 没有 SVG 的 `textPath`，`drawArcEngraving()` 自己沿弧长逐字排：每个字按自己的宽度
   占一段弧，再整字旋转到该点的切线方向 —— 语义和 SVG 一致（于是圆弧下半部分的字会自然
   朝外倒过来，真镜头上的参数刻字就是这样）。
   ⚠️ **一处有意的偏离**：规范把字排在 r=75 / 78，但那两圈正好被上面 r=82 的哑光镜筒框整个
   盖住（HTML 里就是那个绘制次序，字其实一点都看不见）。这里按「刻在钢圈上」的意图把它移到
   可见环带中间 r≈95/96，角度与弧长比例完全照规范。
6. **哑光深灰镜筒框**（`#1E222A`：r=82 的圆挖掉取景窗口）。
7. **取景窗口内**（`clipPath` 严格裁住）：四角黄色对准框（用 `colors.accent`，默认强调色就是
   规范里的 `#F5B800`）、循环扫描线、以及**两片阶梯叶片**。
8. **窗口内凹阴影边框**（压在叶片之上，读起来像窗口是凹进去的）。

### 14.3 两片 S 型阶梯叶片（用户强调的重点）
**不是**一条水平直线切开的上下两半 —— 交界的下/上边缘是一条「左平 → 中间向右上斜切 → 右平」
的带圆角折线，逐字照抄规范的两条 `<path>`。两片之间整体错开 2 个设计单位形成缝隙，
再用 1.5 单位的 `#11141A` 描边压成一道深黑接缝；右下片的切口边缘还有 1 单位、0.16 alpha
的白色高光，体现两片叠压的金属厚度。

几何用 `.verify-lens/verify.mjs` 做过数值验证（把两条 `<path>` 展平成多边形后逐点判定）：

| progress | 取景窗口内被盖住 | 结论 |
| --- | --- | --- |
| 0（闭合） | 98.3%（只剩 1.7% 的接缝） | 完全遮住预览 ✓ |
| 0.5 | 10.7% | 正在退开 ✓ |
| 1（打开） | 0%（露出 100%） | 完整露出预览 ✓ |

接缝位置逐点量出来是：x=62..90 → y=136（左平），x=100→134.75、110→130、120→124、130→118、
140→111.5（中间斜切），x=150..176 → y=108（右平），**左端到右端落差 28 个设计单位**
（若是一条水平直线切开，这个值会是 0）。

### 14.4 200ms 对角开合
`animateFloatAsState(spring(dampingRatio = 0.75f, stiffness = 400f))`（规范给的曲线，约 200ms、
带轻微回弹）：左上片平移 `(-82, -82)`、右下片平移 `(+82, +82)` 设计单位 —— ±82 时两片刚好
完全退出窗口；平移都发生在 `clipPath` 之内，所以只会缩进镜筒两侧而不会糊到钢圈上。
点镜头盖切换（同时给一次机械震动）；循环扫描线只在镜头开着时才跑，alpha 跟着开合进度淡入。

交互：还没连上时 `active = true`，叶片恒开、点按无效（必须扫码）；连上后相机被真正释放
（见 §12.1），叶片闭合、窗口里什么都看不见，点一下重新拉起相机去扫别的电脑。底部状态条里
原来那个「收起镜头」链接去掉了（盖子上已经有提示文字），只留「重试相机」。
（这一版的卡片容器、提示文字、状态条**已在 r6 全部删除**，见 §15。）

## 15. v2.0.3-r6 补充（极致极简：只剩一颗嵌在机身上的圆形镜头）

用户要「保留这颗圆形镜头本身和它的点击开合动画」，但把外面的东西全砍掉。改完这一版，
主页扫码区**一个字都没有**，只剩一颗圆形机械镜头。

### 15.1 去掉外层深色卡片（连带改掉预览的遮挡方式）
原来是 `NeumorphicWell(corner = 24.dp) { Box(padding 10.dp, clip 18.dp, background cameraBackdrop) }`：
外层拟态卡片 + 内层深色圆角底 —— 就是那圈「黑色圆角大方框 + 白边框」。现在直接换成
一个**透明**的 `BoxWithConstraints`，镜头画在正中央，**圆盘之外不画任何东西**，主页的
`bgCanvas` 直接透出来。

这里有个连带的关键改动：以前垫在下面那层「镜座底板」（整块区域挖掉取景窗口）是为了挡住
铺满整块的 `PreviewView`。现在没有底板了，改成**把 PreviewView 本身缩小到取景窗口大小**：
`Modifier.size(lens × 116/240)`（116/240 正是窗口在设计空间里的边长比例），它正好落在窗口
那个圆角矩形的位置上，四个角由上层镜筒（`#1E222A`，不透明）盖掉。于是镜头圆盘之外根本没有
画面，不需要任何遮挡层。

镜头尺寸因此**上提到 `QrScannerWell` 里算**：`lens = (短边 × 0.84).coerceIn(120.dp, 400.dp)`，
预览窗口和镜头盖共用同一个 `lens`，两者永远对齐 —— 以前是 `LensCover` 内部自己算，
现在必须共享（否则小屏上窗口尺寸会和预览对不上）。

**尺寸在 r7 调过**：r6 按规范钉死 224.dp（规范里钢圈直径约 220.dp），去掉外层卡片后
镜头周围空出一大圈、用户反馈「有点太小了」。现在改成占取景区短边的 **84%**
（`LensCoverFill`），手机上约 312.dp —— 比 r6 大 39%，取景窗口（边长 = 镜头 × 116/240）
也从 108.dp 涨到 151.dp，对准二维码也更容易。剩下的每边 8% 不是白留的：外投影的渐变半径
是镜头边长的 0.558 倍，也就是从钢圈外沿还要往外 0.058 倍，再大投影就会被区域边缘切掉。
`LensCoverMax = 400.dp` 只在平板上兜底。

### 15.2 柔和圆形外投影
`drawLensCover` 的第 ① 层换成径向渐变投影：渐变中心比圆心低 4 个设计单位（和规范 `cy=124`
一致，光从上方来），贴着钢圈外沿最深、向外 26 个单位淡出到全透明；颜色用主题的
`colors.shadowDark`（浅色 `#C8D1DC` / 深色 `#0E1013`）—— 和整套新拟物控件是同一套光，
所以看起来是「嵌在机身背板上」而不是「贴了一张图」。投影路径同样把取景窗口挖空，
保证任何情况下都不会把相机画面压暗。原来是规范里那个硬边 0.55 黑圆，太生硬，换成柔和的。

### 15.3 零文字
删掉的东西：

- 镜头下方那行提示（`点按打开镜头` / `扫码中（点按关闭镜头）` / `请扫描电脑上的二维码`）；
- 底部整个状态胶囊（`r5 · 相机状态 · 分析 N 帧 · 1280×960`、`已扫码：wss://…`、`重试相机`）；
- 随之不再需要的状态 `cameraStatus` / `frames` / `frameSize` / `lastDecoded` / `frameCounter`、
  `QrCodeAnalyzer` 的 `onFrame` 回调，以及 `LensHint*` / `LensStatusReserve` / `LensHintText` 常量。

`BUILD_TAG` 改成只在相机启动成功时写一行 logcat（`adb logcat -s QrScannerWell`），界面上不显示。

**一处必须保留的可恢复性**：相机起不来（权限被拒 / 初始化失败）时界面上已经没有「重试」按钮了，
所以点按语义按状态分两种：

- 还没连上（`active`，此时必须扫码）：点一下 = `retry++` 重新挂相机（这时不允许关镜头）；
- 已连上：点一下 = 开关镜头（临时打开去扫别的电脑）。

### 15.4 顺带修掉关闭态的一根亮丝
两片叶片之间错开 2 个设计单位，原本各画 1.5 单位的深色描边 → 中间会剩 0.5 个单位的窄缝，
关闭状态下会透出一根相机画面的亮丝（约 0.5.dp，高密度屏上就是一个像素）。
两道描边加宽到 2.5 单位后互相重叠，接缝就是一条实心的深黑缝。

## 16. v2.0.3-r8 补充（去掉金属镜头框 → 亚光黑边框的方形模块）

用户：「算了不要周围的金属镜头框了，只要圆角矩形取景窗口，两片 S 型阶梯叶片周围一圈质感亚光黑色边框」。
镜头不再是圆的，变成一个**圆角矩形的镜头模块**：一圈亚光黑边框 + 中间的取景窗口。

### 16.1 删掉的与保留的
删掉：拉丝银钢圈（`sweepGradient` 四道高光带）、内侧斜坡倒角、哑光深灰镜筒框，以及钢圈上的
参数刻字 —— `ArcEngraving` / `drawArcEngraving` / `rememberTextMeasurer` 那一整套（约 70 行）
连同 `LensSilver*` / `LensSteel*` / `LensBevel*` / `LensBarrel*` 常量全部删除。

保留（都是点名要的）：圆角矩形取景窗口、两片 S 型阶梯叶片（含那道斜向阶梯接缝）、
四角黄色对准框、循环扫描线、200ms 对角开合、点按开关、柔和外投影。

### 16.2 几何：同心圆角，边框一圈等宽
模块外框 = 取景窗口四周各外扩 `LensBezel = 34` 个设计单位，**圆角取「窗口圆角 + 边框宽度」**：

| | 外框（设计单位） | 边长 | 圆角 | 圆角圆心 |
| --- | --- | --- | --- | --- |
| 取景窗口 | 62..178 | 116 | 22 | 84 |
| 模块 | 28..212 | 184 | 56 | 84 |

两个圆角圆心都是 84 → 一组同心圆角，边框沿任何方向量都是 34 个单位宽（不会四角变粗）。
`.verify-lens/verify.mjs` 里加了这条断言。

### 16.3 亚光黑（matte）怎么做的
亚光的关键是**不出现高光带**，所以没有拉丝金属那种扫掠高光，只叠了四层：

1. 竖直方向极淡渐变 `#23272E → #121519`（上略亮下更暗，做出体积但不反光）；
2. **细颗粒**：280 条极短的微笔触（固定种子生成，位置稳定），深 `#000` α0.10 / 浅 `#FFF` α0.07
   交错，只画在边框那一圈里（`clipPath(bezelPath)`，绝不进窗口）；
3. 模块外沿 1.2 单位收边，用「上受光 → 中透明 → 下压暗」的渐变描边，模块因此「立」在背板上；
4. 窗口内凹阴影：描边裁在窗口之内（上半圈暗 α0.62、下半圈一道极淡反光），
   读起来是「从边框里挖进去的窗口」。

⚠️ 一个当时差点漏掉的坑：外投影是 7 层彼此重叠的实心圆角矩形，如果直接画，会叠出约 40% 的
灰度蒙在取景窗口上（底下的相机预览整片发灰）。所以每一层都做了 EvenOdd 挖洞。

### 16.4 尺寸（r7 → r8）
模块 = 取景区短边 × 0.76，上限 360dp；取景区 = 屏宽 − 40dp（`pageMargin = 20dp`）：

| 屏宽 | 模块 | 取景窗口 | 边框 | 投影外沿到区域边缘 |
| --- | --- | --- | --- | --- |
| 360dp | 243.2 | 153.3 | 44.9 | 10.6dp |
| **411dp** | **282.0** | **177.8** | **52.1** | **12.3dp** |
| 432dp | 297.9 | 187.8 | 55.1 | 13.0dp |

取景窗口反而比 r7（150.6dp）更大：**177.8dp**（r6 只有 108.3dp），对准二维码更轻松 ——
解码仍然用完整的 1280×720 分析流，和窗口显示大小无关。

## 17. v2.0.3-r9 补充（边框减半 → 窗口变大）

用户（通过共享目录 `E:\wenjian\1` 里新建的文件夹名传话）：「现在这个镜头窗口太小了，
而且那个质感哑黑边框有点太粗，变成原来的 0.5 倍就好了」。

只改了一个常量：`LensBezel` **34 → 14**（设计单位）。因为模块尺寸是「取景区短边 × 0.76」，
和边框宽度无关，所以省下来的边框宽度**全部变成了取景窗口**：

| | r8 | r9 |
| --- | --- | --- |
| 模块（411dp 屏） | 282.0dp | 282.0dp（不变） |
| 取景窗口 | 177.8dp | **227.1dp**（+28%） |
| 边框 | 52.1dp | **27.4dp**（= 0.53 倍） |
| 模块外框（设计单位） | 28..212（184） | 48..192（144） |
| 圆角 | 56 | 36（仍是 22 + 边框宽，同心） |

连带两处跟着调：

1. **颗粒改成「只撒在边框那一圈」**（拒绝采样，落在窗口范围内就重掷）。原来是在整个模块里
   均匀撒 280 颗再靠 `clipPath` 裁掉窗口内的，边框宽的时候没问题；边框只剩 14 单位时，
   大部分颗粒会被裁掉，边框上就稀稀拉拉。
2. **投影的模糊范围 21 → 16.8 设计单位**（每层 3 → 2.4）。设计单位换算成 dp 的比例随窗口
   变大而变大（s: 1.53 → 1.96），不收一点的话最外一层投影会被取景区边缘切掉。

叶片、接缝、四角黄框、扫描线、200ms 开合都没动，几何脚本复核结论不变
（关闭态盖住 98.3%、接缝落差 28 单位确认是斜切、打开态露出 100%、边框同心等宽 PASS）。

### 17.1 共享目录传话（`E:\wenjian\1`）
约定：用户在 `E:\wenjian\1` 里**新建一个文件夹或空文件，名字就是要说的话**；`tools\watch-user-msg.ps1`
每 60 秒扫一次这个目录，发现新的（或被改动的）条目就打印并退出，后台任务一退出我就能收到通知、
读到名字并处理，处理完重新挂一次监听（重新取基线）。

⚠️ 第一版只扫了文件（`Get-ChildItem -File`），用户建的是**文件夹**，于是完全没反应。
改成不带 `-File`、并把 `IsDir` 记进签名后才对。APK 每次构建后拷进这个目录（`airtouch-2.0.3-r<N>.apk`），
自己的包用 `^airtouch-.*\.apk$` 排除，不会自己触发自己。

## 18. v2.0.3-r10（相机画面从圆角窗口的四个角漏出来 → 换 TextureView）

现象（r9）：相机画面跑出取景窗口的圆角外，四个角各漏一块。

根因：`PreviewView` 默认 `ImplementationMode.PERFORMANCE`，它是 **SurfaceView** —— 一个独立图层。
系统为了让相机画面透上来，会在窗口上按它的**矩形边界**挖一个洞（punch hole）；
**洞是矩形，取景窗口是圆角矩形**，两者同尺寸 → 只有四个角不一样，于是预览方块的四个角
就从圆角外面露了出来。r9 把窗口放大后圆角半径变成约 43dp（`22 × 282/144`），每个角漏出约 18dp，
边框又只有 27dp 宽，所以特别明显；r6~r8 圆角小（10~14dp）、边框厚，一直没被发现。

修法：`implementationMode = COMPATIBLE`（**TextureView**，画在窗口画布内、没有挖洞这回事），
另外再 `Modifier.clip(RoundedCornerShape(同一个圆角半径))` 双保险 —— 因为预览现在和 Compose
内容在同一个画布里，上面那圈不透明边框才真正盖得住它。

关于老的 OOM 顾虑：旧版的 OOM 来自全屏 TextureView + `getBitmap()` 逐帧取图；
现在预览只有 227dp 见方、也不取 bitmap，分析流另有独立的 1280×720 `ResolutionSelector`，不受影响。








