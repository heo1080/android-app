package com.byd.dolphin.autoassistant.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.byd.dolphin.autoassistant.manager.AdbPermissionManager
import com.byd.dolphin.autoassistant.manager.NowPlayingManager
import com.byd.dolphin.autoassistant.manager.NowPlayingSnapshot
import java.util.Locale

/**
 * Approved-concept now-playing card:
 * album art + app/title/artist/album + elapsed/duration + previous/play-next.
 * It never shows a fake track; permission/no-session states are explicit.
 */
class NowPlayingCardView(
    context: Context,
    private val manager: NowPlayingManager
) : LinearLayout(context) {

    private val cyan = Color.rgb(0, 229, 255)
    private val muted = Color.rgb(139, 170, 181)
    private val cardBg = Color.rgb(5, 24, 38)

    private val art = ImageView(context)
    private val source = label("NOW PLAYING", 8.5f, cyan, true)
    private val title = label("재생 중인 음악 없음", 15f, Color.WHITE, true)
    private val artist = label("", 10f, muted, false)
    private val album = label("", 9f, Color.rgb(102, 141, 154), false)
    private val time = label("00:00  /  00:00", 9f, Color.rgb(116, 158, 171), false)
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 1000
        progress = 0
    }
    private val prev = control("◀◀") { manager.previous() }
    private val play = control("▶") { manager.playPause() }
    private val next = control("▶▶") { manager.next() }
    private val permission = control("권한 자동 복구") {
        AdbPermissionManager.autoGrantPermissionsOnLaunch(context) { _, _ -> }
    }.apply { visibility = GONE }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(cardBg, dp(17).toFloat(), Color.rgb(11, 83, 101))

        art.scaleType = ImageView.ScaleType.CENTER_CROP
        art.setBackgroundColor(Color.rgb(8, 35, 49))
        addView(art, LayoutParams(dp(86), dp(86)).apply {
            setMargins(0, 0, dp(14), 0)
        })

        val meta = LinearLayout(context).apply {
            orientation = VERTICAL
        }
        meta.addView(source)
        meta.addView(title.apply { setPadding(0, dp(3), 0, 0) })
        meta.addView(artist.apply { setPadding(0, dp(2), 0, 0) })
        meta.addView(album.apply { setPadding(0, dp(1), 0, dp(4)) })
        meta.addView(progress, LayoutParams(MATCH_PARENT, dp(5)))
        meta.addView(time.apply { setPadding(0, dp(3), 0, 0) })
        addView(meta, LayoutParams(0, WRAP_CONTENT, 1f))

        val controls = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            addView(prev, LayoutParams(dp(48), dp(38)))
            addView(play, LayoutParams(dp(48), dp(38)).apply { setMargins(dp(5), 0, dp(5), 0) })
            addView(next, LayoutParams(dp(48), dp(38)))
        }
        controls.addView(row)
        controls.addView(permission, LayoutParams(dp(154), dp(34)).apply {
            setMargins(0, dp(6), 0, 0)
        })
        addView(controls, LayoutParams(dp(164), WRAP_CONTENT))
    }

    fun bind(snapshot: NowPlayingSnapshot) {
        permission.visibility = if (!snapshot.notificationAccess) VISIBLE else GONE

        if (!snapshot.available) {
            art.setImageDrawable(null)
            source.text = if (snapshot.notificationAccess) "NOW PLAYING · 연결 대기" else "NOW PLAYING · 권한 자동 복구 중"
            title.text = if (snapshot.notificationAccess) "재생 중인 미디어를 찾는 중" else "미디어 접근 권한 확인 중"
            artist.text = if (snapshot.notificationAccess) "음악을 재생하면 자동으로 표시됩니다." else "차량 내부 ADB로 알림 접근 권한을 먼저 복구합니다."
            album.text = ""
            progress.progress = 0
            time.text = "00:00  /  00:00"
            play.text = "▶"
            return
        }

        source.text = buildString {
            append(snapshot.appName.ifBlank { snapshot.packageName.ifBlank { "MEDIA" } })
            append("  ·  ")
            append(snapshot.source)
        }
        title.text = snapshot.title.ifBlank { "제목 정보 없음" }
        artist.text = snapshot.artist.ifBlank { "아티스트 정보 없음" }
        album.text = snapshot.album

        if (snapshot.artwork != null) {
            art.setImageBitmap(snapshot.artwork)
        } else {
            art.setImageDrawable(null)
            art.setBackgroundColor(Color.rgb(8, 35, 49))
        }

        val duration = snapshot.durationMs.coerceAtLeast(0L)
        val position = snapshot.positionMs.coerceAtLeast(0L)
        progress.progress = if (duration > 0L) {
            ((position.coerceAtMost(duration) * 1000L) / duration).toInt()
        } else 0
        time.text = "${formatTime(position)}  /  ${formatTime(duration)}"
        play.text = if (snapshot.playing) "Ⅱ" else "▶"
    }

    private fun control(textValue: String, action: () -> Unit): TextView =
        label(textValue, 10f, Color.rgb(218, 251, 255), true).apply {
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(5, 57, 73), dp(10).toFloat(), Color.rgb(0, 143, 170))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun label(value: String, size: Float, color: Int, bold: Boolean): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
        }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun formatTime(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSec = ms / 1000L
        val h = totalSec / 3600L
        val m = (totalSec % 3600L) / 60L
        val s = totalSec % 60L
        return if (h > 0L) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
