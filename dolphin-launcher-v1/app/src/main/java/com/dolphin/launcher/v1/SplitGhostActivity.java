package com.dolphin.launcher.v1;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * Temporary split-primary host used only when the selected primary app already
 * owns a fullscreen task. This mirrors the repository's previously working
 * DiLink 3 recovery path; it does not expose vehicle APIs or infer split success.
 */
public final class SplitGhostActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable finishTask = () -> {
        if (!isFinishing()) finish();
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(finishTask);
        handler.postDelayed(finishTask, 4500L);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(finishTask);
        super.onDestroy();
    }
}
