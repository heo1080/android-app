package com.byd.dolphin.autoassistant.next.overlay

import android.app.Activity
import android.os.Bundle

class QuickBarDrawerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        QuickDockOverlay.showQuickDock(this)
        finish()
    }
}
