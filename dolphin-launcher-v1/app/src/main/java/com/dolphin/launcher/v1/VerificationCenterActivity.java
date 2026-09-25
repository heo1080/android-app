package com.dolphin.launcher.v1;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Set;

public class VerificationCenterActivity extends Activity {
    private JSONObject registry;
    private TextView ledgerStatus;
    private TextView collectionStatus;
    private TextView pendingStatus;
    private TextView lastUploadStatus;
    private LinearLayout detailList;
    private LinearLayout activeCapturePanel;
    private LinearLayout activeCaptureActions;
    private TextView activeCaptureStatus;
    private String activeTestId;
    private String activeCorrelationId;
    private String activeTestState;
    private JSONObject activeFeature;
    private boolean detailsVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.parseColor("#03080B"));
        getWindow().setNavigationBarColor(Color.parseColor("#03080B"));
        try {
            registry = VerificationEvidenceRuntime.loadRegistry(this);
            setContentView(buildUi());
        } catch (Exception e) {
            TextView error = text("Verification Registry 로드 실패\n" + e.getMessage(),
                    18f, Color.WHITE, true);
            error.setPadding(dp(28), dp(28), dp(28), dp(28));
            error.setBackgroundColor(Color.parseColor("#17090C"));
            setContentView(error);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLedger();
        refreshRuntimeStatus();
        restoreActiveCapture();
    }

    private void refreshRuntimeStatus() {
        if (collectionStatus != null) collectionStatus.setText("자동 수집\n활성");
        if (pendingStatus != null) pendingStatus.setText(
                "업로드 대기\n" + VerificationEvidenceRuntime.pendingUploadCount(this) + "건");
        if (lastUploadStatus != null) lastUploadStatus.setText(
                "마지막 업로드\n" + VerificationEvidenceRuntime.lastUploadStatus(this));
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(16), dp(24), dp(18));
        root.setBackground(gradient("#071821", "#02070A"));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(text("VERIFICATION CENTER", 27f, Color.WHITE, true));
        titleBox.addView(text("Registry-first · 실차 Test ID evidence", 13f,
                Color.parseColor("#8AA5B2"), false));
        header.addView(titleBox,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button back = button("닫기");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(86), dp(44)));
        root.addView(header);

        JSONArray features = registry.optJSONArray("features");
        int featureCount = features == null ? 0 : features.length();
        Set<String> testIds = new LinkedHashSet<>();
        if (features != null) {
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                JSONArray ids = feature == null ? null : feature.optJSONArray("test_ids");
                if (ids != null) {
                    for (int j = 0; j < ids.length(); j++) testIds.add(ids.optString(j));
                }
            }
        }

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setPadding(0, dp(14), 0, dp(12));
        summary.addView(summaryChip("SCHEMA", String.valueOf(registry.optInt("schema_version", -1))),
                weighted());
        summary.addView(summaryChip("FEATURES", String.valueOf(featureCount)), weighted());
        summary.addView(summaryChip("TEST IDs", String.valueOf(testIds.size())), weighted());
        ledgerStatus = summaryChip("LEDGER",
                String.valueOf(VerificationEvidenceRuntime.ledgerLineCount(this)));
        summary.addView(ledgerStatus, weighted());
        root.addView(summary);

        LinearLayout autoStatus = new LinearLayout(this);
        autoStatus.setOrientation(LinearLayout.HORIZONTAL);
        autoStatus.setPadding(0, dp(4), 0, dp(12));
        collectionStatus = statusCard("자동 수집", "활성");
        pendingStatus = statusCard("업로드 대기", "확인 중");
        lastUploadStatus = statusCard("마지막 업로드", "확인 중");
        autoStatus.addView(collectionStatus, weighted());
        autoStatus.addView(pendingStatus, weighted());
        autoStatus.addView(lastUploadStatus, weighted());
        refreshRuntimeStatus();
        root.addView(autoStatus);

        final String detailLabel = "개발자 상세 · " + featureCount + " Feature / " + testIds.size() + " Test ID";
        Button details = button(detailLabel);
        details.setOnClickListener(v -> {
            detailsVisible = !detailsVisible;
            if (detailList != null) detailList.setVisibility(detailsVisible ? View.VISIBLE : View.GONE);
            details.setText(detailsVisible ? "개발자 상세 닫기" : detailLabel);
        });
        root.addView(details, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button problem = button("지금 문제 발생");
        problem.setOnClickListener(v -> {
            VerificationEvidenceRuntime.markProblem(this, "Verification Center manual marker");
            refreshLedger();
            refreshRuntimeStatus();
            Toast.makeText(this, "문제 순간을 evidence ledger에 기록했습니다.",
                    Toast.LENGTH_SHORT).show();
        });
        actions.addView(problem, weighted());

        Button export = button("Evidence ZIP 생성");
        export.setOnClickListener(v -> {
            try {
                File file = VerificationEvidenceRuntime.createEvidenceBundle(this);
                refreshRuntimeStatus();
                Toast.makeText(this, "생성: " + file.getAbsolutePath(),
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "ZIP 생성 실패: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
        actions.addView(export, weighted());
        root.addView(actions,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)));

        activeCapturePanel = new LinearLayout(this);
        activeCapturePanel.setOrientation(LinearLayout.VERTICAL);
        activeCapturePanel.setPadding(dp(14), dp(12), dp(14), dp(12));
        activeCapturePanel.setBackground(round("#101B10", 16, "#4A7A58"));
        activeCapturePanel.setVisibility(View.GONE);

        activeCaptureStatus = text("실차 캡처 세션 없음", 13f, Color.WHITE, true);
        activeCapturePanel.addView(activeCaptureStatus);

        activeCaptureActions = new LinearLayout(this);
        activeCaptureActions.setOrientation(LinearLayout.VERTICAL);
        activeCaptureActions.setPadding(0, dp(8), 0, 0);
        activeCapturePanel.addView(activeCaptureActions,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams captureLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        captureLp.setMargins(0, dp(4), 0, dp(10));
        root.addView(activeCapturePanel, captureLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        detailList = new LinearLayout(this);
        detailList.setOrientation(LinearLayout.VERTICAL);
        detailList.setPadding(0, dp(8), 0, dp(20));
        detailList.setVisibility(View.GONE);
        scroll.addView(detailList);

        if (features != null) {
            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.optJSONObject(i);
                if (feature != null) detailList.addView(featureCard(feature));
            }
        }

        root.addView(scroll,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private View featureCard(JSONObject feature) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(round("#0A171D", 18, "#1C3946"));

        String featureId = feature.optString("feature_id", "UNKNOWN");
        String state = feature.optString("state", "UNKNOWN");
        card.addView(text(featureId + "   [" + state + "]",
                16f, stateColor(state), true));

        TextView req = text(feature.optString("requirement", ""),
                13f, Color.parseColor("#C5D3DA"), false);
        req.setPadding(0, dp(6), 0, dp(8));
        card.addView(req);

        JSONArray known = feature.optJSONArray("known_bad");
        if (known != null && known.length() > 0) {
            TextView kb = text("Known-Bad: " + known.optString(0),
                    11f, Color.parseColor("#FFB36A"), false);
            kb.setPadding(0, 0, 0, dp(8));
            card.addView(kb);
        }

        JSONArray ids = feature.optJSONArray("test_ids");
        if (ids != null) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < ids.length(); i++) {
                String testId = ids.optString(i);
                Button test = button(testId);
                test.setTextSize(11f);
                test.setOnClickListener(v -> openTestDialog(testId, state, feature));
                row.addView(test, weighted());
            }
            card.addView(row,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        card.setLayoutParams(lp);
        return card;
    }

    private void openTestDialog(String testId, String state, JSONObject feature) {
        if (activeTestId != null) {
            if (activeTestId.equals(testId)) {
                Toast.makeText(this, testId + " 캡처 세션이 이미 진행 중입니다.",
                        Toast.LENGTH_SHORT).show();
                refreshActiveCaptureUi();
            } else {
                Toast.makeText(this, "먼저 진행 중인 " + activeTestId + " 세션을 종료하세요.",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle(testId + " · " + state)
                .setMessage(feature.optString("requirement", "")
                        + "\n\n실차 조작 전에 캡처를 시작하고, 실제 표시가 바뀌는 순간 아래 마커를 누르세요.")
                .setPositiveButton("실차 캡처 시작", (dialog, which) ->
                        startLiveCapture(testId, state, feature))
                .setNegativeButton("취소", null)
                .show();
    }

    private void startLiveCapture(String testId, String state, JSONObject feature) {
        activeTestId = testId;
        activeTestState = state;
        activeFeature = feature;
        activeCorrelationId = VerificationEvidenceRuntime.startTest(this, testId);
        refreshActiveCaptureUi();
        Toast.makeText(this, testId + " 캡처 시작", Toast.LENGTH_SHORT).show();
    }

    private void restoreActiveCapture() {
        if (registry == null || activeCapturePanel == null) return;
        String testId = VerificationEvidenceRuntime.activeTestId(this);
        String correlation = VerificationEvidenceRuntime.activeTestCorrelation(this);
        if (testId == null || correlation == null) return;
        JSONObject feature = findFeatureForTest(testId);
        if (feature == null) return;
        activeTestId = testId;
        activeCorrelationId = correlation;
        activeFeature = feature;
        activeTestState = feature.optString("state", "UNKNOWN");
        refreshActiveCaptureUi();
    }

    private JSONObject findFeatureForTest(String testId) {
        JSONArray features = registry == null ? null : registry.optJSONArray("features");
        if (features == null) return null;
        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.optJSONObject(i);
            JSONArray ids = feature == null ? null : feature.optJSONArray("test_ids");
            if (ids == null) continue;
            for (int j = 0; j < ids.length(); j++) {
                if (testId.equals(ids.optString(j))) return feature;
            }
        }
        return null;
    }

    private void refreshActiveCaptureUi() {
        if (activeCapturePanel == null || activeCaptureActions == null || activeCaptureStatus == null) return;
        if (activeTestId == null || activeCorrelationId == null) {
            activeCapturePanel.setVisibility(View.GONE);
            activeCaptureActions.removeAllViews();
            return;
        }

        activeCapturePanel.setVisibility(View.VISIBLE);
        activeCaptureStatus.setText("실차 캡처 진행 중 · " + activeTestId
                + "\nraw 변화와 운전자 관찰 마커를 같은 시간축으로 기록합니다.");
        activeCaptureActions.removeAllViews();

        LinearLayout markerRow = new LinearLayout(this);
        markerRow.setOrientation(LinearLayout.HORIZONTAL);
        addMarkerButton(markerRow, "시점 기록", "OPERATOR_MARK");

        if ("AUD-DRV-002".equals(activeTestId)) {
            addMarkerButton(markerRow, "OEM NORMAL", "OEM_NORMAL_VISIBLE");
        } else if ("AUD-REG-002".equals(activeTestId)) {
            addMarkerButton(markerRow, "OEM STANDARD", "OEM_STANDARD_VISIBLE");
        } else if ("AUD-AVH-001".equals(activeTestId)) {
            addMarkerButton(markerRow, "버튼 ON", "AUTOHOLD_SWITCH_ON_VISIBLE");
            addMarkerButton(markerRow, "버튼 OFF", "AUTOHOLD_SWITCH_OFF_VISIBLE");
        } else if ("AUD-AVH-002".equals(activeTestId)) {
            addMarkerButton(markerRow, "체결 표시", "AUTOHOLD_HELD_VISIBLE");
            addMarkerButton(markerRow, "해제/출발", "AUTOHOLD_RELEASE_VISIBLE");
        }
        activeCaptureActions.addView(markerRow,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);

        Button finish = button("결과 종료");
        finish.setOnClickListener(v -> finishActiveCaptureDialog());
        controlRow.addView(finish, weighted());

        Button abort = button("중단 · 데이터 유지");
        abort.setOnClickListener(v -> abortActiveCapture());
        controlRow.addView(abort, weighted());

        activeCaptureActions.addView(controlRow,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private void addMarkerButton(LinearLayout row, String label, String observation) {
        Button marker = button(label);
        marker.setTextSize(11f);
        marker.setOnClickListener(v -> recordActiveObservation(observation));
        row.addView(marker, weighted());
    }

    private void recordActiveObservation(String observation) {
        if (activeTestId == null || activeCorrelationId == null) return;
        VerificationEvidenceRuntime.operatorObservation(
                this, activeTestId, activeCorrelationId, observation,
                "Verification Center live correlation marker");
        refreshLedger();
        Toast.makeText(this, "관찰 마커 기록: " + observation,
                Toast.LENGTH_SHORT).show();
    }

    private void finishActiveCaptureDialog() {
        if (activeTestId == null || activeCorrelationId == null) return;
        EditText note = new EditText(this);
        note.setHint("실차 관찰/재현 메모");
        note.setTextColor(Color.WHITE);
        note.setHintTextColor(Color.parseColor("#6F8793"));
        note.setSingleLine(false);
        note.setMinLines(2);
        note.setPadding(dp(14), dp(10), dp(14), dp(10));
        note.setBackground(round("#071116", 14, "#294957"));

        boolean blocked = "BLOCKED".equals(activeTestState) || "UNSUPPORTED".equals(activeTestState);
        String[] outcomes = blocked
                ? new String[]{"NEED_MORE_DATA"}
                : new String[]{"PASS", "FAIL", "INTERMITTENT", "DELAYED", "NEED_MORE_DATA"};

        new AlertDialog.Builder(this)
                .setTitle(activeTestId + " 결과")
                .setMessage(activeFeature == null ? "" : activeFeature.optString("requirement", ""))
                .setView(note)
                .setItems(outcomes, (dialog, which) ->
                        completeActiveCapture(outcomes[which], note.getText().toString().trim(),
                                "Verification Center live capture completed"))
                .setNegativeButton("계속 캡처", null)
                .show();
    }

    private void abortActiveCapture() {
        if (activeTestId == null || activeCorrelationId == null) return;
        completeActiveCapture("NEED_MORE_DATA", "operator aborted live capture",
                "Verification Center live capture aborted");
    }

    private void completeActiveCapture(String outcome, String note, String endNote) {
        String testId = activeTestId;
        String correlation = activeCorrelationId;
        VerificationEvidenceRuntime.operatorResult(this, testId, correlation, outcome, note);
        VerificationEvidenceRuntime.endTest(this, testId, correlation, outcome, endNote);
        VerificationEvidenceRuntime.queueBundleAndUpload(this, "test-complete-" + testId);

        activeTestId = null;
        activeCorrelationId = null;
        activeTestState = null;
        activeFeature = null;
        refreshLedger();
        refreshRuntimeStatus();
        refreshActiveCaptureUi();
        Toast.makeText(this, testId + " → " + outcome, Toast.LENGTH_SHORT).show();
    }

    private void refreshLedger() {
        if (ledgerStatus != null) {
            ledgerStatus.setText("LEDGER\n" +
                    VerificationEvidenceRuntime.ledgerLineCount(this));
        }
    }

    private TextView statusCard(String label, String value) {
        TextView card = text(label + "\n" + value, 14f, Color.WHITE, true);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8), dp(12), dp(8), dp(12));
        card.setBackground(round("#0B1A21", 16, "#2A5362"));
        return card;
    }

    private TextView summaryChip(String label, String value) {
        TextView chip = text(label + "\n" + value, 12f, Color.WHITE, true);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(round("#0B1A21", 14, "#24424F"));
        return chip;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setBackground(round("#10252E", 14, "#2A5362"));
        return b;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(size);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private int stateColor(String state) {
        if ("VERIFIED".equals(state)) return Color.parseColor("#72E6B1");
        if ("BETA".equals(state)) return Color.parseColor("#77D9FF");
        if ("REVERIFY_REQUIRED".equals(state)) return Color.parseColor("#FFD166");
        if ("BLOCKED".equals(state)) return Color.parseColor("#FF8A80");
        return Color.parseColor("#A6B8C1");
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        return lp;
    }

    private GradientDrawable gradient(String start, String end) {
        return new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor(start), Color.parseColor(end)});
    }

    private GradientDrawable round(String fill, int radiusDp, String stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(radiusDp));
        d.setColor(Color.parseColor(fill));
        if (stroke != null) d.setStroke(dp(1), Color.parseColor(stroke));
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
