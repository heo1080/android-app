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

/**
 * 0번 요구사항:
 * 프로젝트 설치 후 실행 시 NativeAdbClient를 통해 안드로이드 공식 ADB 프로토콜로
 * 127.0.0.1:5555에 접속하여 'USB 디버깅을 허용하시겠습니까?' 팝업창을 직접 트리거하고,
 * 사용자가 '허용'을 누르면 자체적으로 모든 쉘 명령어를 실행합니다.
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

    fun getCommandList(): List<String> {
        return listOf(
            CMD_WRITE_SECURE,
            CMD_BT_CONNECT,
            CMD_BT_SCAN,
            CMD_OVERLAY,
            CMD_NOTIF_LISTENER,
            CMD_BATTERY_WHITELIST,
            CMD_RUN_IN_BACKGROUND
        )
    }

    fun getAllAdbCommands(): String {
        return getCommandList().joinToString("\n")
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
     * 앱 실행 시 자체적으로 NativeAdbClient(A_CNXN + A_AUTH RSA)를 가동하여
     * 화면에 'USB 디버깅을 허용하시겠습니까?' 팝업창을 띄우고 자동 승인 수행
     */
    fun autoGrantPermissionsOnLaunch(context: Context, onComplete: ((Boolean, String) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            if (isAllPermissionsGranted(context)) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(true, "이미 모든 권한이 승인되어 정상 가동 중입니다.")
                }
                return@launch
            }

            DolphinLogger.i(TAG, "로컬 ADB 자체 권한 자동 승인 시도 시작")

            // 1차: 로컬 sh 직접 실행
            var grantedViaSh = false
            try {
                val process = Runtime.getRuntime().exec("sh")
                val os: OutputStream = process.outputStream
                val script = getAllAdbCommands() + "\nexit\n"
                os.write(script.toByteArray())
                os.flush()
                os.close()
                val exitCode = process.waitFor()
                if (exitCode == 0 && isAllPermissionsGranted(context)) {
                    grantedViaSh = true
                    DolphinLogger.i(TAG, "로컬 sh 프로세스를 통해 권한 승인 성공")
                }
            } catch (e: Exception) {
                DolphinLogger.w(TAG, "로컬 sh 직접 실행 불가: ${e.message}")
            }

            if (grantedViaSh) {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(true, "로컬 쉘을 통해 모든 권한이 자체 승인되었습니다.")
                }
                return@launch
            }

            // 2차: NativeAdbClient를 통한 실제 ADB 프로토콜 전송 -> 'USB 디버깅 허용' 팝업 창 발생!
            NativeAdbClient.connectAndExecute(
                host = "127.0.0.1",
                port = 5555,
                commands = getCommandList()
            ) { success, message ->
                CoroutineScope(Dispatchers.Main).launch {
                    val finalSuccess = success || isAllPermissionsGranted(context)
                    onComplete?.invoke(finalSuccess, message)
                }
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
