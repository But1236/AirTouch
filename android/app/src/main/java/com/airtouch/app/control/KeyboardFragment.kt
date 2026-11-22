package com.airtouch.app.control

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.airtouch.app.R
import com.airtouch.app.databinding.FragmentKeyboardBinding
import com.airtouch.app.input.KeyboardInputHandler
import com.airtouch.app.input.KeyboardView
import com.airtouch.app.input.keyboard.KeyAction
import com.airtouch.app.input.keyboard.KeySpec
import com.airtouch.app.input.keyboard.KeyboardLayouts
import com.airtouch.app.input.keyboard.KeyboardSession
import com.airtouch.app.input.keyboard.ModifierNames
import com.airtouch.app.input.keyboard.Vk
import com.airtouch.app.input.keyboard.t9.PinyinDictionary
import com.airtouch.app.input.keyboard.t9.PinyinEngine
import com.airtouch.app.input.keyboard.t9.T9Composer
import com.airtouch.app.net.AirTouchClient
import com.airtouch.app.settings.SettingsStore

/**
 * 竖屏键盘页。
 *
 * 它自己不含任何协议知识：所有按键都经过 [KeyboardSession] → [ControlBridge.sendKey]，
 * 中文候选则经过 [T9Composer] → [ControlBridge.sendUnicodeText]（协议 §2.7）。这一页负责的是
 * 「布局、功能栏、候选条、提示文案」这几件界面上的事，以及一个关键的生命周期动作：
 * **不可见时松开全部按键**（否则粘滞锁定的 Ctrl 会留在电脑上）。
 *
 * ## 九键为什么需要一个拦截器
 * 九键的数字键**不发按键帧**（[KeySpec.isT9Digit]）：它们要进拼音组合状态机。拦截器放在
 * [KeyboardInputHandler] 之前，因此「哪些键归中文输入、哪些键照旧发给电脑」这件事只有这一处判断。
 * 关键在于**接管是粘住的**：一旦某次按下被接管，它后续的抬起/连发也一定被吞掉 ——
 * 否则会把一个「从没按下过」的键的抬起事件发给电脑。
 *
 * 横屏全屏键盘在 ControlActivity 的覆盖层里，用同一个 [KeyboardInputHandler] 和
 * [com.airtouch.app.input.KeyboardView]，因此两种形态的手感、连发、粘滞键行为完全一致 ——
 * 全屏键盘不需要拦截器（它是纯英文键盘），传 null 即可。
 */
class KeyboardFragment : Fragment() {

    private var _binding: FragmentKeyboardBinding? = null
    private val binding: FragmentKeyboardBinding
        get() = _binding ?: throw IllegalStateException("KeyboardFragment 的视图尚未创建")

    private var bridge: ControlBridge? = null
    private var settings: SettingsStore? = null

    /** 当前页是否可见（由 ControlActivity 在 show/hide 时同步） */
    private var screenVisible = false

    private var layoutId: String = KeyboardLayouts.DEFAULT_ID

    /** 本页自己的一份粘滞/按住状态；发送目标在调用时才从 [bridge] 取，因此 attach 前也不会崩 */
    private val session = KeyboardSession(
        send = { vk, down, extended -> bridge?.sendKey(vk, down, extended) },
    )

    private val handler = KeyboardInputHandler(
        session = session,
        onActionRequested = { action -> onAction(action) },
        onModifierChanged = { _, _ -> updateHint() },
        interceptor = { key, phase -> intercept(key, phase) },
    )

    /** 九键拼音的组合状态机 */
    private val composer = T9Composer()

    /** 已被九键接管的键：它们的抬起/连发必须继续吞掉，不能漏给 KeyboardSession */
    private val captured = HashSet<KeySpec>()

    /** 标点面板是否展开（复用候选条显示标点） */
    private var punctPanelOpen = false

    private var candidateBar: CandidateBar? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentKeyboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.keyboardView.listener = handler.keyboardListener
        binding.dpadSlider.listener = handler.dpadListener

        candidateBar = CandidateBar(
            strip = binding.candidateStrip,
            compositionView = binding.candidateComposition,
            scroller = binding.candidateScroller,
            chipContainer = binding.candidateChips,
            onPick = { index, text -> onChipPicked(index, text) },
        )

        layoutId = settings?.keyboardLayout ?: KeyboardLayouts.DEFAULT_ID
        applyLayout()

        binding.buttonEsc.setOnClickListener { handler.tapKey(KeyboardLayouts.ToolKeys.ESC) }
        binding.buttonDelete.setOnClickListener { handler.tapKey(KeyboardLayouts.ToolKeys.DELETE) }
        binding.buttonPrtsc.setOnClickListener { handler.tapKey(KeyboardLayouts.ToolKeys.PRTSC) }
        binding.buttonLayoutSwitch.setOnClickListener { switchLayout() }
        binding.buttonTextSender.setOnClickListener { bridge?.showTextSender() }
        binding.buttonFullscreen.setOnClickListener {
            bridge?.setKeyboardFullscreen(true)
        }

        // 词库是几十 KB 的文本资源，丢到后台线程解析；加载完成前键盘照常能用，只是没有候选
        PinyinDictionary.loadAsync(requireContext()) { dictionary ->
            composer.attach(dictionary)
            renderStrip()
        }

        updateHint()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // 视图没了就再也收不到 ACTION_UP，必须在这里把还按着的键全部松开
        handler.releaseAll()
        binding.keyboardView.listener = null
        binding.dpadSlider.listener = null
        candidateBar = null
        _binding = null
    }

    // ---------------- 由 ControlActivity 调用（主线程） ----------------

    fun attach(bridge: ControlBridge, settings: SettingsStore) {
        this.bridge = bridge
        this.settings = settings
        layoutId = settings.keyboardLayout
        val b = _binding
        if (b != null) {
            applyLayout()
            updateHint()
        }
    }

    /**
     * 由 ControlActivity 在 show/hide 时同步；连接状态变化时也会调用（提示文案要跟着变）。
     *
     * 隐藏时**必须**松开全部按键：这一页被 hide 之后是 GONE，收不到 ACTION_UP，
     * 而粘滞锁定的修饰键是「手机内存里的状态 + 电脑上真的按着」两件事，只清前者会让后者卡住。
     */
    fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        if (!visible) {
            // 视图被隐藏后 ACTION_UP 可能永远不来（多指操作时用另一根手指切页就会这样）：
            // 既要把还按着的键抬起来，也要把「按下高亮」的视觉状态清掉
            _binding?.keyboardView?.cancelGesture()
            handler.releaseAll()
            resetComposition()
        }
        val b = _binding
        if (b != null) updateHint()
    }

    /** 连接状态变化后刷新提示（未连接时按键不会发出去，要明确告诉用户） */
    fun refreshHint() {
        val b = _binding
        if (b != null) updateHint()
    }

    /**
     * 清掉按键记账并松开一切。
     *
     * 连接断开 / 界面离开前台时调用。**必须**清掉「修饰键已经按着」这份记账：重连之后电脑端
     * 那两个键其实已经不存在了，手机若还认为 Ctrl 按着，下一次 `Ctrl+C` 只会发出一个 `C`——
     * 按键看起来「随机失灵」，是最难排查的一类现象。
     */
    fun resetKeyState() {
        handler.releaseAll()
        resetComposition()
        val b = _binding
        if (b != null) updateHint()
    }

    // ---------------- 九键拦截器 ----------------

    /**
     * 九键数字键与「组合中的 ⌫ / 空格」在这里被接走。
     *
     * @return true = 这次事件已经处理完，不要发给电脑
     */
    private fun intercept(key: KeySpec, phase: KeyboardView.Phase): Boolean {
        if (!isT9Layout()) return false

        if (key.isT9Digit) {
            when (phase) {
                KeyboardView.Phase.DOWN -> {
                    captured.add(key)
                    val digit = key.label.toIntOrNull()
                    if (digit != null) composer.onDigit(digit)
                    renderStrip()
                }
                KeyboardView.Phase.UP -> captured.remove(key)
                KeyboardView.Phase.REPEAT -> Unit
            }
            return true
        }

        // ⌫：组合中退一位数字；组合为空时放行给 KeyboardSession（删电脑上的字）
        if (key.vk == Vk.BACKSPACE && composer.composing) {
            when (phase) {
                KeyboardView.Phase.DOWN -> {
                    captured.add(key)
                    composer.onBackspace()
                    renderStrip()
                }
                KeyboardView.Phase.REPEAT -> {
                    composer.onBackspace()
                    renderStrip()
                }
                KeyboardView.Phase.UP -> captured.remove(key)
            }
            return true
        }

        // 空格：组合中上屏第一个候选（最高频的那个）；组合为空时放行（打一个空格）
        if (key.vk == Vk.SPACE && composer.composing) {
            when (phase) {
                KeyboardView.Phase.DOWN -> {
                    captured.add(key)
                    commitCandidate(0)
                }
                KeyboardView.Phase.UP -> captured.remove(key)
                KeyboardView.Phase.REPEAT -> Unit
            }
            return true
        }

        // 已经被接管的键，后续阶段一律继续吞掉：绝不能把一个「从没按下过」的键的抬起发给电脑
        if (captured.contains(key)) {
            if (phase == KeyboardView.Phase.UP) captured.remove(key)
            return true
        }

        return false
    }

    // ---------------- 内部 ----------------

    private fun isT9Layout(): Boolean = KeyboardLayouts.byId(layoutId).id == KeyboardLayouts.T9.id

    private fun onAction(action: KeyAction) {
        when (action) {
            KeyAction.SWITCH_LAYOUT -> switchLayout()
            KeyAction.FULLSCREEN -> bridge?.setKeyboardFullscreen(true)
            KeyAction.PUNCT -> togglePunctPanel()
            KeyAction.NONE -> Unit
        }
    }

    private fun switchLayout() {
        // 换布局会让「按下的键」失去对应关系，先把当前按键全部松开
        handler.releaseAll()
        layoutId = KeyboardLayouts.nextPortraitId(layoutId)
        settings?.keyboardLayout = layoutId
        applyLayout()
        updateHint()
    }

    private fun applyLayout() {
        binding.keyboardView.layout = KeyboardLayouts.byId(layoutId)
        binding.buttonLayoutSwitch.text = getString(
            // 按钮上写「按下去会切到哪个布局」：写目标比写现状更好懂
            if (isT9Layout()) {
                R.string.keyboard_switch_to_qwerty
            } else {
                R.string.keyboard_switch_to_t9
            }
        )
        renderStrip()
    }

    private fun togglePunctPanel() {
        punctPanelOpen = !punctPanelOpen
        renderStrip()
    }

    private fun resetComposition() {
        captured.clear()
        punctPanelOpen = false
        composer.reset()
        renderStrip()
    }

    /** 点候选条上第 [index] 个芯片：标点面板里是插入标点，拼音组合里是上屏候选 */
    private fun onChipPicked(index: Int, text: String) {
        if (punctPanelOpen) {
            sendText(text)
            return
        }
        commitCandidate(index)
    }

    private fun commitCandidate(index: Int) {
        val text = composer.commit(index) ?: return
        sendText(text)
        renderStrip()
    }

    private fun sendText(text: String) {
        bridge?.sendUnicodeText(text)
    }

    /**
     * 重画候选条。
     *
     * 没在组合时整条隐藏（标点面板除外）：竖屏上每一 dp 的竖向空间都很贵，
     * 空着的候选条会把键帽挤矮。
     */
    private fun renderStrip() {
        val bar = candidateBar ?: return
        val visible = isT9Layout() && (composer.composing || punctPanelOpen)
        bar.setVisible(visible)
        if (!visible) return

        if (punctPanelOpen) {
            bar.show(getString(R.string.keyboard_t9_punct), PUNCTUATION.map { CandidateBar.Chip(it) })
            return
        }
        when {
            !composer.isReady -> bar.show(
                getString(R.string.keyboard_t9_loading), emptyList(), labelDim = true
            )
            composer.isDictionaryBroken -> bar.show(
                getString(R.string.keyboard_t9_broken), emptyList(), labelDim = true
            )
            else -> {
                val candidates = composer.candidates()
                if (candidates.isEmpty()) {
                    bar.show(
                        getString(R.string.keyboard_t9_no_candidate), emptyList(), labelDim = true
                    )
                } else {
                    bar.show(
                        label = composer.compositionLabel(),
                        items = candidates.map {
                            CandidateBar.Chip(it.text, dim = it.kind == PinyinEngine.Kind.RAW)
                        },
                    )
                }
            }
        }
    }

    private fun updateHint() {
        val b = _binding ?: return

        val builder = StringBuilder(
            getString(
                if (isT9Layout()) R.string.keyboard_hint_t9 else R.string.keyboard_hint_qwerty
            )
        )

        ModifierNames.statusText(requireContext(), session)?.let {
            builder.append('\n').append(it)
        }
        if (bridge?.connectionState() != AirTouchClient.State.CONNECTED) {
            builder.append('\n').append(getString(R.string.keyboard_hint_offline))
        }

        b.keyboardHint.text = builder.toString()
        // 有修饰键生效时用主色写：粘滞锁定是「看不见的模态状态」，必须一眼能发现
        b.keyboardHint.setTextColor(
            ContextCompat.getColor(
                requireContext(),
                if (session.activeModifiers().isEmpty()) R.color.at_text_dim else R.color.at_primary
            )
        )
    }

    companion object {
        /** 与 ControlActivity 里 [ControlActivity] 的页签 tag 保持一致 */
        const val TAG = "keyboard"

        /** 标点面板的候选（都是中文标点，用 text 帧发出去，与输入法上屏同一个通道） */
        private val PUNCTUATION = listOf(
            "。", "，", "、", "？", "！", "：", "；", "“", "”", "‘", "’",
            "（", "）", "《", "》", "…", "—", "·", "～", "【", "】",
        )
    }
}
