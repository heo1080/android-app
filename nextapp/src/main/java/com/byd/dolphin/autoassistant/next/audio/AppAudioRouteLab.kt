package com.byd.dolphin.autoassistant.next.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.integrated.IntegratedSettings

object AppAudioRouteLab {
    data class Probe(
        val modifyAudioRoutingGranted: Boolean,
        val selectedApps: List<String>,
        val outputDevices: List<String>,
        val affinityMethods: List<String>,
        val detail: String
    )

    fun probe(context: Context): Probe {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val permission = context.checkSelfPermission("android.permission.MODIFY_AUDIO_ROUTING") == PackageManager.PERMISSION_GRANTED
        val selected = IntegratedSettings.selectedDriverAudioApps(context).sorted()
        val apps = selected.map { pkg ->
            val uid = runCatching { context.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
            pkg + "(uid=" + (uid?.toString() ?: "missing") + ")"
        }
        val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
            "id=" + it.id + " type=" + it.type + " name=" + it.productName + " addr=" + it.address
        }
        val methods = (AudioManager::class.java.methods + AudioManager::class.java.declaredMethods)
            .filter { it.name.contains("Affinity", true) || it.name.contains("AudioPolicy", true) || it.name.contains("Uid", true) }
            .map { it.name + "(" + it.parameterTypes.joinToString { p -> p.simpleName } + ")" }
            .distinct().sorted()
        val detail = if (!permission) {
            "MODIFY_AUDIO_ROUTING not granted; third-party UID routing cannot be activated"
        } else if (methods.none { it.contains("Affinity", true) }) {
            "permission present but no UID affinity method exposed on AudioManager"
        } else {
            "UID affinity candidate exists; driver-only physical device still must be identified"
        }
        NextLogger.i("APP_AUDIO_LAB", "permission=" + permission + " apps=" + apps + " outputs=" + outputs + " methods=" + methods + " detail=" + detail)
        return Probe(permission, apps, outputs, methods, detail)
    }
}
