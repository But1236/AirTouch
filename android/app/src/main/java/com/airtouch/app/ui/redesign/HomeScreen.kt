package com.airtouch.app.ui.redesign

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.airtouch.app.ui.redesign.camera.QrCodeAnalyzer
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTheme
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction
import com.airtouch.app.ui.redesign.theme.RedesignUiState
import com.airtouch.app.ui.redesign.theme.rememberRedesignUiState
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

/*
 * Compose Route: "home"
 * 本轮改动：
 *  - 去掉模拟状态栏（09:41 / Wi-Fi 98%）与顶部导航标签（都在 Host 层，这里不再有）；
 *  - 扫码框改为 **1:1 真实 CameraX 取景**，扫码结果直接回调给 RedesignController 建立连接，
 *    全程留在新版 UI 内，不再调起旧 PairingActivity；
 *  - 移除「连接上次设备」按钮（不再有任何跳转到旧界面的入口）；
 *  - 连接状态卡与模式入口维持原样。
 */

/**
 * 构建标记。界面上已经一个字都不剩了（用户要求镜头周围零文字），所以这个标记只写进 logcat：
 * `adb logcat -s QrScannerWell` 里每次相机启动会打一行，用来确认装的是哪一版。
 * r1 = 旧版（解码排队 + 640×480）；r2 = 就地解码 + 1280×720 修复版。
 * r3 = 连上关镜头 + 上下方向修正 + 两个反相开关；r4 = 镜头盖改成拟态两片式。
 * r5 = 镜头盖改成机械镜头盖（拉丝钢圈 + 哑光镜筒 + 两片 S 型阶梯叶片 + 200ms 对角开合）。
 * r6 = 极致极简：去掉外层深色卡片与白边框，只留圆形镜头 + 柔和外投影，删掉所有文字与状态条。
 * r7 = 镜头放大：不再按规范的 224.dp 钉死，改成占取景区短边的 84%（手机上约 312.dp）。
 * r8 = 去掉金属镜头框，改成「圆角矩形取景窗口 + 一圈亚光黑边框」的方形模块。
 * r9 = 边框宽度减半（34→14 设计单位，实物 52dp→27dp），取景窗口因此从 178dp 涨到 227dp。
 * r10 = 预览从 SurfaceView 换成 TextureView：矩形挖洞会让相机画面从圆角窗口的四个角漏出来。
 * r11 = 横屏全屏隐藏状态栏并去掉 statusBarsPadding；触控板双击=左键双击（按住可拖拽）、
 *       捏合补齐 down/up 阶段；竖屏键盘 0.83 倍高、两侧留白 20→8dp；九键 重输/Shift → 复制/粘贴。
 * r12 = 捏合 scale 改成「从 down 开始累计」（协议要求相对手势开始，发增量会卡在 90%~100%）；
 *       触控板自己等抬手（不再用 waitForUpOrCancellation，被消费一次就丢双击）；
 *       竖屏键盘在实时键值监视器正下方加了就地调起系统输入法的输入区。
 * r13 = 拖拽不再依赖第二条手势协程（按住期间的移动帧由双击那条自己发），触控板右下角显示发帧数。
 * r14 = 拖拽期间每 8 帧补发一次「按下」（服务端幂等，纯自愈）；pointer id 缺席不再当成抬手。
 * r15 = 修掉「滑完光标一松手就自动点击」：只有没怎么动、也没按很久才算轻点。
 * r16 = 双击窗口 300ms→500ms（对齐 Windows 双击间隔）；右下角小字挪进独立 composable。
 * r17 = 删掉整条「双击后按住拖拽」，改成**按住不动 0.2 秒 = 左键按住**（拖拽）：发给电脑的帧序
 *       与实测能拖的「实体左键按住 + 滑动」完全一致（按下 → 一串 move → 抬起），中间不再夹 click。
 * r18 = 修 sendMove 的**整数截断**（灵敏度非整数时每帧都被截成 0，于是「手机在拖、电脑不动」）：
 *       余数攒够 1px 再发；拖拽中右下角实时显示已发帧数。
 * r19 = 拖拽触发改回**双击**为主（第二下按下立刻按住，中途移动不影响），保留「按住不动 0.2 秒」备用。
 *       服务端日志实测：失败的那几次连 button down 都没发出去（全被当成轻点），成功那次
 *       280 个 move 帧 + 「系统认为左键 按着」，证明链路本身是通的。
 * r20 = 双击之后**等 520ms（系统双击窗口）再按左键**：第一下的 click 刚发完就按下，Windows 会把
 *       这次按下当成「双击的第二下」，拖拽被系统吃掉（日志证据：按钮按着、move 帧到位、电脑不拖；
 *       手动注入一次无前置 click 的拖拽立刻成功）。等待期间显示「拖拽准备中」，手指移动照旧移光标。
 * r21 = 改成 r20 之后更符合人体逻辑的触发：**双击后手指一动（>10px）就立刻拖拽**，0.3 秒原地不动
 *       才算双击。关键顺序：先把这段位移发出去再按左键 —— 光标必须先离开系统「双击判定框」（约 4px），
 *       否则那次按下会被 Windows 认作双击第二下（A/B 实测：click 后 60ms 按下 → 标题栏变最大化；
 *       先移 10px 再按 → 正常拖动）。
 * r22 = 按下之后**立刻把那 16px 还回去**：判断框照样被绕过，但拖拽起点回到手指真正按住的位置，
 *       被拖的东西不会一上来就偏掉。
 * r23 = 换成真实驱动那套时序（libinput / Windows 精密触控板 / Mac Trackpad）：第一下轻点抬起就发
 *       click；**第二下按下不发 click，贴着先观望，手指移动超过死区（touchSlop+12px）才正式进拖拽**
 *       （左键按住到松手），没超过死区就抬起 = 普通双击（补第二下 click）。闪避/回位那套删掉了 ——
 *       死区本身就保证按下时光标已经动过、已离开系统双击判定框。签名（0xFF515700）方案实测不可行：
 *       带签名的按下会被系统当成触摸板轻点，不会真的按住。
 * r24 = 死区从「touchSlop+12px」改成按 dp 写死的 **2mm**（12.5dp，任何屏幕密度都是同一物理距离），
 *       代码里仍保证它 ≥ touchSlop×1.5（否则又会掉进 Windows 的双击判定）。
 * r25 = 观望期不再让另一个手势处理器偷跑：`detectTransformGestures` 过了自己的 touchSlop 就开始发
 *       移动帧，于是进入拖拽前光标会先溜十几~几十像素（用户看到的「还是有一小段位移」）。
 *       现在用 DragArming 把观望期的位移**扣住不发**，判定成拖拽时按「先补位移 → 再按左键」发出去
 *       （顺序仍是绕开 Windows 双击判定的那个顺序），判定成双击时先补位移再补 click（click 落在手指位置）。
 * r26 = 死区 2mm → **0.5mm**（3.2dp），观望期加**上限 0.1 秒**（期内超过死区=拖拽，到点没动=双击）。
 *       死区已小于系统 touchSlop，所以进拖拽时不再指望轴移处理器先动光标 —— 改为直接补发
 *       「手指从按下到现在的净位移」（0.5mm≈9px > 系统双击判定框 4px），照样绕开双击判定。
 * r27 = 观望期 0.3s；**观望期到点就直接从第二次按下的位置开始拖**（不再判成双击，双击改由抬手决定：
 *       第二下在观望期内抬起 = 双击）。"在按下位置直接按左键"本来会掉进 Windows 的双击判定，
 *       所以这次闪避挪到**电脑端**做：`MouseController.Button(down)` 发现「上一次是左键单击 + 光标还在
 *       双击判定框内 + 还在双击时间窗内」时，先用 MOUSEEVENTF_ABSOLUTE 把光标偏 8px 按下、再精确
 *       回位（绝对定位不受指针加速影响，零误差），被拖的东西只看到 +8px 又 -8px，净位移为 0。
 * r28 = 按下之后**瞬间把光标从按下位置拖到手指当前的位置**（观望期扣住的位移在按下后一次补上）——
 *       于是拖拽起点是第二次按下的点，位移一点不丢；客户端不再需要"先移动再按下"那套顺序，
 *       闪避完全交给电脑端。
 * r29 = 设置页新增「触摸零延时」开关（默认关，SharedPreferences 持久化），触控板手势改成两套模式：
 *       关 = **观察窗模式**：第一下轻点先扣住 click 160ms（窗内无后续动作 → 补发＝普通单击）；第二下落下后
 *       没动就抬起 = 普通双击（连发两次 click）；按住 >120ms 或滑动 ≥4px = 吞掉第一下的 click、只在原点
 *       按一次左键进拖拽 —— Windows 看不到「点击在前、按下在后」，于是 **0px 起点偏移**。
 *       开 = **零延时模式**：抬手立刻 click；500ms 内第二下落下后先不发左键，没动就抬起 = 补一次 click；
 *       滑动到「电脑端光标累积位移 ≥3px」（走出 4×4px 双击判定框）就先发这 3px、紧接着按下左键，不回位。
 * r30 = 修 r29 的回归：**手势状态机一律不碰多指**。r29 里双指滚动的第二根手指会被当成「Tap2」，
 *       一滑动（≥4px）就判成拖拽、按住左键，用户看到的现象就是「双指滑动没了」。现在只要手势里出现
 *       ≥2 指就当场放弃（观察窗 / Tap2 判定 / 模式 B 判定都一样），滚动与捏合全部交回轴移处理器。
 * r31 = 一批 UI 修复：① 首页删掉「已连接 · 服务端 x.y.z」那一行；② 顶部连接胶囊的延迟改传真实
 *       `state.latencyMs`（以前没传参，一直吃形参默认值 12 → 永远显示 12ms）；③ 鼠标页加
 *       `Spacer(weight(1f))` 把「灵敏度/返回/暂停/传文件」压到底部，与触控板页对齐；④ 触控板页顶部
 *       删掉手势说明文字、补上和鼠标页一样的设置齿轮；⑤ 半屏（竖屏）键盘页新增小触控板（开关在
 *       「传文件」左边、面板在按钮排正上方，复用抽出来的 `MiniTouchpad.kt`）、顶部补胶囊 + 设置齿轮、
 *       系统输入法退格现在会真的把 Backspace 发到电脑（原来只删本地那行字）；⑥ **震动全部改接真正的
 *       `VibrationEffect`**（新 `Haptics.kt`，原来只有 `TextHandleMove`，键盘键帽压根没接震动线）。
 * r32 = 半屏（竖屏）键盘页收尾 + 震动第二修：① 删掉装饰性标签 `PURE FLAT MODE`；② 该页根背景
 *       `bgBase` → `bgCanvas`（与鼠标/触控板/设置/首页一致，那圈"深浅不一"的横带就是页底与按钮条
 *       用了不同底色造成；横屏键盘页同样从 `bgBase` 改过来），截图小样的写死颜色也改成 token；
 *       ③ 小触控板默认停在**最右**，右侧留白 = 键盘自己的 8dp 留白（可继续拖动，越界会被夹回）；
 *       ④ IME 提示行最右加 `⌫` 键：直接发 `PressKey("Backspace")`，绕开 `composition` 判断，
 *       组词中也能删；⑤ IME 输入框锁单行（32–40dp）永不伸长，小触控板移出 `weight(1f)` 可压缩区、
 *       上半区改成可滚动 —— 输入法弹出再也挡不住小触控板和按钮排；⑥ `Haptics` 不再用
 *       `createPredefined`（受系统「触摸反馈」开关管、很多 ROM 映射成无动作），改 `createOneShot`
 *       + 显式振幅（tick 15ms/120、click 25ms/200），设置页打开开关时会来一次 80ms 最大振幅自检。
 * r33 = 半屏键盘页细节 + 震动第三修 + 返回路由：① 删掉 IME 行最右那个 `⌫` 圆钮（用户不要），
 *       输入框改回占满整行；② 「最近发送按键」不再带「已发送: 」前缀（从 `RedesignContract` 源头去掉）；
 *       ③ 输入框与「最近发送按键」改成同款卡片（同圆角/底色/描边，只有高度不同且锁死 60–68dp）；
 *       ④ `HandPointerIcon` 重画：不画大拇指，中指/无名指/小拇指蜷起且等高（原来是一根比一根短，
 *       像张开的巴掌）；⑤ 设置页返回回到**来时的页面**（`routeBeforeSettings`，新增 `"返回上一页"`
 *       分支），不再是固定回主页；⑥ **震动为什么"设置里试振有、按鼠标没反应"**：旧实现把开关值当
 *       `remember` key 在构造时定死，而键帽/物理键/滚轮都是在 `pointerInput(Unit)` 里长期捕获实例的，
 *       开关切换后那些实例永远停在旧值；现在实例只跟 context 绑定，开关值由 `SideEffect` 同步进
 * r34 = 半屏键盘页排版 + 震动第四修：① 「最近发送按键」与输入框**拼回同一个圆角矩形**（外圈共用描边、
 *       中间一条 hairline，两块不留缝），输入框高度锁在约两个字符（32dp 内容 + 上下 8dp）；② 按钮排
 *       从左到右改为 返回 / 传文件 / 小触控板开关 / 全屏，并整体下移约 0.4 个按钮高度（上半留白加大、
 *       下半归零，行高只增 0.4h−rowGap，不会盖住键盘）；③ **震动的真正原因**：r33 把开关值经
 *       `AirTouchTokens.hapticEnabled` + `SideEffect` 同步进实例，用户实测仍"只有试振能震"，说明
 *       那条状态链路没把值送到（开关只写了 prefs）。现在 `AirTouchHaptics` 直接持有 `RedesignPrefs`，
 *       `enabled` **每次调用现读** `prefs.haptic`，彻底不再依赖 Compose 状态传播。
 * r35 = 半屏键盘 + 全屏键盘 + 九键：① 输入框再矮一档（总高 36dp：内容 24 + 上下 6，正文 12sp）；
 *       ② **空框也能删电脑的字**：输入框内部常驻零宽哨兵 `\u200B`，空框按退格 = 删掉哨兵 → 识别成
 *       一次退格并补发 Backspace；③ 键帽**上滑输入**：九键数字键上滑循环该键字母（b→c→a）、QWERTY
 *       字母键上滑发大写（`Shift+X`，`pressKey` 里展开成按住 LSHIFT）、数字键上滑发上档符号；
 *       ④ 半屏滑动小球**只识别水平**，不再发 ↑/↓；⑤ 全屏键盘补「最近发送按键」回显卡片（贴键盘上方，
 *       不挡自由拖动的浮动触控板），并把只发 ↑ 的 `▲▼` 拆成 `▲`/`▼` 两个键 —— 原来全屏页物理上
 *       发不出「下」；⑥ 九键候选上限 8→24、多音节候选改为末两音节组合、上屏后按 1/2 字上下文**联想**。
 */
private const val BUILD_TAG = "r35"

@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    state: RedesignUiState = RedesignUiState(),
    onAction: (RedesignAction) -> Unit = redesignLogAction,
    /** 扫码成功：把二维码原文交给控制器解析并连接（不跳任何 Activity） */
    onQrScanned: (String) -> Unit = {},
) {
    val colors = AirTouchTokens.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.bgCanvas)
            .verticalScroll(rememberScrollState())
            .padding(AirTouchDimens.phonePadding),
        verticalArrangement = Arrangement.spacedBy(AirTouchDimens.blockGap),
    ) {
        // 标题 + 设置齿轮（不再显示副标题）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("AirTouch", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = colors.textPrimary)
            TactileCircleIconButton(
                onClick = { onAction(RedesignAction.Click("打开设置")) },
                size = AirTouchDimens.iconButtonSize,
            ) {
                GearIcon(modifier = Modifier.size(AirTouchDimens.iconSize), tint = colors.textPrimary)
            }
        }

        // 1:1 真实扫码取景框：**连上就把相机关掉**（省电，也不会反复扫到同一个码触发重连），
        // 取景框退化成「镜头已关闭」的遮光罩，点一下可以临时把镜头再打开去扫别的电脑。
        QrScannerWell(
            onQrScanned = onQrScanned,
            active = !state.connected,
        )

        // 连接状态卡（维持现状）
        ConnectionStatusCard(state = state, onAction = onAction)

        // 两个并排按钮：手动连接走 Compose 弹窗、重置信任走本地设置，均不跳旧 UI
        Row(horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap)) {
            TactileSurfaceButton(
                onClick = { onAction(RedesignAction.Click("手动输入 IP/端口")) },
                modifier = Modifier
                    .weight(1f)
                    .height(AirTouchDimens.pairButtonHeight),
                corner = AirTouchDimens.pairButtonCorner,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("手动连接", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
            }
            TactileSurfaceButton(
                onClick = { onAction(RedesignAction.ResetTrust) },
                modifier = Modifier
                    .weight(1f)
                    .height(AirTouchDimens.pairButtonHeight),
                corner = AirTouchDimens.pairButtonCorner,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("重置信任", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = colors.danger)
            }
        }

        // 模式入口：两张卡片用同一个 composable，尺寸/圆角/内边距完全一致
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (state.connected) 1f else 0.35f),
            horizontalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap),
        ) {
            ModeEntryCard(
                title = "键鼠模式",
                badge = "ENTER →",
                accentLit = true,
                enabled = true,
                onClick = { onAction(RedesignAction.Click("进入键鼠模式 -> AirMouseScreen")) },
                icon = {
                    MouseKeyboardModeIcon(
                        modifier = Modifier.size(24.dp),
                        tint = AirTouchTokens.colors.accentText,
                    )
                },
            )
            ModeEntryCard(
                title = "远控模式",
                badge = "暂未开发",
                accentLit = false,
                enabled = false,
                onClick = {},
                icon = {
                    RemoteModeIcon(
                        modifier = Modifier.size(24.dp),
                        tint = AirTouchTokens.colors.textSecondary,
                    )
                },
            )
        }
    }
}

/** 模式入口卡片：两张卡片共用，保证大小、圆角、内边距与排版完全一致。 */
@Composable
private fun RowScope.ModeEntryCard(
    title: String,
    badge: String,
    accentLit: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val colors = AirTouchTokens.colors
    val contentColor = if (accentLit) colors.accentText else colors.textPrimary
    TactileSurfaceButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .weight(1f)
            .height(76.dp),
        corner = 18.dp,
        accentLit = accentLit,
        contentPadding = PaddingValues(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                Text(badge, fontSize = 9.sp, fontWeight = FontWeight.Bold, color = contentColor)
            }
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = contentColor)
        }
    }
}

/**
 * 1:1 内凹槽扫码取景框：真实 CameraX 预览 + NV21/RGB 二维码分析 + 四角定位框 + 循环扫描线。
 * 扫码结果通过 [onQrScanned] 回传控制器，不启动任何旧 Activity。
 *
 * 健壮性要点（修复「有画面但扫不出」与「进主页后闪退」）：
 *  - PreviewView 由 AndroidView 工厂创建并挂载后才绑定相机；
 *  - 用 CameraX 默认的 PERFORMANCE/SurfaceView（TextureView + getBitmap() 高分辨率轮询会 OOM）；
 *  - ImageAnalysis 明确指定 1280×720：不给的话 CameraX 默认只协商到 640×480，
 *    二维码在手边只剩两三个像素/模块，永远解不出来；
 *  - 解码就地发生在分析线程上（见 [QrCodeAnalyzer]），close() 放在最后，
 *    这样 KEEP_ONLY_LATEST 背压才真正生效，不会堆积帧缓冲把内存吃光；
 *  - 界面上零文字：相机起不来时点一下镜头就是重挂相机（见 onTap），状态只写 logcat；
 *  - 镜头圆盘之外不画任何东西，主页背景直接透出来（见 [LensCover]）。
 */
@Composable
private fun QrScannerWell(
    onQrScanned: (String) -> Unit,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val isPreview = LocalInspectionMode.current

    // effect 的 key 不含回调，用 rememberUpdatedState 保证始终调用最新的 connectFromQr
    val currentOnQrScanned by rememberUpdatedState(onQrScanned)

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    // PreviewView 由 AndroidView 工厂创建，挂载后写回 state；相机绑定以它为准
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var retry by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    /** 连上以后镜头默认是关的；点一下镜头可以临时把镜头再打开去扫别的电脑 */
    var reopenLens by remember { mutableStateOf(false) }
    val scanning = active || reopenLens
    // 断开之后（active 变回 true）把「临时打开」收回去，避免状态残留
    LaunchedEffect(active) { if (active) reopenLens = false }
    val mainHandler = remember { Handler(android.os.Looper.getMainLooper()) }

    // 解码现在就地发生在相机分析线程上，回调统一回主线程即可，不再需要额外的 HandlerThread
    val cameraExecutor = remember {
        Executors.newSingleThreadExecutor { r -> Thread(r, "redesign-qr-exec").apply { isDaemon = true } }
    }
    // getInstance 在 composition 阶段执行，包 try/catch 避免 CameraX 初始化异常直接崩 Activity
    val cameraProviderFuture = remember {
        try {
            ProcessCameraProvider.getInstance(context)
        } catch (t: Throwable) {
            Log.w("QrScannerWell", "CameraX 初始化失败", t)
            null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    // 重新检查权限（从系统设置返回后点一下取景框即可恢复）
    LaunchedEffect(retry) {
        if (isPreview) return@LaunchedEffect
        hasPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // 绑定 / 解绑相机：PreviewView 已挂载 + 有权限 + 需要扫码时才绑定。
    // 连上以后 scanning 变 false，这个 effect 就会走到下面的分支，
    // 上一个实例的 onDispose 会 unbindAll() —— 相机是**真的被释放**，不是仅仅不显示。
    DisposableEffect(previewView, hasPermission, scanning, isPreview, retry) {
        val view = previewView
        val future = cameraProviderFuture
        if (isPreview || view == null || !hasPermission || !scanning || future == null) {
            onDispose { }
        } else {
            var provider: ProcessCameraProvider? = null
            var disposed = false
            val listener = Runnable {
                if (disposed) return@Runnable
                try {
                    val p = future.get()
                    provider = p
                    // 关键修复：取景框变成 1:1 小框后，CameraX 按 surface 尺寸给
                    // ImageAnalysis 协商出很低的分辨率，二维码像素不够 → 永远解不出。
                    // 只给 ImageAnalysis 一个明确、可控的 1280×720（Preview 保持默认，
                    // 不去动预览流，避免 TextureView 高分辨率大图导致 OOM 崩溃）。
                    val analysisResolution = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()
                    val preview = CameraPreview.Builder().build()
                    preview.setSurfaceProvider(view.surfaceProvider)
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(analysisResolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(
                        cameraExecutor,
                        QrCodeAnalyzer(
                            callbackHandler = mainHandler,
                            onDecoded = { raw ->
                                // 扫到东西就把镜头收回去，交给连接逻辑处理；否则取景窗口里那张码
                                // 会被反复扫到，变成不停地重新连接。
                                reopenLens = false
                                currentOnQrScanned(raw)
                            },
                            onError = { e -> Log.w("QrScannerWell", "二维码分析异常", e) },
                        )
                    )
                    p.unbindAll()
                    p.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis
                    )
                    // 界面上没有任何诊断文字了，构建标记改从 logcat 看
                    Log.i("QrScannerWell", "$BUILD_TAG · 已绑定预览 + 1280×720 分析")
                } catch (t: Throwable) {
                    // 用 Throwable：CameraX 选流失败时可能抛 OutOfMemoryError 等 Error，
                    // 只 catch Exception 会让 Activity 直接崩掉。
                    // 界面上不再有任何文字，失败后点一下镜头即重新绑定（见下面的 onTap）。
                    Log.w("QrScannerWell", "启动相机失败", t)
                }
            }
            future.addListener(listener, ContextCompat.getMainExecutor(context))
            onDispose {
                disposed = true
                try {
                    provider?.unbindAll()
                } catch (e: Exception) {
                    Log.w("QrScannerWell", "释放相机失败", e)
                }
            }
        }
    }

    // 极致极简：没有外层卡片、没有外框，就是一块圆角矩形的亚光黑镜头模块
    // （一圈边框 + 中间的取景窗口 + 里面的两片 S 型阶梯叶片），直接落在主页背景上。
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f),
        contentAlignment = Alignment.Center,
    ) {
        // 模块尺寸：占这块区域短边的 LensModuleFill，四周剩下一圈给柔和外投影。
        // 在这里算而不是在 LensCover 里算，是为了让下面的预览窗口和模块用同一个尺寸。
        val room = minOf(maxWidth, maxHeight)
        val module = (room * LensModuleFill).coerceIn(160.dp, LensModuleMaxDp)
        // 取景窗口边长 = 模块边长 × 116/184（同一套设计单位）。预览只铺在窗口这一小块里，
        // 四个角被上层那圈不透明边框盖住 —— 模块之外一点相机画面都不露。
        val aperture = module * (LensApertureSize / LensModuleSize)

        when {
            // 预览占位（Layoutlib @Preview）：画个静态取景框，叶片开着时从窗口里露出来
            isPreview -> QrGlyph(
                modifier = Modifier
                    .size(72.dp)
                    .alpha(0.25f),
            )

            // 正在扫码：真实相机预览，严格只占取景窗口大小
            scanning -> AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        // ⚠️ 必须用 COMPATIBLE（TextureView），不能用默认的 PERFORMANCE（SurfaceView）：
                        // SurfaceView 是独立图层，系统会在窗口上按它的**矩形边界**挖洞（punch hole）
                        // 让相机画面透上来；洞是矩形，而取景窗口是圆角矩形 → 预览方块四个角会从
                        // 圆角外面漏出相机画面（r9 圆角半径约 43dp，每个角漏出约 18dp）。
                        // TextureView 是画在窗口画布里的，上层那圈不透明边框才能真正盖住它。
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                },
                update = { pv -> if (previewView !== pv) previewView = pv },
                // 视图被移除时清掉引用，否则断开重连后会拿着一个已经 detach 的
                // PreviewView 去 setSurfaceProvider，预览会是黑的。
                onRelease = { pv -> if (previewView === pv) previewView = null },
                // 再按取景窗口的圆角裁一刀（同一个圆角半径），双保险
                modifier = Modifier
                    .size(aperture)
                    .clip(RoundedCornerShape((LensApertureCorner * module.value / LensModuleSize).dp)),
            )

            // 已连接：底下没有画面可看，整块都是上层的镜头（叶片闭合）
            else -> Unit
        }

        // 机械镜头盖：就是这颗圆形组件本身（+ 柔和外投影），点按开关，两片叶片 200ms 对角开合。
        // 几何 / 配色 / 动画参数照 lens_cover_spec.html。
        LensCover(
            moduleSize = module,
            open = scanning,
            // 还没连上时必须扫码、不给关，这时点一下 = 重试拉起相机（界面上没有文字，
            // 相机起不来时全靠这一下重试）；连上以后点一下 = 开关镜头（去扫别的电脑）。
            onTap = { if (active) retry++ else reopenLens = !reopenLens },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ---------------------------------------------------------------------------
// 机械镜头盖（卡片相机那种金属多环嵌套 + 双叶片）
// ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
// 几何、配色、动画参数照 `lens_cover_spec.html`：所有坐标都写在 240×240 的设计空间里，
// 绘制时统一乘 `s = 镜头边长 / 240` 映射到画布 —— 于是规范和代码可以逐行对上。
//
// 层级（从下到上）：
//   ① 镜座底板：整块取景区挖掉中间的圆角矩形取景窗口。CameraX 预览就在这层下面，
//      底板必须是"挖了洞"的，否则会把画面整个盖住；
//   ② 钢圈外投影；③ 最外层拉丝银钢圈；④ 内侧斜坡倒角；⑤ 钢圈上的参数刻字；
//   ⑥ 哑光深灰镜筒框；⑦ 取景窗口内：四角黄框 + 两片阶梯叶片 + 循环扫描线；
//   ⑧ 窗口内凹阴影边框。
//
// 叶片**不是**一条水平直线切开的上下两半，而是两片 S 型阶梯斜切：交界是一条
// 「左平 → 中间向右上斜切 → 右平」的带圆角折线；两片之间留 2 个设计单位的缝，
// 再用 1.5 单位的 #11141A 描边压成一道深黑接缝。
// ---------------------------------------------------------------------------

/** 取景窗口（圆角矩形）：设计单位 x/y 62..178，圆角 22；[LensApertureSize] 是它的边长。
 *  （这套坐标来自规范的 `viewBox="0 0 240 240"` 设计空间，中心在 120。） */
private const val LensApertureMin = 62f
private const val LensApertureMax = 178f
private const val LensApertureCorner = 22f
private const val LensApertureSize = LensApertureMax - LensApertureMin

/**
 * 亚光黑边框那一圈的宽度（设计单位）：模块外框 = 取景窗口四周各外扩 [LensBezel]。
 * 圆角取「窗口圆角 + 边框宽度」→ 一组同心圆角，边框才是一圈均匀宽度，而不是四个角变粗。
 *
 * r9 按用户要求把边框减半：34 → 14（实物约 52dp → 27dp）。模块尺寸不变，所以取景窗口
 * 反而从 177.8dp 涨到 227dp。
 */
private const val LensBezel = 14f
private const val LensModuleMin = LensApertureMin - LensBezel
private const val LensModuleMax = LensApertureMax + LensBezel
private const val LensModuleSize = LensModuleMax - LensModuleMin
private const val LensModuleCorner = LensApertureCorner + LensBezel

/** 叶片开合行程（设计单位）：±82 时两片刚好完全退出取景窗口。 */
private const val LensBladeTravel = 82f

/**
 * 模块（亚光黑边框）占取景区短边的比例。手机上模块约 282.dp、里面的取景窗口约 227.dp；
 * 再大一点柔和外投影就会被取景区边缘切掉（最外一层向外扩 16.8 个设计单位）。
 */
private const val LensModuleFill = 0.76f

/** 模块边长上限：只在平板这种超宽屏上兜底。 */
private val LensModuleMaxDp = 360.dp

// —— 亚光黑边框（matte black）：不靠高光带，靠「上略亮 → 下更暗」的极淡渐变 + 细颗粒 + 一圈收边
private val BezelTop = Color(0xFF23272E)
private val BezelBottom = Color(0xFF121519)
private val BezelEdgeLight = Color(0x1FFFFFFF)
private val BezelEdgeDark = Color(0x59000000)
private val BezelGrainLight = Color(0x12FFFFFF)
private val BezelGrainDark = Color(0x1A000000)
private val BezelInnerShadow = Color(0x9E000000)

// —— 两片 S 型阶梯叶片（枪灰色机械面）
private val LensBladeSeam = Color(0xFF0E1116)
private val LensBladeTopLight = Color(0xFF474E5A)
private val LensBladeTopDark = Color(0xFF2E333C)
private val LensBladeBottomLight = Color(0xFF3C424D)
private val LensBladeBottomDark = Color(0xFF252932)

/**
 * 边框上的一粒细颗粒。亚光黑（喷砂/磨砂塑料）的「质感」就靠它：
 * 一批极短的微笔触、深浅交错，只画在边框那一圈里（窗口内不画，免得糊住取景画面）。
 */
private class BezelGrain(
    val x: Float,
    val y: Float,
    val len: Float,
    val light: Boolean,
)

/** 固定种子生成，位置稳定 —— 不能每次重组都跳一批新颗粒。只保留落在边框那一圈里的：
 *  边框只有 14 个单位宽，均匀撒满模块的话大部分颗粒会落在窗口里被裁掉，边框上就稀稀拉拉。 */
private fun buildBezelGrain(): List<BezelGrain> {
    val rnd = java.util.Random(0x5EED_5EEDL)
    val out = ArrayList<BezelGrain>(320)
    var guard = 0
    while (out.size < 320 && guard++ < 20_000) {
        val x = LensModuleMin + rnd.nextFloat() * LensModuleSize
        val y = LensModuleMin + rnd.nextFloat() * LensModuleSize
        val inWindow = x > LensApertureMin - 1f && x < LensApertureMax + 1f &&
            y > LensApertureMin - 1f && y < LensApertureMax + 1f
        if (inWindow) continue
        out += BezelGrain(x, y, 1.2f + rnd.nextFloat() * 2.4f, rnd.nextBoolean())
    }
    return out
}

/**
 * 镜头模块：一圈亚光黑边框 + 中间的圆角矩形取景窗口，窗口里是两片 S 型阶梯叶片。
 *
 * 连上电脑后相机被真正释放（省电；也避免取景窗口里那张码被反复扫到而反复重连），
 * 这时两片叶片闭合、窗口里什么都看不见；点一下模块就把叶片滑开、重新拉起相机去扫别的电脑。
 */
@Composable
private fun LensCover(
    moduleSize: Dp,
    open: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AirTouchTokens.colors
    // 真正的振动器实现（见 Haptics.kt）：开关关闭时是空操作，这里不用再判断。
    val haptics = rememberAirTouchHaptics()

    // 对角开合：spring(dampingRatio = 0.75, stiffness = 400) —— 约 200ms，带轻微物理回弹
    val progress by animateFloatAsState(
        targetValue = if (open) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 400f),
        label = "lensBlade",
    )
    // 循环扫描线只在镜头开着时跑：关着的时候不必占着帧时钟
    val scan = if (open) {
        val transition = rememberInfiniteTransition(label = "lensScan")
        val y by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 2600),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "lensScanY",
        )
        y
    } else {
        0f
    }

    // 边框颗粒只生成一次（固定种子，位置稳定）
    val grain = remember { buildBezelGrain() }

    // 整块区域就这一张画布：模块画在正中央，画布本身透明 —— 模块之外什么都没有，
    // 主页背景直接透出来（没有卡片、没有外框、没有文字）。
    Canvas(
        modifier = modifier.pointerInput(onTap) {
            detectTapGestures(
                onPress = {
                    haptics.tick()
                    tryAwaitRelease()
                },
                onTap = { onTap() },
            )
        },
    ) {
        drawLensCover(
            moduleSize = moduleSize,
            progress = progress,
            scan = scan,
            accent = colors.accent,
            shadow = colors.shadowDark,
            grain = grain,
        )
    }
}

private fun DrawScope.drawLensCover(
    moduleSize: Dp,
    progress: Float,
    scan: Float,
    accent: Color,
    shadow: Color,
    grain: List<BezelGrain>,
) {
    // 设计单位 → px：模块在设计空间里是 28..212（[LensModuleSize] = 184 个单位）
    val s = moduleSize.toPx() / LensModuleSize
    // 模块永远画在画布正中央（外面的 Box 已经把这块区域居中，取景窗口也用同一个中心）
    val lensCenter = center
    fun X(v: Float) = lensCenter.x + (v - 120f) * s
    fun Y(v: Float) = lensCenter.y + (v - 120f) * s
    fun P(x: Float, y: Float) = Offset(X(x), Y(y))
    fun roundRectOf(min: Float, max: Float, corner: Float, dy: Float = 0f) = RoundRect(
        rect = Rect(X(min), Y(min + dy), X(max), Y(max + dy)),
        cornerRadius = CornerRadius(corner * s),
    )

    val aperture = roundRectOf(LensApertureMin, LensApertureMax, LensApertureCorner)
    val aperturePath = Path().apply { addRoundRect(aperture) }
    // 边框那一圈 = 模块圆角矩形挖掉取景窗口（窗口必须是「洞」，否则会把底下的相机预览盖住）
    val bezelPath = Path().apply {
        addRoundRect(roundRectOf(LensModuleMin, LensModuleMax, LensModuleCorner))
        addRoundRect(aperture)
        fillType = PathFillType.EvenOdd
    }

    // ① 柔和外投影：7 层逐渐放大、逐渐变淡的圆角矩形，整体下移 2 个单位（光从上方来）。
    //    没有用平台的 elevation 阴影：它在旧系统上会忽略自定义阴影色，自己叠更可控，
    //    也能保证用的是主题的新拟物暗面色（和别的控件同一套光）。
    //    ⚠️ 每层都必须把取景窗口挖掉 —— 这 7 层是实心圆角矩形、彼此重叠，直接画会把
    //    ~40% 的灰度蒙在窗口上（底下的相机预览会发灰）。
    repeat(7) { i ->
        val grow = (i + 1) * 2.4f
        drawPath(
            path = Path().apply {
                addRoundRect(roundRectOf(LensModuleMin - grow, LensModuleMax + grow, LensModuleCorner + grow, dy = 2f))
                addRoundRect(aperture)
                fillType = PathFillType.EvenOdd
            },
            color = shadow.copy(alpha = 0.10f * (1f - i / 7f)),
        )
    }

    // ② 亚光黑边框本体：竖直方向极淡的渐变（上略亮、下更暗）。亚光的关键是「不出现高光带」，
    //    所以这里没有拉丝金属那种扫掠高光，只靠这层渐变 + 细颗粒 + 一圈收边把体积做出来。
    drawPath(
        path = bezelPath,
        brush = Brush.linearGradient(
            colors = listOf(BezelTop, BezelBottom),
            start = P(120f, LensModuleMin),
            end = P(120f, LensModuleMax),
        ),
    )

    // ③ 边框的细颗粒（质感）：一批极短的微笔触，深浅交错，裁在边框那一圈里。
    clipPath(bezelPath) {
        grain.forEach { g ->
            drawLine(
                color = if (g.light) BezelGrainLight else BezelGrainDark,
                start = P(g.x, g.y),
                end = P(g.x + g.len, g.y + g.len * 0.4f),
                strokeWidth = 0.7f * s,
                cap = StrokeCap.Round,
            )
        }
    }

    // ④ 边框自身的一圈收边：上沿一道极淡受光、下沿压暗 —— 模块因此「立」在机身背板上。
    drawPath(
        path = Path().apply { addRoundRect(roundRectOf(LensModuleMin, LensModuleMax, LensModuleCorner)) },
        brush = Brush.linearGradient(
            colors = listOf(BezelEdgeLight, Color.Transparent, BezelEdgeDark),
            start = P(120f, LensModuleMin),
            end = P(120f, LensModuleMax),
        ),
        style = Stroke(1.2f * s),
    )

    // ⑤ 取景窗口内部：四角黄框 + 两片阶梯叶片 + 扫描线，全部严格裁在窗口里
    clipPath(aperturePath) {
        // 四角黄色对准框（规范路径，2.5 单位圆头描边）
        drawPath(
            path = Path().apply {
                moveTo(X(76f), Y(92f))
                lineTo(X(76f), Y(76f))
                lineTo(X(92f), Y(76f))
                moveTo(X(148f), Y(76f))
                lineTo(X(164f), Y(76f))
                lineTo(X(164f), Y(92f))
                moveTo(X(164f), Y(148f))
                lineTo(X(164f), Y(164f))
                lineTo(X(148f), Y(164f))
                moveTo(X(92f), Y(164f))
                lineTo(X(76f), Y(164f))
                lineTo(X(76f), Y(148f))
            },
            color = accent,
            style = Stroke(width = 2.5f * s, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )

        // 循环扫描线：叶片快退开时才出现，alpha 跟着开合进度淡入
        val scanAlpha = ((progress - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (scanAlpha > 0f) {
            val y = Y(70f + 100f * scan)
            drawLine(
                color = accent.copy(alpha = 0.9f * scanAlpha),
                start = Offset(X(64f), y),
                end = Offset(X(176f), y),
                strokeWidth = 2f * s,
                cap = StrokeCap.Round,
            )
        }

        // 叶片 A（左上片）：下边缘是「左平 → 中间向右上斜切 → 右平」的 S 型阶梯折线
        val bladeTop = Path().apply {
            moveTo(X(50f), Y(50f))
            lineTo(X(190f), Y(50f))
            lineTo(X(190f), Y(108f))
            lineTo(X(154f), Y(108f))
            cubicTo(X(144f), Y(108f), X(138f), Y(112f), X(130f), Y(118f))
            lineTo(X(110f), Y(130f))
            cubicTo(X(102f), Y(135f), X(96f), Y(136f), X(86f), Y(136f))
            lineTo(X(50f), Y(136f))
            close()
        }
        // 叶片 B（右下片）：上边缘与 A 严密咬合（整体低 2 个设计单位 → 留出深黑接缝）
        val bladeBottom = Path().apply {
            moveTo(X(50f), Y(138f))
            lineTo(X(86f), Y(138f))
            cubicTo(X(96f), Y(138f), X(102f), Y(137f), X(110f), Y(132f))
            lineTo(X(130f), Y(120f))
            cubicTo(X(138f), Y(114f), X(144f), Y(110f), X(154f), Y(110f))
            lineTo(X(190f), Y(110f))
            lineTo(X(190f), Y(190f))
            lineTo(X(50f), Y(190f))
            close()
        }
        // B 的切口边缘高光，体现两片叠压的金属厚度
        val bladeHighlight = Path().apply {
            moveTo(X(52f), Y(138.5f))
            lineTo(X(86f), Y(138.5f))
            cubicTo(X(96f), Y(138.5f), X(102f), Y(137.5f), X(110f), Y(132.5f))
            lineTo(X(130f), Y(120.5f))
            cubicTo(X(138f), Y(114.5f), X(144f), Y(110.5f), X(154f), Y(110.5f))
            lineTo(X(188f), Y(110.5f))
        }

        // 开：左上片往左上角退、右下片往右下角退，都被裁在窗口里缩进镜筒两侧
        // 描边用 2.5 而不是 1.5：两片之间本来就错开 2 个单位，各自的描边只有 1.5 时
        // 中间会剩 0.5 个单位的窄缝，关闭状态下会透出一根相机画面的亮丝；2.5 让两道
        // 描边互相重叠，接缝就是一条实心的深黑缝。
        val travel = LensBladeTravel * progress * s
        translate(left = -travel, top = -travel) {
            drawPath(
                path = bladeTop,
                brush = Brush.linearGradient(
                    colors = listOf(LensBladeTopLight, LensBladeTopDark),
                    start = P(50f, 50f),
                    end = P(190f, 136f),
                ),
            )
            drawPath(bladeTop, color = LensBladeSeam, style = Stroke(2.5f * s))
        }
        translate(left = travel, top = travel) {
            drawPath(
                path = bladeBottom,
                brush = Brush.linearGradient(
                    colors = listOf(LensBladeBottomLight, LensBladeBottomDark),
                    start = P(50f, 110f),
                    end = P(190f, 190f),
                ),
            )
            drawPath(bladeBottom, color = LensBladeSeam, style = Stroke(2.5f * s))
            drawPath(bladeHighlight, color = Color.White.copy(alpha = 0.16f), style = Stroke(1f * s))
        }
    }

    // ⑥ 取景窗口的内凹阴影（压在叶片之上，读起来才像窗口是从边框里挖进去的）：
    //    描边裁在窗口之内，上半圈是暗的（内阴影）、下半圈一道极淡的反光。
    clipPath(aperturePath) {
        drawPath(
            path = aperturePath,
            brush = Brush.linearGradient(
                colorStops = arrayOf(
                    0.00f to BezelInnerShadow,
                    0.42f to Color.Transparent,
                    0.78f to Color.Transparent,
                    1.00f to BezelEdgeLight,
                ),
                start = P(120f, LensApertureMin),
                end = P(120f, LensApertureMax),
            ),
            style = Stroke(9f * s),
        )
    }
}

/** 连接状态卡：加密盾牌 + 毫秒延时 + 上次连接信息 + 身份。 */
@Composable
private fun ConnectionStatusCard(state: RedesignUiState, onAction: (RedesignAction) -> Unit) {
    val colors = AirTouchTokens.colors
    NeumorphicWell(
        modifier = Modifier.fillMaxWidth(),
        corner = 20.dp,
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            (if (state.connected) colors.success else colors.danger).copy(alpha = 0.14f)
                        )
                        .redesignTap {
                            onAction(if (state.connected) RedesignAction.Disconnect else RedesignAction.Click("手动输入 IP/端口"))
                        }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(50))
                                .background(if (state.connected) colors.success else colors.danger),
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = if (state.connected) "已连接 (点击断开)" else "未连接 (点击连接)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.connected) colors.success else colors.danger,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShieldCheckIcon(modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = if (state.latencyMs >= 0) "延时: ${state.latencyMs}ms" else "延时: -- ms",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = colors.textSecondary,
                    )
                }
            }
            InfoLine("上次连接信息:", state.connectionLabel)
            InfoLine("身份:", state.deviceIdentity)
            if (state.statusText.isNotEmpty()) {
                InfoLine("状态:", state.statusText)
            }
            if (state.fileStatus.isNotEmpty()) {
                InfoLine("文件:", state.fileStatus)
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    val colors = AirTouchTokens.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = colors.textSecondary)
        Text(value, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, color = colors.textPrimary)
    }
}

private fun Modifier.redesignTap(onClick: () -> Unit): Modifier = this.pointerInput(onClick) {
    detectTapGestures(onTap = { onClick() })
}

@Preview(name = "01 · 连接与主页 (Light)", showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun HomeScreenPreview() {
    val (state, onAction) = rememberRedesignUiState()
    AirTouchTheme(darkTheme = state.darkTheme, accent = state.accent) {
        HomeScreen(state = state, onAction = onAction)
    }
}
