package com.byd.dolphin.autoassistant.activity

import android.app.Activity
import android.os.Bundle
import com.byd.dolphin.autoassistant.manager.InsideLightManager

/**
 * 앱서랍 또는 홈 화면에서 실내등을 원터치로 켜고 끄는 투명 액티비티
 * 클릭 즉시 토글 실행 후 화면 깜빡임 없이 바로 종료됩니다.
 */
class InsideLightToggleActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InsideLightManager.toggle(this, showToast = true)
        finish()
    }
}
