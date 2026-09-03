package com.byd.dolphin.autoassistant.activity

import android.app.Activity
import android.os.Bundle
import com.byd.dolphin.autoassistant.manager.InsideLightManager

/**
 * 앱서랍 또는 홈 화면에서 전체 실내등을 원터치로 끄는 투명 액티비티
 */
class InsideLightOffActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InsideLightManager.turnOff(this, showToast = true)
        finish()
    }
}
