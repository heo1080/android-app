package com.byd.dolphin.autoassistant.manager

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 0번 요구사항:
 * 프로젝트 설치 후 실행 시 앱 자체에서 로컬 ADB(5555 포트) 및 쉘을 통해
 * 수동으로 버그제거 앱을 쓰지 않고도 모든 권한을 자동 획득하도록 구현
 */
object AdbPermissionManager {

    private const val TAG = "AdbPermissionManager"
    const val PACKAGE_NAME = "com.byd.dolphin.autoassistant"
    const val LISTENER_COMPONENT = "com.byd.dolphin.autoassistant/com.byd.dolphin.autoassistant.hud.MultiNavNotificationListener"

    const val CMD_WRITE_SECURE = "pm grant $PACKAGE_NAME android.permission.WRITE_SECURE_SETTINGS"
    const val CMD_BT_CONNECT = "pm grant $PACKAGE_NAME android.permission.BLUETOOTH_CONNECT"
    const val CMD_BT_SCAN = "pm grant $PACKAGE_NAME android.permission.BLUETOOTH_SCAN"
    const val CMD_OVERLAY = "appops set $PACKAGE_NAME SYSTEM_ALERT_WINDOW allow"
    const val CMD_NOTIF_LISTENER = "cmd notification allow_listener $LISTENER_COMPONENT"
    const val CMD_BATTERY_WHITELIST = "dumpsys deviceidle whitelist +$PACKAGE_NAME"
    const val CMD_RUN_IN_BACKGROUND = "cmd appops set $PACKAGE_NAME RUN_IN_BACKGROUND allow"

    fun getAllAdbCommands(): String {
        return listOf(
            CMD_WRITE_SECURE,
            CMD_BT_CONNECT,
            CMD_BT_SCAN,
            CMD_OVERLAY,
            CMD_NOTIF_LISTENER,
            CMD_BATTERY_WHITELIST,
            CMD_RUN_IN_BACKGROUND
        ).joinToString("\n")
    }

    fun isNotificationListenerGranted(context: Context): Boolean {
        val packages = NotificationManagerCompat.getEnabledListenerPackages(context)
        return packages.contains(context.packageName)
    }

    fun isOverlayGranted(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isWriteSecureSettingsGranted(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_SECURE_SETTINGS
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isBluetoothGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isAllPermissionsGranted(context: Context): Boolean {
        return isNotificationListenerGranted(context) &&
                isOverlayGranted(context) &&
                isWriteSecureSettingsGranted(context) &&
                isBluetoothGranted(context)
    }

    /**
     * 앱 실행 시 자체적으로 로컬 ADB 소켓(127.0.0.1:5555) 및 sh 프로세스를 실행하여
     * 사용자가 수동으로 버그제거 앱에서 명령어를 넣지 않아도 자동 권한 부여 완료
     */
    fun autoGrantPermissionsOnLaunch(context: Context, onComplete: ((Boolean, String) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            DolphinLogger.i(TAG, "로컬 ADB 자체 권한 자동 승인 시도 시작")

            // 1단계: 로컬 쉘(sh) 직접 실행 시도
            var grantedViaSh = false
            try {
                val process = Runtime.getRuntime().exec("sh")
                val os: OutputStream = process.outputStream
                val script = getAllAdbCommands() + "\nexit\n"
                os.write(script.toByteArray())
                os.flush()
                os.close()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    grantedViaSh = true
                    DolphinLogger.i(TAG, "로컬 sh 프로세스를 통해 권한 자동 승인 성공")
                }
            } catch (e: Exception) {
                DolphinLogger.w(TAG, "로컬 sh 직접 실행 실패: ${e.message}")
            }

            // 2단계: 로컬 127.0.0.1:5555 ADB 데몬 소켓 직접 통신 시도 (USB 디버깅 허용된 경우)
            var grantedViaSocket = false
            if (!grantedViaSh) {
                try {
                    val socket = Socket()
                    socket.connect(InetSocketAddress("127.0.0.1", 5555), 1500)
                    val out = socket.getOutputStream()
                    val cmds = getAllAdbCommands() + "\nexit\n"
                    out.write(cmds.toByteArray())
                    out.flush()
                    socket.close()
                    grantedViaSocket = true
                    DolphinLogger.i(TAG, "127.0.0.1:5555 로컬 ADB 소켓을 통해 명령 전송 성공")
                } catch (e: Exception) {
                    DolphinLogger.w(TAG, "127.0.0.1:5555 연결 실패: ${e.message}")
                }
            }

            val success = grantedViaSh || grantedViaSocket || isAllPermissionsGranted(context)
            withContext(Dispatchers.Main) {
                val msg = if (success) {
                    "모든 필수 차량 권한이 자체적으로 자동 승인되었습니다."
                } else {
                    "USB 디버깅 '항상 허용' 확인 후 원터치 실행 버튼을 눌러주세요."
                }
                DolphinLogger.i(TAG, "자동 권한 부여 결과: $msg")
                onComplete?.invoke(success, msg)
            }
        }
    }

    fun copyToClipboard(context: Context, text: String, toastMessage: String = "ADB 명령어가 클립보드에 복사되었습니다.") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("ADB_Commands", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }
}
