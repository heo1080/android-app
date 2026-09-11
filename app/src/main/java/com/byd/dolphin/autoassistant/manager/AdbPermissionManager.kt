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

/**
 * 로컬 ADB 포트 연결, 시스템 권한 상태 감지 및 원터치 권한 부여 매니저
 */
object AdbPermissionManager {

    private const val TAG = "AdbPermissionManager"

    private val SYSTEM_GRANT_PERMISSIONS = listOf(
        "android.permission.WRITE_SECURE_SETTINGS"
    )

    // COMMON 권한만 pm grant 가능한 런타임 권한이다. GET/SET은 이 차량에서
    // signature/privileged라 pm grant가 항상 실패한다. GET/SET 호출은
    // BydPermissionContext를 통해 BYD SDK의 client-side Context 검사 경로를 사용한다.
    private val BYD_GRANT_PERMISSIONS = listOf(
        "android.permission.BYDAUTO_SETTING_COMMON",
        "android.permission.BYDAUTO_AC_COMMON",
        "android.permission.BYDAUTO_BODYWORK_COMMON",
        "android.permission.BYDAUTO_LIGHT_COMMON",
        "android.permission.BYDAUTO_RADAR_COMMON",
        "android.permission.BYDAUTO_INSTRUMENT_COMMON"
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
        CoroutineScope(Dispatchers.IO).launch {
            val pkg = context.packageName
            val host = "127.0.0.1"

            if (!NativeAdbClient.isPortOpen(host, 5555)) {
                withContext(Dispatchers.Main) {
                    onStatus(false, "차량 로컬 ADB 포트(5555) 미개방")
                }
                return@launch
            }

            DolphinLogger.i(TAG, "차량 로컬 ADB 연결/권한 진단 시작: $host:5555")

            // 가장 먼저 무해한 명령으로 shell 채널 자체가 정상인지 확인한다.
            val identity = NativeAdbClient.executeShell(context, "id", host, 5555)
            if (!identity.success) {
                DolphinLogger.w(TAG, "ADB 연결은 열렸지만 shell 실행 실패: ${identity.message}")
                withContext(Dispatchers.Main) {
                    onStatus(false, "ADB 연결됨 · shell 실행 실패 (${identity.message})")
                }
                return@launch
            }

            var successCount = 0
            var failureCount = 0
            val failures = mutableListOf<String>()

            val grantTargets = SYSTEM_GRANT_PERMISSIONS + BYD_GRANT_PERMISSIONS
            for (permission in grantTargets) {
                val result = NativeAdbClient.executeShell(
                    context,
                    "pm grant $pkg $permission",
                    host,
                    5555
                )
                if (result.success) {
                    successCount++
                    DolphinLogger.i(TAG, "grant 성공: $permission")
                } else {
                    failureCount++
                    val reason = result.message.take(180)
                    failures += "$permission=$reason"
                    DolphinLogger.w(TAG, "grant 실패: $permission ($reason)")
                }
            }

            // pm grant 하나가 실패해도 appops/알림 리스너는 반드시 별도로 계속 실행한다.
            val supplementalCommands = listOf(
                "appops set $pkg SYSTEM_ALERT_WINDOW allow",
                "cmd notification allow_listener $pkg/.hud.MultiNavNotificationListener"
            )
            for (command in supplementalCommands) {
                val result = NativeAdbClient.executeShell(context, command, host, 5555)
                if (result.success) successCount++ else {
                    failureCount++
                    failures += "$command=${result.message.take(180)}"
                }
            }

            // 실제 앱 프로세스 기준으로 최종 권한 상태를 재검사한다.
            val bydGranted = BYD_GRANT_PERMISSIONS.count {
                context.checkCallingOrSelfPermission(it) == PackageManager.PERMISSION_GRANTED
            }
            DolphinLogger.i(
                TAG,
                "ADB 권한 진단 완료: commandSuccess=$successCount commandFailure=$failureCount " +
                    "bydCommonGranted=$bydGranted/${BYD_GRANT_PERMISSIONS.size} bydContextWrapper=true overlay=${isOverlayGranted(context)} " +
                    "secure=${isSecureSettingsGranted(context)} notification=${isNotificationListenerGranted(context)}"
            )
            failures.take(6).forEach { DolphinLogger.w(TAG, "권한 실패 상세: $it") }

            val coreReady = isOverlayGranted(context) && isNotificationListenerGranted(context)
            val message = when {
                bydGranted == BYD_GRANT_PERMISSIONS.size -> "BYD COMMON 승인 완료 · GET/SET은 차량 API Context 브리지 사용"
                bydGranted > 0 -> "기본 권한 완료 · BYD COMMON $bydGranted/${BYD_GRANT_PERMISSIONS.size} 승인 · Context 브리지 사용"
                coreReady -> "기본 권한 완료 · BYD GET/SET은 pm grant 대신 Context 브리지 사용"
                else -> "ADB 진단 완료 · 일부 기본/BYD 권한 미승인"
            }

            withContext(Dispatchers.Main) {
                onStatus(coreReady, message)
            }
        }
    }

}
