package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;

/**
 * Narrow compatibility wrapper used only for BYD framework getInstance(Context).
 * It mirrors the legacy real-car-tested client-side BYDAUTO permission behavior
 * while leaving every unrelated Android permission check untouched.
 */
public final class BydPermissionContext extends ContextWrapper {
    private static final String PREFIX = "android.permission.BYDAUTO_";

    private BydPermissionContext(Context base) {
        super(base.getApplicationContext());
    }

    public static Context wrap(Context context) {
        if (context instanceof BydPermissionContext) return context;
        return new BydPermissionContext(context);
    }

    private static boolean isByd(String permission) {
        return permission != null && permission.startsWith(PREFIX);
    }

    @Override
    public void enforceCallingOrSelfPermission(String permission, String message) {
        if (isByd(permission)) return;
        super.enforceCallingOrSelfPermission(permission, message);
    }

    @Override
    public int checkCallingOrSelfPermission(String permission) {
        return isByd(permission) ? PackageManager.PERMISSION_GRANTED
                : super.checkCallingOrSelfPermission(permission);
    }

    @Override
    public void enforcePermission(String permission, int pid, int uid, String message) {
        if (isByd(permission)) return;
        super.enforcePermission(permission, pid, uid, message);
    }

    @Override
    public int checkPermission(String permission, int pid, int uid) {
        return isByd(permission) ? PackageManager.PERMISSION_GRANTED
                : super.checkPermission(permission, pid, uid);
    }
}
