package com.byd.dolphin.autoassistant.next.hud

import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.byd.dolphin.autoassistant.next.core.NextLogger

class NextNavNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        val n = sbn.notification ?: return
        if (!isNav(pkg, n.channelId.orEmpty())) return
        val extras = n.extras ?: Bundle.EMPTY
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = collectText(extras, n.tickerText?.toString())
        val sub = extras.getCharSequence("android.subText")?.toString().orEmpty()
        val combined = listOf(title, text, sub).filter { it.isNotBlank() }.joinToString(" | ")
        val cue = parse(pkg, combined)
        NextLogger.i("NAV_NOTIFICATION", "pkg=" + pkg + " cue=" + cue)
        if (cue != null) NextHudBridge.forwardCue(this, cue)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val pkg = sbn?.packageName ?: return
        if (isNav(pkg, "")) NextHudBridge.clearCluster(this)
    }

    private fun parse(pkg: String, text: String): NavCue? {
        if (text.isBlank()) return null
        val distMatch = Regex("([0-9]+(?:\\.[0-9]+)?)\\s*(km|m)(?!\\s*/?\\s*h)", RegexOption.IGNORE_CASE)
            .find(text)
        val meters = distMatch?.let {
            val num = it.groupValues[1].toDoubleOrNull() ?: 0.0
            if (it.groupValues[2].equals("km", true)) (num * 1000.0).toInt() else num.toInt()
        } ?: 0

        val turn = when {
            text.contains("유턴") || text.contains("U턴", true) -> 5
            listOf("좌회전","왼쪽","좌측").any { text.contains(it) } -> 7
            listOf("우회전","오른쪽","우측").any { text.contains(it) } -> 3
            text.contains("직진") -> 1
            else -> 1
        }
        val limit = Regex("(?:제한(?:속도)?|과속|단속)\\s*[:：]?\\s*([0-9]{2,3})")
            .find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val road = text.split("|").firstOrNull { it.length in 2..100 && !it.contains("km") && !it.contains("m") }
            ?.trim().orEmpty()

        return NavCue(pkg, turn, meters.coerceIn(0, 65535), road, limit, text.take(1500))
    }

    private fun isNav(pkg: String, channel: String): Boolean {
        val known = setOf(
            "com.tmap.auto.byd",
            "com.skt.tmap.byd",
            "com.skt.tmap.auto",
            "com.skt.tmap.oem",
            "com.tmapmobility.tmap.autonavi",
            "com.skt.tmap.ku",
            "com.skt.skaf.l001mtm091",
            "com.nhn.android.nmap",
            "com.locnall.KimGiSa",
            "com.thinkware.inaviair"
        )
        return pkg in known ||
            pkg.contains("tmap", true) ||
            pkg.contains("nmap", true) ||
            pkg.contains("kakao", true) ||
            pkg.contains("kimgisa", true) ||
            pkg.contains("inavi", true) ||
            channel.contains("navi", true)
    }

    @Suppress("DEPRECATION")
    private fun collectText(extras: Bundle, ticker: String?): String {
        val out = linkedSetOf<String>()
        ticker?.takeIf { it.isNotBlank() }?.let(out::add)
        extras.keySet().forEach { key ->
            when (val v = extras.get(key)) {
                is CharSequence -> if (v.isNotBlank()) out += v.toString()
                is Array<*> -> v.filterIsInstance<CharSequence>().forEach { if (it.isNotBlank()) out += it.toString() }
                is Iterable<*> -> v.filterIsInstance<CharSequence>().forEach { if (it.isNotBlank()) out += it.toString() }
            }
        }
        return out.joinToString(" | ").take(2500)
    }
}
