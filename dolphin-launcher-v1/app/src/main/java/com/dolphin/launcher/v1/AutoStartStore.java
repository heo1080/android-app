package com.dolphin.launcher.v1;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class AutoStartStore {
    private static final String KEY_SET="autostart_set";
    private static final String KEY_ORDERED="autostart_ordered_v2";
    private static final String SEP="\n";
    private static final String MEDIA_PREFIX="autostart_media_";
    private AutoStartStore(){}

    public static List<String> read(SharedPreferences p){
        String raw=p.getString(KEY_ORDERED,null);
        if(raw!=null){
            ArrayList<String> out=new ArrayList<>();
            for(String s:raw.split(SEP)) if(!s.trim().isEmpty()&&!out.contains(s)) out.add(s);
            return out;
        }
        Set<String> legacy=p.getStringSet(KEY_SET, Collections.emptySet());
        ArrayList<String> migrated=new ArrayList<>();
        if(legacy!=null) migrated.addAll(legacy);
        write(p,migrated);
        return migrated;
    }

    public static void write(SharedPreferences p,List<String> packages){
        LinkedHashSet<String> unique=new LinkedHashSet<>();
        for(String s:packages) if(s!=null&&!s.trim().isEmpty()) unique.add(s);
        StringBuilder b=new StringBuilder();
        for(String s:unique){ if(b.length()>0)b.append(SEP); b.append(s); }
        p.edit().putString(KEY_ORDERED,b.toString()).putStringSet(KEY_SET,new LinkedHashSet<>(unique)).apply();
    }

    public static boolean add(SharedPreferences p,String pkg){
        List<String> list=read(p); if(list.contains(pkg))return false; list.add(pkg); write(p,list); return true;
    }
    public static boolean move(SharedPreferences p,String pkg,int delta){
        List<String> list=read(p); int from=list.indexOf(pkg); if(from<0)return false;
        int to=from+delta; if(to<0||to>=list.size())return false;
        Collections.swap(list,from,to); write(p,list); return true;
    }

    public static boolean mediaEnabled(SharedPreferences p,String pkg){ return p.getBoolean(MEDIA_PREFIX+pkg,false); }
    public static void setMediaEnabled(SharedPreferences p,String pkg,boolean enabled){ p.edit().putBoolean(MEDIA_PREFIX+pkg,enabled).apply(); }

    public static boolean remove(SharedPreferences p,String pkg){
        List<String> list=read(p); boolean changed=list.remove(pkg); if(changed)write(p,list); return changed;
    }
}
