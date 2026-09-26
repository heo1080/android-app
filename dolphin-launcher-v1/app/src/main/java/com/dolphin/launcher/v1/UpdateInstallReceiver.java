package com.dolphin.launcher.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

public class UpdateInstallReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL_STATUS =
            "com.dolphin.launcher.v1.action.INSTALL_STATUS";
    private static final String TAG = "V1_APP_UPDATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            VerificationEvidenceRuntime.recordPassiveEvent(context, "APP_UPDATE_INSTALL_STATUS",
                    "status=PENDING_USER_ACTION");
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            } else {
                Toast.makeText(context, "Android 설치 확인 화면을 열지 못했습니다.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            VerificationEvidenceRuntime.recordPassiveEvent(context, "APP_UPDATE_INSTALL_STATUS",
                    "status=SUCCESS");
            Log.i(TAG, "OTA install success");
            Toast.makeText(context, "Dolphin Launcher V1 업데이트가 완료되었습니다.",
                    Toast.LENGTH_LONG).show();
        } else {
            VerificationEvidenceRuntime.recordPassiveEvent(context, "APP_UPDATE_INSTALL_STATUS",
                    "status=FAILURE;code=" + status);
            Log.e(TAG, "OTA install failed status=" + status + " message=" + message);
            Toast.makeText(context,
                    "업데이트 설치 실패: " + (message == null ? status : message),
                    Toast.LENGTH_LONG).show();
        }
    }
}
