package com.airtouch.app.control

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.airtouch.app.R
import com.airtouch.app.databinding.FragmentDiagnosticsBinding
import com.airtouch.app.net.AirTouchClient
import com.airtouch.app.net.ServerDiagReport
import com.airtouch.app.settings.SettingsStore

/**
 * 「诊断」页：一键跑画圆测试，并直接给出「卡在哪一层」的结论。
 *
 * 界面上刻意只放三件事：怎么发（绝对/相对、频率、每圈时长）、现在发到哪了、电脑回传的统计。
 * 用户只需要看着 PC 上的光标，测试结束后看结论即可。
 */
class DiagnosticsFragment : Fragment(), CircleTestRunner.Listener {

    private var binding: FragmentDiagnosticsBinding? = null
    private var client: AirTouchClient? = null
    private var bridge: ControlBridge? = null
    private var settings: SettingsStore? = null
    private var runner: CircleTestRunner? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    fun attach(client: AirTouchClient, bridge: ControlBridge, settings: SettingsStore) {
        this.client = client
        this.bridge = bridge
        this.settings = settings
        if (runner == null) {
            runner = CircleTestRunner(client, this)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val b = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        binding = b
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val b = binding ?: return
        val s = settings

        // 恢复上次的选择
        if (s != null) {
            (if (s.diagUseAbsolute) b.radioAbsolute else b.radioRelative).isChecked = true
            when (s.diagTargetHz) {
                60 -> b.radioHz60.isChecked = true
                144 -> b.radioHz144.isChecked = true
                else -> b.radioHz100.isChecked = true
            }
            when (s.diagCircleMs) {
                1200 -> b.radioMs1200.isChecked = true
                4800 -> b.radioMs4800.isChecked = true
                else -> b.radioMs2400.isChecked = true
            }
        }

        b.buttonRunTest.setOnClickListener {
            if (runner?.isRunning == true) {
                runner?.stop()
                b.buttonRunTest.text = getString(R.string.diag_start)
            } else {
                startTest()
            }
        }

        b.buttonResetTrust.setOnClickListener { resetTrust() }

        b.trustInfo.text = getString(R.string.diag_trust_info, s?.lastSid?.ifEmpty { "—" } ?: "—")

        updateIdleText()
    }

    fun setScreenVisible(visible: Boolean) {
        // 离开诊断页就停掉测试，避免「看不见的地方还在动光标」
        if (!visible) runner?.stop()
        binding?.buttonRunTest?.text = getString(R.string.diag_start)
    }

    /** 连接状态变化时由 Activity 调用 */
    fun onConnectionState(state: AirTouchClient.State) {
        val b = binding ?: return
        if (state != AirTouchClient.State.CONNECTED && runner?.isRunning == true) {
            runner?.stop()
            b.buttonRunTest.text = getString(R.string.diag_start)
        }
        // 按钮**始终保持可点**：未连接时点它只会给出「尚未连接」的提示。
        // 之前是按连接状态 disable，深色主题下一个 20% 透明度的按钮看起来就像"这页没有按钮"。
        b.buttonRunTest.isEnabled = true
        if (state != AirTouchClient.State.CONNECTED) {
            b.statusText.text = getString(R.string.diag_need_connection)
        }
    }

    /** PC 回传的统计结果（由 Activity 转发） */
    fun onServerReport(report: ServerDiagReport) {
        val b = binding ?: return
        mainHandler.post {
            b.resultText.text = formatReport(report)
            b.buttonRunTest.text = getString(R.string.diag_start)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        runner?.stop()
        binding = null
    }

    private fun startTest() {
        val b = binding ?: return
        val c = client ?: return
        val r = runner ?: return
        val s = settings

        if (c.state != AirTouchClient.State.CONNECTED) {
            bridge?.showHint(getString(R.string.diag_need_connection))
            return
        }

        val absolute = b.radioAbsolute.isChecked
        val targetHz = when {
            b.radioHz60.isChecked -> 60
            b.radioHz144.isChecked -> 144
            else -> 100
        }
        val circleMs = when {
            b.radioMs1200.isChecked -> 1200
            b.radioMs4800.isChecked -> 4800
            else -> 2400
        }

        s?.diagUseAbsolute = absolute
        s?.diagTargetHz = targetHz
        s?.diagCircleMs = circleMs

        val width = c.session?.screenWidth ?: 0
        val height = c.session?.screenHeight ?: 0
        if (width <= 0 || height <= 0) {
            bridge?.showHint(getString(R.string.diag_no_screen_size))
        }

        b.resultText.text = getString(R.string.diag_waiting_report)
        b.buttonRunTest.text = getString(R.string.diag_stop)

        r.start(
            mode = if (absolute) "abs" else "rel",
            targetHz = targetHz,
            circleMs = circleMs,
            width = width,
            height = height
        )
    }

    private fun resetTrust() {
        val s = settings ?: return
        val count = s.serverPinCount()
        if (count == 0) {
            bridge?.showHint(getString(R.string.diag_trust_empty))
            return
        }

        val cleared = com.airtouch.app.net.PinnedTrust.resetAll(s)
        bridge?.showHint(getString(R.string.diag_trust_cleared, cleared))
    }

    // ---------------- CircleTestRunner.Listener（后台线程） ----------------

    override fun onCircleTestStarted(
        planned: Int,
        mode: String,
        targetHz: Int,
        circleMs: Int,
        width: Int,
        height: Int
    ) {
        mainHandler.post {
            val b = binding ?: return@post
            b.statusText.text = getString(
                R.string.diag_running,
                planned,
                targetHz,
                circleMs,
                if (mode == "abs") getString(R.string.diag_mode_absolute) else getString(R.string.diag_mode_relative)
            )
            val radius = height / 2
            val displayHz = client?.session?.displayHz ?: 0
            b.planText.text = getString(
                R.string.diag_plan,
                width,
                height,
                radius,             // 半径 = 高/2（圆因此同时相切上、下边界）
                height / 2,         // 圆心 y
                width - radius      // 右圆圆心 x（相切右边界）
            ) + if (displayHz > 0) {
                // 屏幕刷新率是"看起来流畅"的物理上限，写出来用户才知道该选哪个频率
                "\n" + getString(R.string.diag_plan_display, displayHz)
            } else {
                ""
            }
        }
    }

    override fun onCircleTestProgress(circle: Int, sent: Int, planned: Int, actualHz: Double, maxSendGapMs: Int) {
        mainHandler.post {
            val b = binding ?: return@post
            b.statusText.text = getString(
                R.string.diag_progress,
                circle + 1,
                sent,
                planned,
                actualHz,
                maxSendGapMs
            )
        }
    }

    override fun onCircleTestFinished(
        sent: Int,
        planned: Int,
        elapsedMs: Long,
        maxSendGapMs: Int,
        firstSeq: Int,
        lastSeq: Int
    ) {
        mainHandler.post {
            val b = binding ?: return@post
            b.statusText.text = getString(R.string.diag_sent_done, sent, planned, elapsedMs, maxSendGapMs)
            b.buttonRunTest.text = getString(R.string.diag_start)
            b.resultText.text = getString(R.string.diag_waiting_report)
        }
    }

    override fun onCircleTestAborted(reason: String) {
        mainHandler.post {
            val b = binding ?: return@post
            b.statusText.text = reason
            b.buttonRunTest.text = getString(R.string.diag_start)
        }
    }

    // ---------------- 文本 ----------------

    private fun updateIdleText() {
        val b = binding ?: return
        b.statusText.text = getString(R.string.diag_idle)
        b.resultText.text = getString(R.string.diag_result_hint)
    }

    private fun formatReport(r: ServerDiagReport): String {
        val sb = StringBuilder()
        sb.append(getString(R.string.diag_report_mode, r.mode, r.targetHz, r.planned))
        sb.append('\n')
        sb.append(
            getString(
                R.string.diag_report_client,
                r.clientSent,
                r.clientElapsedMs,
                r.clientMaxSendGapMs
            )
        )
        sb.append('\n')
        sb.append(
            getString(
                R.string.diag_report_server,
                r.received,
                r.lost,
                r.duplicated,
                r.outOfOrder
            )
        )
        sb.append('\n')
        sb.append(getString(R.string.diag_report_gap, r.maxGapMs, r.meanGapMs, r.sendInputFailures))
        sb.append('\n')
        // 眼睛看到的那个数字：电脑侧光标每秒真正变化了几次（发送频率 / 到达频率都不等于它）
        sb.append(
            if (r.cursorSampleValid) {
                getString(
                    R.string.diag_report_cursor,
                    r.cursorHz,
                    r.cursorUpdates,
                    r.cursorMaxGapMs,
                    r.displayHz
                )
            } else {
                getString(R.string.diag_report_cursor_unknown)
            }
        )
        sb.append('\n')
        sb.append(getString(R.string.diag_report_actual, r.actualHz, r.arrivalPercent, r.missing))
        sb.append('\n')
        sb.append("→ ").append(r.verdict())
        return sb.toString()
    }

    /** 回到本页时刷新状态（按钮始终可点，未连接时点它会提示原因） */
    override fun onResume() {
        super.onResume()
        val b = binding ?: return
        b.buttonRunTest.isEnabled = true
        if (client?.state != AirTouchClient.State.CONNECTED) {
            b.statusText.text = getString(R.string.diag_need_connection)
        }
    }
}
