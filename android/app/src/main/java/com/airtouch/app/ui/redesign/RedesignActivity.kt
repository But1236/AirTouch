package com.airtouch.app.ui.redesign

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.airtouch.app.ui.redesign.integration.RedesignController
import com.airtouch.app.ui.redesign.theme.AirTouchDimens
import com.airtouch.app.ui.redesign.theme.AirTouchTheme
import com.airtouch.app.ui.redesign.theme.AirTouchTokens
import com.airtouch.app.ui.redesign.theme.RedesignAction
import com.airtouch.app.ui.redesign.theme.RedesignRoute

/**
 * 重构 UI 的**唯一真机入口**（旧版 UI 已整体删除）。
 *
 * - 扫码直接内嵌在 HomeScreen 的 1:1 取景框里，扫码结果由 [RedesignController.connectFromQr]
 *   直接建立连接，全程留在本 Activity，不启动任何旧 Activity；
 * - 也可自动用 SettingsStore 里的设备密钥免扫码重连上次设备；
 * - 顶部无导航标签栏、无模拟状态栏；页面切换靠底部共享的一体式滑块。
 */
class RedesignActivity : ComponentActivity() {

    private lateinit var controller: RedesignController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { controller.sendFile(it) }
        }

        controller = RedesignController(
            activity = this,
            onHint = { msg -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() },
            openFilePicker = { filePicker.launch(arrayOf("*/*")) },
        )

        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent { RedesignHost(controller) }

        controller.connectFromIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        controller.onStart()
    }

    override fun onStop() {
        super.onStop()
        controller.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        controller.onDestroy()
    }
}

@Composable
private fun RedesignHost(controller: RedesignController) {
    val s = controller.state
    val context = LocalContext.current
    val activity = context as? Activity

    AirTouchTheme(
        darkTheme = s.darkTheme,
        accent = s.accent,
        customAccent = s.customAccent,
        leftHandMode = s.leftHandMode,
        hapticEnabled = s.hapticEnabled,
    ) {
        val colors = AirTouchTokens.colors
        // 全屏 = 横屏键盘页：这一页要独占整块屏幕
        val fullscreen = controller.route == RedesignRoute.KEYBOARD_LANDSCAPE

        LaunchedEffect(controller.route) {
            activity?.requestedOrientation = if (controller.route == RedesignRoute.KEYBOARD_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        }
        // 全屏时真正隐藏系统状态栏（时间/电量那一栏），退出全屏立刻恢复。
        // 顺带把「边缘下滑临时呼出」设上：全屏状态下仍能从顶部边缘拉出状态栏看通知。
        LaunchedEffect(fullscreen) {
            activity?.window?.let { w ->
                val c = WindowInsetsControllerCompat(w, w.decorView)
                if (fullscreen) {
                    c.systemBarsBehavior =
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    c.hide(WindowInsetsCompat.Type.statusBars())
                } else {
                    c.show(WindowInsetsCompat.Type.statusBars())
                }
            }
        }
        LaunchedEffect(colors.bgCanvas, s.darkTheme) {
            activity?.window?.let { w ->
                w.statusBarColor = colors.bgCanvas.toArgb()
                WindowInsetsControllerCompat(w, w.decorView)
                    .isAppearanceLightStatusBars = !s.darkTheme
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                // 全屏（状态栏已隐藏）时不能再留 statusBarsPadding，否则顶上白空一条
                .then(if (fullscreen) Modifier else Modifier.statusBarsPadding()),
        ) {
            RedesignScreenHost(
                state = s,
                route = controller.route,
                onAction = controller::dispatch,
                onQrScanned = controller::connectFromQr,
                modifier = Modifier.weight(1f),
            )
        }

        if (controller.showManualConnectDialog) {
            ManualConnectDialog(controller)
        }
        if (controller.showTextDialog) {
            TextInputDialog(controller)
        }
    }
}

@Composable
private fun ManualConnectDialog(controller: RedesignController) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("8443") }
    var token by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { controller.showManualConnectDialog = false },
        title = { Text("手动连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(AirTouchDimens.rowGap)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("主机 / 完整 wss:// URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it },
                    label = { Text("端口") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("token（留空 = 设备密钥重连）") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                controller.connectManual(host, port.toIntOrNull() ?: 8443, token)
            }) { Text("连接") }
        },
        dismissButton = {
            TextButton(onClick = { controller.showManualConnectDialog = false }) { Text("取消") }
        },
    )
}

@Composable
private fun TextInputDialog(controller: RedesignController) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { controller.showTextDialog = false },
        title = { Text("发送整段文字到电脑") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("输入内容（支持中文）") },
            )
        },
        confirmButton = {
            TextButton(onClick = { controller.dispatch(RedesignAction.SendText(text)) }) { Text("发送") }
        },
        dismissButton = {
            TextButton(onClick = { controller.showTextDialog = false }) { Text("取消") }
        },
    )
}
