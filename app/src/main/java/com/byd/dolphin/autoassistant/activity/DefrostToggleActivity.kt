package com.byd.dolphin.autoassistant.activity

import android.app.Activity
import android.os.Bundle
import com.byd.dolphin.autoassistant.manager.DefrostManager

/**
 * 앱서랍 또는 홈 화면에서 원터치로 통합 성에 제거를 켜고 끄는 투명 액티비티
 * 화면 전환 없이 켜짐/꺼짐 토글 후 즉시 종료됩니다.
 */
class DefrostToggleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DefrostManager.toggle(this, showToast = true)
        finish()
    }
}
