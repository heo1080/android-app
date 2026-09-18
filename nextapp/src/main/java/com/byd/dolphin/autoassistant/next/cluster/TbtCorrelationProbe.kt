package com.byd.dolphin.autoassistant.next.cluster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.system.NextAdb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TbtCorrelationState(
    val registered: Boolean = false,
    val active: Boolean = false,
    val startedAtMs: Long = 0L,
    val endsAtMs: Long = 0L,
    val receiverCandidates: List<String> = emptyList(),
    val packageCandidates: List<String> = emptyList(),
    val eventCount: Int = 0,
    val lastEventAtMs: Long = 0L,
    val lastEventSummary: String = "",
    val serviceEvidence: String = "",
    val logEvidence: String = "",
    val result: String = "IDLE"
)

object TbtCorrelationProbe {
    const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(TbtCorrelationState())
    val state: StateFlow<TbtCorrelationState> = _state

    @Volatile private var registered = false
    private var windowJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            if (intent?.action != ACTION) return
            val now = System.currentTimeMillis()
            val extras = intent.extras
            val summary = buildString {
                append("action=")
                append(intent.action)
                append(" pkg=")
                append(intent.getPackage() ?: "broadcast")
                if (extras != null) {
                    append(" extras=")
                    extras.keySet().sorted().forEach { key ->
                        append(key)
                        append("=")
                        append(runCatching { extras.get(key) }.getOrNull()?.toString()?.take(180))
                        append(";")
                    }
                }
            }
            val old = _state.value
            _state.value = old.copy(
                eventCount = old.eventCount + 1,
                lastEventAtMs = now,
                lastEventSummary = summary,
                result = if (old.active) "OEM TBT BROADCAST OBSERVED" else old.result
            )
            NextLogger.i(
                "TBT_CORRELATION",
                "broadcast received active=" + old.active + " " + summary
            )
        }
    }

    fun register(context: Context) {
        if (registered) return
        val app = context.applicationContext
        runCatching {
            ContextCompat.registerReceiver(
                app,
                receiver,
                IntentFilter(ACTION),
                ContextCompat.RECEIVER_EXPORTED
            )
            registered = true
            _state.value = _state.value.copy(registered = true)
            NextLogger.i("TBT_CORRELATION", "receiver registered action=" + ACTION)
        }.onFailure {
            NextLogger.e("TBT_CORRELATION", "receiver registration failed", it)
        }
    }

    fun unregister(context: Context) {
        if (!registered) return
        runCatching { context.applicationContext.unregisterReceiver(receiver) }
        registered = false
        windowJob?.cancel()
        windowJob = null
        _state.value = _state.value.copy(registered = false, active = false)
    }

    fun startWindow(context: Context, durationMs: Long = 60_000L) {
        register(context)
        windowJob?.cancel()
        val app = context.applicationContext
        val start = System.currentTimeMillis()
        windowJob = scope.launch {
            val receiverCandidates = queryReceivers(app)
            val packageCandidates = queryPackages(app)

            _state.value = _state.value.copy(
                active = true,
                startedAtMs = start,
                endsAtMs = start + durationMs,
                receiverCandidates = receiverCandidates,
                packageCandidates = packageCandidates,
                eventCount = 0,
                lastEventAtMs = 0L,
                lastEventSummary = "",
                serviceEvidence = "",
                logEvidence = "",
                result = "LISTENING · use stock TMAP/navigation now"
            )

            NextLogger.i(
                "TBT_CORRELATION",
                "window start durationMs=" + durationMs +
                    " receivers=" + receiverCandidates.joinToString("|") +
                    " packages=" + packageCandidates.joinToString("|")
            )

            var lastServiceEvidence = ""
            while (isActive && System.currentTimeMillis() < start + durationMs) {
                val serviceEvidence = serviceSnapshot(app)
                if (serviceEvidence.isNotBlank() && serviceEvidence != lastServiceEvidence) {
                    lastServiceEvidence = serviceEvidence
                    _state.value = _state.value.copy(serviceEvidence = serviceEvidence)
                    NextLogger.i(
                        "TBT_CORRELATION",
                        "service evidence=" + serviceEvidence.replace("\n", " ").take(1200)
                    )
                }
                delay(5_000L)
            }

            val logs = logEvidence(app)
            val current = _state.value
            val result = when {
                current.eventCount > 0 && lastServiceEvidence.isNotBlank() ->
                    "CORRELATION CANDIDATE · broadcast + Amap/autonavi service evidence"
                current.eventCount > 0 ->
                    "BROADCAST VERIFIED · downstream service/CAN still unconfirmed"
                current.receiverCandidates.isNotEmpty() ->
                    "RECEIVER EXISTS · no stock broadcast observed in window"
                else ->
                    "NO RECEIVER / NO BROADCAST · Korean Dolphin path unconfirmed"
            }

            _state.value = current.copy(
                active = false,
                serviceEvidence = lastServiceEvidence,
                logEvidence = logs,
                result = result
            )

            NextLogger.i(
                "TBT_CORRELATION",
                "window end result=" + result +
                    " events=" + current.eventCount +
                    " logs=" + logs.replace("\n", " ").take(1800)
            )
        }
    }

    fun stopWindow() {
        windowJob?.cancel()
        windowJob = null
        if (_state.value.active) {
            _state.value = _state.value.copy(
                active = false,
                result = "STOPPED"
            )
        }
    }

    private fun queryReceivers(context: Context): List<String> {
        @Suppress("DEPRECATION")
        return runCatching {
            context.packageManager.queryBroadcastReceivers(Intent(ACTION), 0)
                .mapNotNull { info ->
                    val activity = info.activityInfo ?: return@mapNotNull null
                    activity.packageName + "/" + activity.name
                }
                .distinct()
                .sorted()
        }.getOrDefault(emptyList())
    }

    private fun queryPackages(context: Context): List<String> {
        if (!NextAdb.isPortOpen()) return emptyList()
        val r = NextAdb.shell(
            context,
            "sh -c \"pm list packages | grep -Ei 'amap|autonavi|tmap|navi|cluster|instrument' | head -n 120\""
        )
        return r.output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun serviceSnapshot(context: Context): String {
        if (!NextAdb.isPortOpen()) return ""
        val r = NextAdb.shell(
            context,
            "sh -c \"dumpsys activity services 2>/dev/null | grep -Ei -A2 -B2 'amap|autonavi|tmap' | head -n 160\""
        )
        return r.output.trim().take(12_000)
    }

    private fun logEvidence(context: Context): String {
        if (!NextAdb.isPortOpen()) return ""
        val r = NextAdb.shell(
            context,
            "sh -c \"logcat -d -t 500 2>/dev/null | grep -Ei 'AUTONAVI_STANDARD_BROADCAST_SEND|AmapService|autonavi|BYDAutoInstrument|GuidanceInfo' | tail -n 180\""
        )
        return r.output.trim().take(18_000)
    }
}
