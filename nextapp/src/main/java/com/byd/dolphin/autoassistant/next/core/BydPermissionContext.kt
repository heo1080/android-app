package com.byd.dolphin.autoassistant.next.core

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager

class BydPermissionContext private constructor(base: Context) : ContextWrapper(base.applicationContext) {
    override fun checkCallingOrSelfPermission(permission: String): Int =
        if (permission.startsWith(PREFIX)) PackageManager.PERMISSION_GRANTED
        else super.checkCallingOrSelfPermission(permission)

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int =
        if (permission.startsWith(PREFIX)) PackageManager.PERMISSION_GRANTED
        else super.checkPermission(permission, pid, uid)

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
        if (!permission.startsWith(PREFIX)) super.enforceCallingOrSelfPermission(permission, message)
    }

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
        if (!permission.startsWith(PREFIX)) super.enforcePermission(permission, pid, uid, message)
    }

    companion object {
        private const val PREFIX = "android.permission.BYDAUTO_"
        fun wrap(context: Context): Context =
            if (context is BydPermissionContext) context else BydPermissionContext(context)
    }
}
