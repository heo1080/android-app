package com.byd.dolphin.autoassistant.activity

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.byd.dolphin.autoassistant.manager.AppDrawerShortcutManager
import com.byd.dolphin.autoassistant.manager.FloatingOverlayManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

abstract class DrawerSlotActivityBase : Activity() {
    abstract val slot: Int
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val item = AppDrawerShortcutManager.read(this, slot)
        if (item == null) {
            DolphinLogger.w("APP_DRAWER", "비어 있는 앱서랍 슬롯 실행 slot=$slot")
            Toast.makeText(this, "이 앱서랍 바로가기는 비어 있습니다.", Toast.LENGTH_SHORT).show()
        } else {
            DolphinLogger.i("APP_DRAWER", "앱서랍 실행 slot=$slot id=${item.id} package=${item.packageName}")
            FloatingOverlayManager.executeItem(applicationContext, item)
        }
        finish()
    }
}

class DrawerSlotActivity01 : DrawerSlotActivityBase() { override val slot = 1 }
class DrawerSlotActivity02 : DrawerSlotActivityBase() { override val slot = 2 }
class DrawerSlotActivity03 : DrawerSlotActivityBase() { override val slot = 3 }
class DrawerSlotActivity04 : DrawerSlotActivityBase() { override val slot = 4 }
class DrawerSlotActivity05 : DrawerSlotActivityBase() { override val slot = 5 }
class DrawerSlotActivity06 : DrawerSlotActivityBase() { override val slot = 6 }
class DrawerSlotActivity07 : DrawerSlotActivityBase() { override val slot = 7 }
class DrawerSlotActivity08 : DrawerSlotActivityBase() { override val slot = 8 }
class DrawerSlotActivity09 : DrawerSlotActivityBase() { override val slot = 9 }
class DrawerSlotActivity10 : DrawerSlotActivityBase() { override val slot = 10 }
class DrawerSlotActivity11 : DrawerSlotActivityBase() { override val slot = 11 }
class DrawerSlotActivity12 : DrawerSlotActivityBase() { override val slot = 12 }
class DrawerSlotActivity13 : DrawerSlotActivityBase() { override val slot = 13 }
class DrawerSlotActivity14 : DrawerSlotActivityBase() { override val slot = 14 }
class DrawerSlotActivity15 : DrawerSlotActivityBase() { override val slot = 15 }
class DrawerSlotActivity16 : DrawerSlotActivityBase() { override val slot = 16 }
