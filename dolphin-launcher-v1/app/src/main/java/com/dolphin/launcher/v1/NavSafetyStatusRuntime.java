package com.dolphin.launcher.v1;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * UI-only freshness snapshot for supported navigation notifications.
 * It never parses camera type, limit speed, distance, or other semantics.
 */
public final class NavSafetyStatusRuntime {
    private static final String PREFS="v1_nav_safety_status";
    private static final String KEY_PACKAGE="package";
    private static final String KEY_HASH="hash";
    private static final String KEY_LENGTH="length";
    private static final String KEY_POSTED_MS="posted_ms";
    private static final String KEY_ACTIVE="active";
    public static final long UI_SOURCE_TTL_MS=30000L;

    private NavSafetyStatusRuntime(){}

    public static final class Snapshot {
        public final boolean available;
        public final boolean active;
        public final boolean fresh;
        public final String packageName;
        public final long ageMs;
        public final int textLength;
        public final String reason;

        Snapshot(boolean available, boolean active, boolean fresh,
                 String packageName, long ageMs, int textLength, String reason){
            this.available=available;
            this.active=active;
            this.fresh=fresh;
            this.packageName=packageName;
            this.ageMs=ageMs;
            this.textLength=textLength;
            this.reason=reason;
        }
    }

    public static void recordPosted(Context context,String pkg,String hash,int length,long nowMs){
        if(context==null || pkg==null)return;
        context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit()
                .putString(KEY_PACKAGE,pkg)
                .putString(KEY_HASH,hash==null?"":hash)
                .putInt(KEY_LENGTH,Math.max(0,length))
                .putLong(KEY_POSTED_MS,nowMs)
                .putBoolean(KEY_ACTIVE,true)
                .apply();
    }

    public static void recordRemoved(Context context,String pkg,long nowMs){
        if(context==null)return;
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        String current=p.getString(KEY_PACKAGE,null);
        if(current==null || pkg==null || !pkg.equals(current))return;
        p.edit().putBoolean(KEY_ACTIVE,false).apply();
    }

    public static Snapshot read(Context context){
        if(context==null)return unavailable("context-unavailable");
        SharedPreferences p=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        long posted=p.getLong(KEY_POSTED_MS,0L);
        String pkg=p.getString(KEY_PACKAGE,null);
        if(posted<=0L || pkg==null || pkg.trim().isEmpty()){
            return unavailable("no-supported-nav-source");
        }
        long age=Math.max(0L,System.currentTimeMillis()-posted);
        boolean active=p.getBoolean(KEY_ACTIVE,false);
        boolean fresh=active && age<=UI_SOURCE_TTL_MS;
        return new Snapshot(
                true,active,fresh,pkg,age,p.getInt(KEY_LENGTH,0),
                fresh?"source-fresh":active?"source-stale":"source-removed");
    }

    public static String sourceLabel(String pkg){
        if("com.nhn.android.nmap".equals(pkg))return "NAVER MAP";
        if("com.skt.tmap.ku".equals(pkg))return "TMAP";
        if("com.locnall.KimGiSa".equals(pkg))return "KAKAONAVI";
        if("com.mappers.AtlanSmart".equals(pkg))return "ATLAN";
        return pkg==null?"UNKNOWN":pkg;
    }

    private static Snapshot unavailable(String reason){
        return new Snapshot(false,false,false,null,Long.MAX_VALUE,0,reason);
    }
}
