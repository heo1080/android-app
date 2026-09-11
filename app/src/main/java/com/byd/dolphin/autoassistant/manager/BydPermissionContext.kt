package com.byd.dolphin.autoassistant.manager

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import com.byd.dolphin.autoassistant.util.DolphinLogger

/**
 * DiLink 3 BYDAUTO framework performs several GET/SET checks against the
 * Context passed to getInstance(Context). Those permissions are
 * signature/privileged on this head unit and cannot be granted with pm grant.
 *
 * The vehicle framework's client-side permission gate can be satisfied by a
 * narrow ContextWrapper which only overrides android.permission.BYDAUTO_*.
 * All unrelated Android permissions continue to use the real Context checks.
 */
class BydPermissionContext private constructor(base: Context) : ContextWrapper(base.applicationContext) {

    override fun enforceCallingOrSelfPermission(permission: String?, message: String?) {
        if (isBydPermission(permission)) return
        super.enforceCallingOrSelfPermission(permission, message)
    }

    override fun checkCallingOrSelfPermission(permission: String?): Int {
        if (isBydPermission(permission)) return PackageManager.PERMISSION_GRANTED
        return super.checkCallingOrSelfPermission(permission)
    }

    override fun enforcePermission(permission: String?, pid: Int, uid: Int, message: String?) {
        if (isBydPermission(permission)) return
        super.enforcePermission(permission, pid, uid, message)
    }

    override fun checkPermission(permission: String?, pid: Int, uid: Int): Int {
        if (isBydPermission(permission)) return PackageManager.PERMISSION_GRANTED
        return super.checkPermission(permission, pid, uid)
    }

    companion object {
        private const val PREFIX = "android.permission.BYDAUTO_"

        private fun isBydPermission(permission: String?): Boolean =
            permission?.startsWith(PREFIX) == true

        fun wrap(context: Context): Context {
            if (context is BydPermissionContext) return context
            DolphinLogger.d("BYD_CONTEXT", "BYDAUTO client permission wrapper 사용")
            return BydPermissionContext(context)
        }
    }
}
