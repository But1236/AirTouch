# 键盘模式 v2：九键中文输入 + 输入手感返工

> 本文是实现前的技术方案。落地后可以直接删掉，或者把「关键决定」一节合并进 `docs/PROTOCOL.md`。

## 一、本轮要解决的四件事

| # | 用户反馈 | 处理 |
| --- | --- | --- |
| 1 | 九键不是数字/符号键盘，是手机上那种**能打中文**的输入法；另外顶部工具栏要留一个「纯文本输入框」小按钮，用来补大段文字 | 走**方案 A**：自绘九键 + 内置拼音词库 + 候选条，选中的中文用新增的 `text` 帧直接注入电脑（见 §3、§5） |
| 2 | Shift / Win 不要粘滞（不然没法单独按它们）；Alt 也一起改，只有 Ctrl 保持粘滞 | `KeySpec` 增加 `sticky` 标记，只有 Ctrl 走粘滞状态机，其余修饰键变成普通「按下即生效」键（见 §2） |
| 3 | 十字摇杆条太矮，上下滑动容易误触，改成像夸克那样的一字滑条 | 上下轴整个去掉：一字滑条只做 ←/→；↑/↓ 变成两个独立按键（见 §4） |
| 4 | 全屏模式没法打开触摸板 | 重做气泡的放置方式，让它**不可能**跑到屏幕外；同时把入口做得能被发现（见 §6） |

一个必然的连带改动：Shift 不再粘滞之后，「按住 Shift + 敲字母 = 大写」只能靠**两根手指**完成，所以自绘键盘必须支持多指同时按键（见 §2.2）。不做这一步，Shift/Win/Alt 会变成完全没用的键。

---

## 二、修饰键：只有 Ctrl 粘滞

### 2.1 数据模型

`input/keyboard/KeySpec.kt`

```kotlin
data class KeySpec(
    ...,
    val sticky: Boolean = false,   // 新增：只有 Ctrl 为 true
) {
    val isModifier: Boolean get() = kind == KeyKind.MODIFIER
    /** 走粘滞状态机、不直接产生协议帧 */
    val isStickyModifier: Boolean get() = kind == KeyKind.MODIFIER && sticky
    /** 会真的产生协议帧：普通键 + 非粘滞修饰键 */
    val emitsKey: Boolean get() = kind == KeyKind.PLAIN || (kind == KeyKind.MODIFIER && !sticky)
}
```

`Keys.modifier(vk, label, weight, sticky = false)` 增加 `sticky` 形参。

`input/keyboard/KeyboardLayouts.kt`：`Ctrl` 传 `sticky = true`，`Shift`/`Alt`/`Win` 保持默认 `false`。渲染用的 `KeyKind.MODIFIER` 不变，所以它们的底色仍然比普通键暗一档。

行为差异一览：

| 键 | 按下 | 抬起 | 单独按它 |
| --- | --- | --- | --- |
| Ctrl | 只推进状态机，不发帧 | 推进状态机 | 单击=单次锁定，双击=持续锁定 |
| Shift / Alt / Win | 立刻发 `KEY_DOWN` | 立刻发 `KEY_UP` | 真的按下去，配合另一根手指按字母 |

`KeyboardView` 里所有 `key.isModifier` 判断改为 `key.isStickyModifier`（只有粘滞键才在抬起时触发 `onModifierTap`、才查询 `modifierLock` 高亮）。`KeyboardSession` / `KeyboardInputHandler` 无需改动：非粘滞修饰键就是普通 `pressKey`/`releaseKey`。

### 2.2 自绘键盘支持多指（`input/KeyboardView.kt`）

把「一个手势」状态换成「每个 pointerId 一份状态」：

```kotlin
private class PointerState(
    var key: KeySpec?,      // 当前按住的键
    var downX: Float, var downY: Float,
    var sliding: Boolean,
    var repeatKey: KeySpec?, // 这一指是否拥有连发权
)
private val pointers = HashMap<Int, PointerState>()
```

- `ACTION_DOWN` / `ACTION_POINTER_DOWN`：`val i = event.actionIndex`；用 `event.getX(i)/getY(i)` 与 `event.getPointerId(i)` 新建一份状态并 `beginPress`（按下即发 `KEY_DOWN` + 触觉反馈，语义与现在完全一致）。
- `ACTION_MOVE`：遍历 `event.pointerCount`，逐指判断是否越过 `SLIDE_SLOP_DP`（越过才允许换键），命中不同键时先抬旧键再按新键。**不改动滑选/防抖阈值**，单指行为与现在逐字节一致。
- `ACTION_UP` / `ACTION_POINTER_UP`：只结束 `actionIndex` 那一指；粘滞键/动作键的「抬起点在同一个键上才算点击」判定同样按该指自己的坐标算。
- `ACTION_CANCEL`：结束全部指。
- **连发只有一个**：`activeRepeat: Pair<Int /*pointerId*/, KeySpec>?`，后按下的可连发键抢占；拥有者那一指抬起或移出该键就连发停止。这样「按住 Shift（不连发）+ 按住退格（连发）」互不干扰。
- `requestDisallowInterceptTouchEvent(true)` 只在第一根手指按下时调用一次；最后一根手指抬起时恢复。
- 仍然无条件消费落在键盘上的全部触摸（防穿透/防误触）。

### 2.3 提示文案

`ModifierNames` 保持通用（读 `session.activeModifiers()`），只是实际只会有 Ctrl。`strings.xml` 里 `keyboard_hint_*` 的说明改成「Ctrl 单击=单次、双击=持续；Shift/Alt/Win 按住即生效，配合另一根手指按字母/方向键」。

---

## 三、九键拼音输入（核心）

### 3.1 为什么必须是「手机端词库 + 直接注入」

九键的每一次按键都对应 3~4 个字母（`5` = j/k/l），只有**词库**才能把 `5426` 消歧成 `jian`/`lian`/`jiao`，再落到汉字。所以「九键能打中文」= 手机端必须有拼音词库。选中的中文也不能用虚拟键码表达，必须新增一个文本帧（§5）。

方案 A 的另一半价值：**不依赖电脑装没装输入法、也不依赖它的中英状态**——这对远程键盘很重要。

### 3.2 词库资源与生成流水线

- 生成脚本：`tools/build-pinyin-dict.mjs`（仓库已有 `tools/*.mjs` 的传统）。
- 输入（在生成时联网取一次，落到 `tools/pinyin-source/` 并加入 `.gitignore`，产物入库）：
  - 汉字→拼音：Unihan `kMandarin` 或 `mozillazg/pinyin-data`；
  - 字频：一份公开的现代汉语字频表（用于候选排序，频率高的排前面）；
  - 词表：常用二字/三字词（用于整词候选，如 `64426 → 你好`）。
- 产物：`android/app/src/main/assets/pinyin_dict.txt`，**提交进仓库**，因此 App 构建与运行完全离线。预估 40~70KB（远低于 100~300KB 的预算）。
- 格式（行式、便于用 `BufferedReader` 流式解析、也便于 Node 侧校验）：

```
#chars
2<TAB>啊阿吖嗄锕
24<TAB>比笔彼...
#words
64426<TAB>你好
```

按键 = 该字**主拼音**的音节数字（`n`→6 `i`→4 `h`→4 `a`→2 `o`→6 ⇒ `64426`）。

- **实际产物**（生成脚本跑完的真实数字，可复核）：132.5 KB / 12009 个不重复汉字（215 个数字键）/
  7800 条词（5678 个数字键）。来源是 `mozillazg/pinyin-data`（读音）与 `fxsjy/jieba` 的词表+词频，
  两者都是 MIT；`tools/pinyin-source/` 里的原始语料不入库。
- **多音字词的已知妥协**：词键按成员字的**主拼音**拼接，所以 `银行` 落在 `9469464`（行 = zhǎng 的音）
  而不是 `yinhang` 的 `9464264`。Android 端按用户敲出的数字直接查词，因此这类词查不到，会退化成
  逐音节候选 —— 用户已经预判「整词不够聪明」，两条兜底（原样数字候选 +「纯文本输入框」）都在。
  不要为了「修正」这一点去改生成脚本，除非同时改掉两端的查询规则。

- 校验脚本：`tools/verify-pinyin-dict.mjs`，离线、不联网、失败即非 0 退出。检查：段头与格式、
  汉字键自洽（同一个字只能挂一个键）、**每个词的键必须等于其成员字键的顺序拼接**（把两段绑死）、
  `64426` 的首候选是「你好」、常用字覆盖率（≥3500 字 + 200 字固定样本）、文件体积。
  **这是本轮唯一能自动化的功能验证**（纯数据，不需要 Android）。

### 3.3 引擎（纯 Kotlin，无 Android 依赖，便于日后加单测）

- `input/keyboard/t9/PinyinDictionary.kt`：后台线程加载并解析资源；对外
  `charsFor(digits): List<Char>`、`wordsFor(digits): List<String>`、`isSyllable(digits): Boolean`。
- `input/keyboard/t9/PinyinEngine.kt`：`candidates(digits, limit = 8): List<Candidate>`。规则：
  1. 整串命中的词（按词频）；
  2. 用 DP 把整串切成音节（目标：切分音节数少 + 各音节首字频率高），记最优切分 `s1..sn`；
  3. `n >= 2` 时，前缀 `s1..s(n-1)` 取各自最高频字，末音节 `sn` 逐个列字 → 得到「你 好/号/浩/豪…」这种候选；`n == 1` 时直接列该音节的所有字；
  4. 末尾永远补一个「原样输出数字」的兜底候选，避免用户卡死。
- `input/keyboard/t9/T9Composer.kt`：组合状态机（`digits`、当前候选、高亮下标），对外
  `onDigit(d)`、`onBackspace(): Boolean`、`commit(index): String?`（返回要发出去的文本）、`reset()`、`candidates()`、`compositionLabel()`（显示 `ni'hao` 这种带分隔的拼音）。

### 3.4 九键布局与页面

`KeyboardLayouts` 里把现在的 `T9` **整个换掉**（id 仍为 `t9`，保证 `SettingsStore` 里存过的值继续有效）：

```
1(标点)   2(abc)   3(def)
4(ghi)    5(jkl)   6(mno)
7(pqrs)   8(tuv)   9(wxyz)
英         ↵        空格0     ⌫
```

- 前三行各 3 键（键帽主标签大号数字、副标签小号字母，用 `Keys.t9Key(digit, letters)` 生成）。
- 第四行 4 键，权重 `[1, 1, 2, 1.4]`（手机输入法的下排就是这样，各键不等宽是正常的）。
- 新增 `KeyKind.T9_DIGIT`（不产生协议帧，由 Fragment 路由给 `T9Composer`）与 `KeyAction.PUNCT`（`1` 键打开标点面板）。
- 「英」= 现有的 `KeyAction.SWITCH_LAYOUT` → 切到 QWERTY（英文/数字/符号都在那儿），**不做老式多击字母**：那需要一套定时器，而 QWERTY 已经完整覆盖英文输入。

候选条放在键盘正上方（竖屏页里新增一层）：

```
[拼音 ni'hao] [你好] [你] [尼] [泥] …        ← HorizontalScrollView + TextView 芯片，最多 8 个
```

- `1` 键打开的标点面板复用同一条横条显示 `。 ， 、 ？ ！ ： ； 「 」 …`，选中即以 `text` 帧发出。
- 组合为空时：`空格` 发一个空格（`text`）、`↵` 发 `VK_RETURN`（`key` 帧）、`⌫` 发 `VK_BACK`（`key` 帧，也就是退回删电脑上的字）；正在组合时：`空格` 提交高亮候选、`⌫` 退掉一位数字。

### 3.5 Fragment 路由

`KeyboardInputHandler` 增加一个可选拦截器：

```kotlin
class KeyboardInputHandler(
    ...,
    /** 返回 true 表示这一事件已被处理，handler 不再转发给 session */
    private val interceptor: ((KeySpec, KeyboardView.Phase) -> Boolean)? = null,
)
```

`KeyboardFragment` 在九键布局下传入拦截器：`T9_DIGIT` / `PUNCT` / `⌫` / `空格` / `↵` 归 `T9Composer`，其余照旧走 `KeyboardSession`。全屏键盘不传拦截器，行为不变。

---

## 四、一字滑条（只做左右，没有 ↑/↓ 键）

- **删除** `input/DPadSliderView.kt`，**新增** `input/OneLineDPadView.kt`：
  - 画一条通栏圆角横条：左端 `←`、右端 `→`、正中一个凸起的小方块当把手；没有任何纵向轴，**上下滑动不产生任何事件**（这正是误触的根因）。
  - 触摸：按下点在中线左侧=←、右侧=→，**立刻触发**一次；按住 `320ms` 后按 `70ms` 连发；手指横向划过中线即切换方向并立刻补一次触发；抬起/取消即结束。左右各半区高亮。
  - 事件回调与现在同构：`onDirectionStart/Repeat/End(direction)`，`Direction` 只保留 `LEFT` / `RIGHT`。
- **↑/↓ 按键已按用户反馈删除**：一开始在滑条左端放了两个真实键帽
  （`KeyboardLayouts.TOOLBAR_ARROWS`，用 `KeyboardView` 画一行两键），用户明确表示不要。
  现在竖屏只有 ←/→（滑条），上下方向键只存在于横屏 104 键的方向区。
  因此功能栏第二行变成：`[一字滑条 weight=1] [全屏]`，滑条净得整行宽度。
- `KeyboardInputHandler` 里 `DPadSliderView.Listener` 换成 `OneLineDPadView.Listener`；方向→键码映射不变。

---

## 五、协议新增 `text` 帧（两端）

### 5.1 帧定义

```json
{"type":"text","s":"你好"}
```

- `s`：要注入的文本。服务端按 UTF-16 码元逐个以 `KEYEVENTF_UNICODE` 注入，**不走键盘布局**，因此中文、emoji（代理对）、以及需要 AltGr 的字符都能正确落字。
- 长度按协议 §2.3 的「截断而不是拒绝」原则夹到 `MaxTextLength = 256`（一个帧最多 256 个 UTF-16 码元）；客户端按 120 字符分片连发，分片边界不会切开代理对。
- 帧本身不携带按键状态，**不参与卡键保护**（`_pressed` 里不记账）。
- 加法式扩展：`Protocol.Version` 仍为 `2`，旧服务端按 §2.3 静默忽略。

### 5.2 服务端改动

| 文件 | 改动 |
| --- | --- |
| `AirTouch.Core/Protocol.cs` | `MessageKind.Text`；`ControlMessage.Text`；`text` 解析分支（放在 `key` 分支之后，缺 `s` 忽略、超长截断）；`public const int MaxTextLength = 256` |
| `AirTouch.Core/KeyboardController.cs` | `IKeyboardController.Text(string)`；`Win32KeyboardController.Text` 用 `KeyEventUnicode = 0x0004`（`wVk=0`、`wScan=码元`，一次 `SendInput` 发完整串，失败计数）；`RecordingKeyboardController.Text` 记 `text <内容>` |
| `AirTouch.Core/AirTouchServer.cs` | `case MessageKind.Text:` → `_keyboard.Text(command.Text)`；`ServerStatus.TextCommands` 计数 |
| `AirTouch.Server/MainWindow.xaml(.cs)` | 计数器行加 `· 文本 N` |
| `AirTouch.SmokeTest/Program.cs` | `text` 解析断言（正常 / 缺 `s` / 超长截断 / 未知字段忽略）；E2E 断言 `RecordingKeyboardController` 收到 `text 你好`；真实注入用**一个空格**（U+0020）验证 `SendInput` 被接受且 `SendInputFailures == 0` |
| `docs/PROTOCOL.md` | 新增 §2.7 文本（`text`）：字段表、为什么必须新增（中文无法用虚拟键码表达）、Unicode 注入机制、长度夹取、追加式兼容说明、以及限制（用 Raw Input/DirectInput 直接读扫描码的游戏看不到这类注入） |

---

## 六、全屏微型触控板：从「不可能打开」到「一定能用」

### 6.1 现状与问题

现在是「把气泡作为容器的绝对定位子 View，用 `translationY` 抬到容器上方」，所以必须 `clipChildren=false`，而且气泡的可见高度取决于容器在屏幕上的位置：360dp 高的横屏上，气泡顶边会落到屏幕外约 20dp；容器被拖到更靠上时会被裁掉更多。这套机制天然会「打开但看不见」。

另外「点了手形图标没有任何反馈」这一现象，光靠读代码无法区分是「点击没进到按钮」还是「气泡跑到屏幕外」——两者在界面上都是「什么都没发生」。

### 6.2 改法

1. **气泡改成容器里的正常子 View**，放进容器的竖向 `LinearLayout`，右对齐（`layout_gravity="end"`）、位置就在标题条正上方：

   ```
   LinearLayout(vertical)
     mini_touchpad_bubble   ← layout_gravity=end，正好在手形图标上方
     keyboard_header        ← 取消全屏 / 提示 / 手形图标
     keyboard_view_fullscreen
   ```

   于是「打开气泡」= 容器自己向上长高，不再有 `translationY`、不再需要 `clipChildren=false`、不再依赖容器在屏幕上的位置，**屏幕外这个失败模式被结构性消除**。XML 里的 `clipChildren`/`clipToPadding`、`ControlActivity` 里的 `extraTopLimit`（连带 `DraggableKeyboardContainer` 的那个属性）一并删除。

2. **保证总高不超出屏幕**：打开时把 `keyboard_view_fullscreen` 的高度夹到
   `可用高度 - 气泡 - 标题条 - 内边距 - 间隙`（104 键是按比例缩放的，压矮只是键变矮，不会错位），
   关闭时恢复自然高度（`layout.heightRatio`）。**只压键盘、不压气泡**：气泡里的 `TouchpadView` 是
   固定尺寸，压小会真的削掉可拖动的面积；键盘下限 120dp（再矮键帽就点不准了），而正常横屏
   （≥320dp 高）上算出来的预算总有 140dp 以上，因此下限只是安全网、碰不到。
3. **入口做明显**：手形图标 40dp → 44dp，并加上「点它开触控板」的一次性提示（进入全屏时 `showHint` 一次，每次会话只提示一次）；点开后按钮变强调色（已有）。
4. **可诊断**：点击处理里先 `showHint("微型触控板已打开")` 再放气泡。这样万一还有问题，用户看到的是明确的两分结果：**有提示但没气泡 = 放置问题；连提示都没有 = 点击没进到按钮**，下一轮就能直接定位。若属于后者，回退方案是把拖动手柄从整条标题条收窄到中间的提示文字（`keyboard_drag_hint`）。
5. 拖拽时气泡自动跟随（它是容器的子 View，本来就跟）；拖动不改变可用高度，因此不需要重新夹取。

---

## 七、纯文本输入框（大段文字的补充入口）

- 新增 `res/layout/dialog_send_text.xml`：多行 `EditText` + 实时字数 + 「发送」/「取消」，并写明这是给大段文字用的补充通道。
- 入口两处：竖屏功能栏第一行新增「文本」小按钮；全屏标题条在手形图标旁新增「文本」小按钮。两者都走 `ControlBridge.showTextSender()`。
- 发送：按 120 字符分片（不切开代理对）依次发 `text` 帧，完成后提示「已发送 N 字」。
- **这里刻意使用系统输入法**：它是「我要贴一段话/打一大段」的逃生通道，黏贴和多行编辑都靠系统能力。自绘键盘本身仍然**从不**调起系统输入法——这一点在代码注释和本文里都写明，避免以后被误改成全局行为。

---

## 八、逐文件清单

**新增**

```
tools/build-pinyin-dict.mjs                 词库生成（联网一次，产物入库）
tools/verify-pinyin-dict.mjs                词库自洽性校验（本轮唯一可自动化验证）
android/app/src/main/assets/pinyin_dict.txt 生成的词库（提交）
android/.../input/OneLineDPadView.kt        一字滑条
android/.../input/keyboard/t9/PinyinDictionary.kt
android/.../input/keyboard/t9/PinyinEngine.kt
android/.../input/keyboard/t9/T9Composer.kt
android/.../control/CandidateBar.kt         候选条/标点面板（TextView 芯片，不进自定义绘制）
android/app/src/main/res/layout/dialog_send_text.xml
docs/PLAN-KEYBOARD-V2.md                    本文
```

**修改**

```
android/.../input/KeyboardView.kt           多指支持；isStickyModifier
android/.../input/keyboard/KeySpec.kt       sticky；T9_DIGIT；t9Key 工厂；KeyAction.PUNCT
android/.../input/keyboard/KeyboardLayouts.kt  Ctrl 粘滞；T9 换成拼音九键；删掉 TOOLBAR_ARROWS（↑↓）
android/.../input/KeyboardInputHandler.kt   一字滑条 Listener；interceptor
android/.../control/KeyboardFragment.kt     组合状态、候选条、文本按钮、提示文案
android/.../control/CandidateBar.kt         候选条/标点面板（显隐落在整条 strip 上，不能只改子 View）
android/.../control/ControlActivity.kt      气泡放置、文本发送入口、全屏提示、移除 extraTopLimit
android/.../control/ControlBridge.kt        sendUnicodeText / showTextSender
android/.../net/Protocol.kt                 TYPE_TEXT / buildText / MaxTextLength（客户端叫 sendUnicodeText，
                                            避免和已有的「发原始 JSON」的 sendText 撞名）
android/.../net/AirTouchClient.kt           sendUnicodeText + 按 120 码元分片（不切开代理对）
android/.../settings/SettingsStore.kt       无改动（t9 布局 id 已经能持久化）
android/.../input/DraggableKeyboardContainer.kt  删掉 extraTopLimit
android/app/src/main/res/layout/fragment_keyboard.xml      候选条 + 一字滑条 + ↑↓ + 文本按钮
android/app/src/main/res/layout/overlay_keyboard_fullscreen.xml  气泡挪进 LinearLayout + 标题条加「文本」
android/app/src/main/res/values/strings.xml                 新增/改写键盘相关文案
android/app/src/main/res/values/dimens.xml                  文本按钮宽度、↑↓ 宽度、候选条标号宽度、手形图标 44dp
android/app/src/main/res/values/colors.xml                  无改动（复用 at_primary / at_text_dim / at_error）
server/AirTouch.Core/Protocol.cs
server/AirTouch.Core/KeyboardController.cs
server/AirTouch.Core/AirTouchServer.cs
server/AirTouch.Server/MainWindow.xaml(.cs)
server/AirTouch.SmokeTest/Program.cs
docs/PROTOCOL.md                            §2.7 text
.gitignore                                  tools/pinyin-source/
```

**删除**

```
android/.../input/DPadSliderView.kt        被 OneLineDPadView 取代
```

---

## 九、验证

1. `tools/verify-pinyin-dict.mjs`：字键自洽、`64426 → 你好`、常用字覆盖率、文件大小。
2. Android：`:app:assembleDebug`（沿用现有命令：`JAVA_HOME=.jdk/jdk-17.0.21+9`、`GRADLE_USER_HOME=.gradle-home`、`GRADLE_RO_DEP_CACHE=C:\Users\kks\.gradle\caches`、离线、直接调 Gradle 8.7 启动器），要求无新增警告。
3. 服务端三个项目分别 `dotnet build` + 跑 `AirTouch.SmokeTest`（基线：276 通过 / 2 失败，两个失败都是沙箱无权写 `%APPDATA%\Microsoft\Crypto` 导致 wss 起不来，与本轮无关）。
4. 手工清单（没有设备，只能列给用户过一遍）：
   - 九键：打 `64426` 出现「你好」、空格上屏、`⌫` 退位、电脑收到中文；`1` 出标点面板；`英` 切到 QWERTY。
   - Shift：一根手指按住 Shift、另一根敲 H，电脑打出大写 H；按住 Ctrl 再敲 C 仍然能复制（粘滞路径未回归）。
   - 一字滑条：上下滑动**不产生任何事件**；左右各自立刻移动、按住连发；整个滑条有整行宽度，不挤。
   - 全屏：点手形图标气泡出现在标题条上方且完整可见；拖动键盘气泡跟随；再点收起；「文本」按钮能发一段中文过去。

---

## 十、风险与回退

| 风险 | 回退 |
| --- | --- |
| 词库数据源取不到（生成阶段需要联网一次） | 缩减为手工可核对的常用字子集；词库是**纯数据**，后续替换不需要改任何代码 |
| 拼音引擎的整词候选不够聪明（用户已预判） | 已有两条兜底：候选条永远补「原样数字」候选 + 顶部「纯文本输入框」 |
| 多指改造是这轮最容易出回归的地方 | 阈值、滑选、连发参数一律不动；单指路径保持逐字节等价；先按单指语义逐条比对再谈多指 |
| Unicode 注入对读原始扫描码的程序（部分游戏）无效 | 已在 §2.7 写明限制；后续可加「剪贴板 + Ctrl+V」通道 |
| 全屏气泡的真实根因未在设备上确认 | §6.4 的提示把问题一分为二（放置问题 / 点击问题），并给出收窄拖动手柄的回退方案 |
| 烟测里的真实 Unicode 注入会往当前焦点窗口打一个空格 | 与既有「真实鼠标 live 测试会移动真实光标」同级别，且刻意只用空格 |
