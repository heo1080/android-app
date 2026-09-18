package com.byd.dolphin.autoassistant.next.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.byd.dolphin.autoassistant.next.NextMainActivity
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.system.NextAdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

object LauncherStartController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun showHome(context: Context, reason: String) {
        val app = context.applicationContext
        scope.launch {
            val component = ComponentName(app, NextMainActivity::class.java)
            var adbStarted = false

            if (NextAdb.isPortOpen()) {
                val result = NextAdb.shell(
                    app,
                    "am start -W --user 0 -n '" +
                        component.flattenToShortString().replace("'", "'\\''") + "'"
                )
                adbStarted = result.success
                NextLogger.i(
                    "LAUNCHER_START",
                    "reason=" + reason + " adb=" + result.success +
                        " exit=" + result.exitCode + " out=" +
                        result.output.replace("\n", " ").take(300)
                )
            }

            if (!adbStarted) {
                runCatching {
                    val intent = Intent(app, NextMainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                        putExtra("launcher_reason", reason)
                    }
                    app.startActivity(intent)
                    NextLogger.i("LAUNCHER_START", "reason=" + reason + " direct startActivity accepted")
                }.onFailure {
                    NextLogger.e(
                        "LAUNCHER_START",
                        "reason=" + reason + " direct start failed",
                        it
                    )
                }
            }
        }
    }
}
