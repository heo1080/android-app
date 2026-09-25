package com.dolphin.launcher.v1;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class NavNotificationEvidenceService extends NotificationListenerService {
    private static final Set<String> SUPPORTED=new HashSet<>(Arrays.asList(
            "com.nhn.android.nmap","com.skt.tmap.ku","com.locnall.KimGiSa","com.mappers.AtlanSmart"));

    @Override public void onListenerConnected(){
        VerificationEvidenceRuntime.recordPassiveEvent(this,"NAV_NOTIFICATION_LISTENER","connected=true;mode=read-only");
    }

    @Override public void onNotificationPosted(StatusBarNotification sbn){
        if(sbn==null || !SUPPORTED.contains(sbn.getPackageName())) return;
        Notification n=sbn.getNotification();
        Bundle e=n==null?null:n.extras;
        String title=e==null?null:String.valueOf(e.getCharSequence(Notification.EXTRA_TITLE,""));
        String text=e==null?null:String.valueOf(e.getCharSequence(Notification.EXTRA_TEXT,""));
        String payload=(title==null?"":title)+"\n"+(text==null?"":text);
        VerificationEvidenceRuntime.recordPassiveEvent(this,"NAV_NOTIFICATION_RAW",
                "package="+sbn.getPackageName()+";textSha256="+sha256(payload)
                        +";textLength="+payload.length()+";parser=none;mode=read-only");
    }

    private static String sha256(String value){
        try{
            byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder();
            for(byte x:d)b.append(String.format("%02x",x));
            return b.toString();
        }catch(Throwable t){return "unavailable";}
    }
}
