package com.byd.dolphin.autoassistant.activity

import android.app.Activity
import android.os.Bundle
import com.byd.dolphin.autoassistant.manager.InsideLightManager

/**
 * 앱서랍 또는 홈 화면에서 전체 실내등을 원터치로 켜는 투명 액티비티
 */
class InsideLightOnActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        InsideLightManager.turnOn(this, showToast = true)
        finish()
    }
}
