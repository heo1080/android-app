package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.byd.dolphin.autoassistant.util.DolphinLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 로컬 ADB 포트 연결, 시스템 권한 상태 감지 및 원터치 권한 부여 매니저
 */
object AdbPermissionManager {

    private const val TAG = "AdbPermissionManager"

    private val REQUIRED_PERMISSIONS = listOf(
        "android.permission.WRITE_SECURE_SETTINGS",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.DUMP"
    )

    fun isAllGranted(context: Context): Boolean {
        return isOverlayGranted(context) && isSecureSettingsGranted(context) && isNotificationListenerGranted(context)
    }

    fun isOverlayGranted(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    fun isSecureSettingsGranted(context: Context): Boolean {
        return context.checkCallingOrSelfPermission(
            "android.permission.WRITE_SECURE_SETTINGS"
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isNotificationListenerGranted(context: Context): Boolean {
        return NotificationManagerCompat
            .getEnabledListenerPackages(context)
            .contains(context.packageName)
    }

    /**
     * ADB 없이도 사용자가 설정 화면에서 원터치로 플로팅 권한을 부여할 수 있도록 인텐트 제공
     */
    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
        }
    }

    /**
     * ADB 없이도 사용자가 설정 화면에서 원터치로 알림 접근 권한을 부여할 수 있도록 인텐트 제공
     */
    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun autoGrantPermissionsOnLaunch(context: Context, onStatus: (Boolean, String) -> Unit) {
        if (isAllGranted(context)) {
            DolphinLogger.i(TAG, "모든 필수 권한이 이미 승인되어 있습니다.")
            onStatus(true, "권한 이미 승인됨")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val pkg = context.packageName
            val commands = mutableListOf<String>()
            for (p in REQUIRED_PERMISSIONS) {
                commands.add("pm grant $pkg $p")
            }
            commands.add("cmd notification allow_listener $pkg/.notification.MultiNavNotificationListener")

            val candidateHosts = mutableListOf("127.0.0.1", "localhost", "192.168.10.10")
            getLocalIpAddress()?.let { candidateHosts.add(it) }

            var anySuccess = false
            var lastError = "5555 포트 미응답"

            for (host in candidateHosts) {
                try {
                    DolphinLogger.i(TAG, "차량 로컬 ADB 연결 시도: $host:5555")
                    NativeAdbClient.connectAndExecute(
                        host = host,
                        port = 5555,
                        commands = commands
                    ) { success, msg ->
                        if (success) {
                            anySuccess = true
                            DolphinLogger.i(TAG, "차량 ADB 권한 승인 성공 ($host:5555)")
                        } else {
                            lastError = msg
                        }
                    }
                    if (anySuccess) break
                } catch (e: Exception) {
                    lastError = e.message ?: "연결 거부"
                }
            }

            withContext(Dispatchers.Main) {
                if (anySuccess || isAllGranted(context)) {
                    onStatus(true, "차량 권한 자동 부여 완료")
                } else {
                    DolphinLogger.w(TAG, "로컬 ADB 자동 연결 실패: $lastError")
                    onStatus(false, "차량 로컬 ADB 포트(5555) 미개방 ($lastError)")
                }
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val en = NetworkInterface.getNetworkInterfaces()
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                val enumIpAddr = intf.inetAddresses
                while (enumIpAddr.hasMoreElements()) {
                    val inetAddress = enumIpAddr.nextElement()
                    if (!inetAddress.isLoopbackAddress && inetAddress is Inet4Address) {
                        return inetAddress.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}
