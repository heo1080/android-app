package com.dolphin.launcher.v1;

import android.app.Activity;
import android.os.Bundle;

/**
 * Disabled-by-default launcher activities used as BYD stock app-drawer slots.
 * Each slot is a real MAIN+LAUNCHER component; it only dispatches the
 * user-assigned target and never treats launcher visibility as verified.
 */
public final class DrawerSlotActivities {
    private DrawerSlotActivities() {}

    public abstract static class Base extends Activity {
        protected abstract int slot();

        @Override
        protected void onCreate(Bundle state) {
            super.onCreate(state);
            AppDrawerShortcutManager.launchSlot(this, slot());
            finish();
        }
    }

    public static final class Slot01 extends Base { @Override protected int slot() { return 1; } }
    public static final class Slot02 extends Base { @Override protected int slot() { return 2; } }
    public static final class Slot03 extends Base { @Override protected int slot() { return 3; } }
    public static final class Slot04 extends Base { @Override protected int slot() { return 4; } }
    public static final class Slot05 extends Base { @Override protected int slot() { return 5; } }
    public static final class Slot06 extends Base { @Override protected int slot() { return 6; } }
    public static final class Slot07 extends Base { @Override protected int slot() { return 7; } }
    public static final class Slot08 extends Base { @Override protected int slot() { return 8; } }
    public static final class Slot09 extends Base { @Override protected int slot() { return 9; } }
    public static final class Slot10 extends Base { @Override protected int slot() { return 10; } }
    public static final class Slot11 extends Base { @Override protected int slot() { return 11; } }
    public static final class Slot12 extends Base { @Override protected int slot() { return 12; } }
    public static final class Slot13 extends Base { @Override protected int slot() { return 13; } }
    public static final class Slot14 extends Base { @Override protected int slot() { return 14; } }
    public static final class Slot15 extends Base { @Override protected int slot() { return 15; } }
    public static final class Slot16 extends Base { @Override protected int slot() { return 16; } }
}
