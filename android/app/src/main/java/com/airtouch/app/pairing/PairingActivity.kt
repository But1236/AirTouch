package com.airtouch.app.pairing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.airtouch.app.R
import com.airtouch.app.control.ControlActivity
import com.airtouch.app.databinding.ActivityPairingBinding
import com.airtouch.app.databinding.DialogManualConnectBinding
import com.airtouch.app.net.PairingUri
import com.airtouch.app.net.PinnedTrust
import com.airtouch.app.settings.SettingsStore
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 配对界面（launcher）。
 *
 * 职责：
 * 1. CameraX 预览 + ImageAnalysis(STRATEGY_KEEP_ONLY_LATEST) → [QrCodeAnalyzer] 扫码；
 * 2. 用 [PairingUri.parse] 校验二维码内容，非法内容忽略并提示；
 * 3. 合法则停止相机、保存 host/token、跳转 [ControlActivity]；
 * 4. 「手动输入地址」对话框走同一个 [PairingUri.parse]/[PairingUri.fromParts] 校验路径；
 * 5. 相机权限被拒时给出中文说明 + 「重试授权」「打开系统设置」两个按钮；
 * 6. 显示「上次连接」的 host。
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPairingBinding
    private lateinit var settings: SettingsStore

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var analysisHandlerThread: HandlerThread? = null
    private var analysisHandler: Handler? = null
    private var cameraStarted = false

    /** 已经跳转过就不重复处理（扫码回调可能连续触发） */
    private var navigated = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                hidePermissionPanel()
                startCamera()
            } else {
                showPermissionDenied()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPairingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        settings = SettingsStore(this)

        binding.buttonManualInput.setOnClickListener { showManualInputDialog() }
        binding.buttonResetPin.setOnClickListener { resetCertificatePins() }
        binding.buttonReconnect.setOnClickListener { reconnectLast() }
        binding.buttonRetryPermission.setOnClickListener { requestCamera() }
        binding.buttonOpenSettings.setOnClickListener { openAppSettings() }

        updateLastConnected()
        ensurePermissionOrStart()
    }

    override fun onResume() {
        super.onResume()
        // 从控制界面返回（比如服务端断开）时允许再次扫码。
        // 必须在「是否启动相机」的判断之前复位：ControlActivity 断开后会 finish 回来，
        // 此时 navigated 若仍是 true，相机就永远不会重新启动，用户无法重新扫码。
        navigated = false
        // 从系统设置返回后，如果权限已给，直接把相机拉起来
        if (hasCameraPermission() && !cameraStarted) {
            startCamera()
        }
    }

    override fun onPause() {
        super.onPause()
        stopCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCamera()
        analysisHandlerThread?.quitSafely()
        analysisHandlerThread = null
        analysisHandler = null
        cameraExecutor?.shutdown()
        cameraExecutor = null
    }

    // ---------------- 权限 ----------------

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensurePermissionOrStart() {
        if (hasCameraPermission()) {
            hidePermissionPanel()
            startCamera()
        } else {
            requestCamera()
        }
    }

    private fun requestCamera() {
        requestCameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun showPermissionDenied() {
        binding.permissionMessage.text = getString(R.string.perm_camera_denied)
        binding.permissionPanel.visibility = View.VISIBLE
        binding.buttonRetryPermission.setText(R.string.perm_camera_retry)
        binding.buttonOpenSettings.visibility = View.VISIBLE
    }

    private fun hidePermissionPanel() {
        binding.permissionPanel.visibility = View.GONE
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "打开应用设置失败", e)
            toast(getString(R.string.perm_camera_open_failed))
        }
    }

    // ---------------- 相机 ----------------

    private fun startCamera() {
        if (cameraStarted || navigated) return
        cameraStarted = true

        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider

                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(binding.previewView.surfaceProvider)

                val analysis = ImageAnalysis.Builder()
                    // 只保留最新帧，避免扫码时排队积压
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                if (analysisHandler == null) {
                    val thread = HandlerThread("airtouch-qr")
                    thread.start()
                    analysisHandlerThread = thread
                    analysisHandler = Handler(thread.looper)
                }
                if (cameraExecutor == null) {
                    cameraExecutor = Executors.newSingleThreadExecutor { r ->
                        val t = Thread(r, "airtouch-qr-exec")
                        t.isDaemon = true
                        t
                    }
                }

                val handler = analysisHandler
                val executor = cameraExecutor
                if (handler == null || executor == null) {
                    cameraStarted = false
                    return@addListener
                }

                val analyzer = QrCodeAnalyzer(
                    callbackHandler = handler,
                    onDecoded = { raw -> onQrDecoded(raw) },
                    onError = { e -> Log.w(TAG, "扫码分析异常", e) }
                )

                try {
                    // 刻意使用「已确定存在」的 setAnalyzer(Executor, Analyzer) 重载
                    // （CameraX 1.3 里 setAnalyzer(Analyzer) 已被标记废弃）
                    analysis.setAnalyzer(executor, analyzer)
                } catch (e: Exception) {
                    Log.w(TAG, "setAnalyzer 失败", e)
                    cameraStarted = false
                    return@addListener
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                hidePermissionPanel()
            } catch (e: Exception) {
                cameraStarted = false
                Log.w(TAG, "启动相机失败", e)
                toast(getString(R.string.perm_camera_denied))
                showPermissionDenied()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        cameraStarted = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "停止相机失败", e)
        }
    }

    // ---------------- 扫码结果 ----------------

    private fun onQrDecoded(raw: String) {
        mainHandler.post {
            if (navigated || isFinishing || isDestroyed) return@post
            val uri = PairingUri.parse(raw)
            if (uri == null) {
                // 非法内容：继续扫描，只做一个轻提示（协议 §1.1 要求原样使用，不做猜测修补）
                toast(getString(R.string.pairing_invalid_qr))
                return@post
            }
            navigated = true
            stopCamera()
            persistAndConnect(uri)
        }
    }

    private fun persistAndConnect(uri: PairingUri) {
        settings.lastHost = uri.host
        settings.lastPort = uri.port
        // token 持久化：见 SettingsStore 的说明（一次性配对票据，仅用于展示/排错）
        settings.lastToken = uri.token
        if (!uri.sid.isNullOrEmpty()) settings.lastSid = uri.sid
        if (!uri.fp.isNullOrEmpty()) settings.lastPin = uri.fp
        updateLastConnected()
        openControl(uri)
    }

    /**
     * 免扫码重连：协议 §1.2 的重连模式不需要 token，只要设备私钥还在（AndroidKeyStore 里）
     * 且电脑端仍然信任它，就能直接进控制页。
     */
    private fun reconnectLast() {
        val host = settings.lastHost
        if (host.isEmpty()) {
            toast(getString(R.string.pairing_reconnect_none))
            return
        }

        val sid = settings.lastSid.ifEmpty { null }
        val fp = settings.lastPin.ifEmpty { null }
        val uri = PairingUri.resume(host, settings.lastPort, sid, fp)

        navigated = true
        stopCamera()
        openControl(uri)
    }

    private fun openControl(uri: PairingUri) {
        val intent = Intent(this, ControlActivity::class.java)
        intent.putExtra(ControlActivity.EXTRA_HOST, uri.host)
        intent.putExtra(ControlActivity.EXTRA_PORT, uri.port)
        intent.putExtra(ControlActivity.EXTRA_TOKEN, uri.token)
        intent.putExtra(ControlActivity.EXTRA_SID, uri.sid)
        intent.putExtra(ControlActivity.EXTRA_FP, uri.fp)
        startActivity(intent)
    }

    // ---------------- 手动输入 ----------------

    private fun showManualInputDialog() {
        val dialogBinding = DialogManualConnectBinding.inflate(LayoutInflater.from(this))
        val lastHost = settings.lastHost
        dialogBinding.editHost.setText(lastHost)
        if (settings.lastPort > 0) {
            dialogBinding.editPort.setText(settings.lastPort.toString())
        } else {
            dialogBinding.editPort.setText(PairingUri.DEFAULT_PORT.toString())
        }
        dialogBinding.editToken.setText(settings.lastToken)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.manual_dialog_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.manual_connect, null)
            .setNegativeButton(R.string.manual_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val host = dialogBinding.editHost.text.toString()
                val portText = dialogBinding.editPort.text.toString().trim()
                val token = dialogBinding.editToken.text.toString()

                if (host.trim().isEmpty()) {
                    showDialogError(dialogBinding, getString(R.string.manual_host_required))
                    return@setOnClickListener
                }
                if (token.trim().isEmpty()) {
                    showDialogError(dialogBinding, getString(R.string.manual_token_required))
                    return@setOnClickListener
                }

                // 端口：留空用默认 8443，非数字或越界直接报错（不做静默兜底）
                var port = PairingUri.DEFAULT_PORT
                if (portText.isNotEmpty()) {
                    val parsed = portText.toIntOrNull()
                    if (parsed == null || parsed !in 1..65535) {
                        showDialogError(dialogBinding, getString(R.string.manual_port_invalid))
                        return@setOnClickListener
                    }
                    port = parsed
                }

                // 与扫码完全相同的校验路径
                val uri = PairingUri.fromParts(host, port, token)
                if (uri == null) {
                    showDialogError(dialogBinding, getString(R.string.pairing_invalid_qr))
                    return@setOnClickListener
                }
                dialog.dismiss()
                navigated = true
                stopCamera()
                persistAndConnect(uri)
            }
        }
        dialog.show()
    }

    private fun showDialogError(dialogBinding: DialogManualConnectBinding, message: String) {
        dialogBinding.manualError.text = message
        dialogBinding.manualError.visibility = View.VISIBLE
    }

    // ---------------- 证书信任重置 ----------------

    private fun resetCertificatePins() {
        val count = settings.serverPinCount()
        if (count == 0) {
            toast(getString(R.string.pairing_pin_reset_empty))
            return
        }
        val removed = PinnedTrust.resetAll(settings)
        toast(getString(R.string.pairing_pin_reset_done) + "（$removed）")
    }

    private fun updateLastConnected() {
        val host = settings.lastHost
        binding.lastConnected.text = if (host.isEmpty()) {
            getString(R.string.pairing_last_connected_none)
        } else {
            val sid = settings.lastSid
            if (sid.isEmpty()) {
                getString(R.string.pairing_last_connected, "${host}:${settings.lastPort}")
            } else {
                getString(R.string.pairing_last_connected_identity, "${host}:${settings.lastPort}", sid)
            }
        }

        // 没有配对记录时「免扫码重连」没有意义，直接禁用，避免用户点了没反应
        binding.buttonReconnect.isEnabled = host.isNotEmpty()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val TAG = "PairingActivity"
    }
}
