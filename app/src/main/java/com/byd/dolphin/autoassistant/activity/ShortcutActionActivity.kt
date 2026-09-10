package com.byd.dolphin.autoassistant.activity

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.byd.dolphin.autoassistant.floating.FloatingItem
import com.byd.dolphin.autoassistant.manager.FloatingOverlayManager
import com.byd.dolphin.autoassistant.util.DolphinLogger
import java.util.UUID

/** Explicit target for launcher-pinned app and verified quick-action shortcuts. */
class ShortcutActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val suppliedToken = intent.getStringExtra(EXTRA_TOKEN).orEmpty()
        if (suppliedToken.isBlank() || suppliedToken != getOrCreateToken(this)) {
            DolphinLogger.w("SHORTCUT", "유효하지 않은 외부 바로가기 실행 요청 차단")
            Toast.makeText(this, "유효하지 않거나 이전 버전의 바로가기입니다.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val packageName = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        if (id.isBlank()) {
            Toast.makeText(this, "바로가기 정보가 없습니다.", Toast.LENGTH_SHORT).show()
        } else {
            val item = FloatingItem(
                id = id,
                title = title,
                isApp = packageName.isNotBlank(),
                packageName = packageName
            )
            DolphinLogger.i("SHORTCUT", "실행: id=$id package=$packageName")
            FloatingOverlayManager.executeItem(applicationContext, item)
        }
        finish()
    }

    companion object {
        const val EXTRA_ID = "shortcut_item_id"
        const val EXTRA_TITLE = "shortcut_item_title"
        const val EXTRA_PACKAGE = "shortcut_item_package"
        const val EXTRA_TOKEN = "shortcut_install_token"

        private const val PREFS = "secure_shortcut_state"
        private const val KEY_TOKEN = "install_token"

        @Synchronized
        fun getOrCreateToken(context: android.content.Context): String {
            val prefs = context.applicationContext.getSharedPreferences(
                PREFS,
                android.content.Context.MODE_PRIVATE
            )
            prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }?.let { return it }
            return UUID.randomUUID().toString().also { token ->
                prefs.edit().putString(KEY_TOKEN, token).apply()
            }
        }
    }
}
