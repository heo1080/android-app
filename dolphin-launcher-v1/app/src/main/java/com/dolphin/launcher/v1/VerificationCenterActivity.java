package com.dolphin.launcher.v1;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class VerificationCenterActivity extends Activity {
    private JSONObject registry;
    private JSONObject testContracts;
    private JSONObject knownBadRegistry;
    private TextView ledgerStatus;
    private TextView collectionStatus;
    private TextView pendingStatus;
    private TextView lastUploadStatus;
    private LinearLayout detailList;
    private LinearLayout activeCapturePanel;
    private LinearLayout activeCaptureActions;
    private TextView activeCaptureStatus;
    private TextView activeRawStatus;
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
            testContracts = VerificationEvidenceRuntime.loadTestContracts(this);
            knownBadRegistry = VerificationEvidenceRuntime.loadKnownBadRegistry(this);
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
        root.setPadding(dp(22), dp(12), dp(22), dp(16));
        root.setBackground(gradient("#071A22", "#010305"));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        HmiGlyphView verifyGlyph = new HmiGlyphView(this, "✓");
        verifyGlyph.setAccentColor(Color.parseColor("#8CFFE8"));
        verifyGlyph.setBackground(gradientRound(
                new String[]{"#1B4E50","#0B2B2E","#06191D"}, 18, "#3B756E"));
        LinearLayout.LayoutParams verifyGlyphLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        verifyGlyphLp.rightMargin = dp(14);
        header.addView(verifyGlyph, verifyGlyphLp);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        TextView verifyTitle = text("VERIFICATION CENTER", 25f, Color.WHITE, true);
        verifyTitle.setLetterSpacing(0.06f);
        titleBox.addView(verifyTitle);
        titleBox.addView(text("REGISTRY-FIRST  ·  REAL CAR EVIDENCE", 10f,
                Color.parseColor("#6E8C97"), false));
        header.addView(titleBox,
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView betaGate = text("BETA  ·  OPERATOR GATED", 9.5f,
                Color.parseColor("#FFD166"), true);
        betaGate.setLetterSpacing(0.06f);
        betaGate.setGravity(Gravity.CENTER);
        betaGate.setPadding(dp(12), 0, dp(12), 0);
        betaGate.setBackground(gradientRound(
                new String[]{"#2D2715","#17140B"}, 15, "#6F5A22"));
        LinearLayout.LayoutParams betaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        betaLp.rightMargin = dp(10);
        header.addView(betaGate, betaLp);

        Button back = button("닫기");
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(86), dp(48)));
        root.addView(header);

        VerificationEvidenceRuntime.recordPassiveEvent(
                this, "VERIFICATION_HMI_RENDER",
                "variant=glass-vector-v3-premium;compat_variant=glass-vector-v2"
                        + ";priority=P0;active_capture=glass-green"
                        + ";release_channel=BETA;promotion=operator-gated;auto_promotion=false");

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

        Button p0Queue = button("P0  ·  실차 검증 큐  ·  " + p0Count() + "건");
        p0Queue.setTextColor(Color.parseColor("#FFD6DB"));
        p0Queue.setBackground(pressableGradientRound(
                new String[]{"#351A20","#1C0F13"},
                new String[]{"#49232B","#281419"},
                16, "#7C3E49"));
        p0Queue.setOnClickListener(v -> showP0Queue());
        LinearLayout.LayoutParams p0Lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        p0Lp.bottomMargin = dp(6);
        root.addView(p0Queue, p0Lp);

        Button uiQuality = button(uiQualityLabel());
        applyUiQualityStyle(uiQuality);
        uiQuality.setOnClickListener(v -> showUiQualitySummary());
        LinearLayout.LayoutParams qualityLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50));
        qualityLp.bottomMargin = dp(6);
        root.addView(uiQuality, qualityLp);

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
        details.setBackground(pressableGradientRound(
                new String[]{"#0E222A","#07151B"},
                new String[]{"#173440","#0A2028"},
                15, "#284B57"));
        details.setOnClickListener(v -> {
            detailsVisible = !detailsVisible;
            if (detailList != null) detailList.setVisibility(detailsVisible ? View.VISIBLE : View.GONE);
            details.setText(detailsVisible ? "개발자 상세 닫기" : detailLabel);
        });
        root.addView(details, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button problem = button("문제 순간 기록");
        problem.setBackground(pressableGradientRound(
                new String[]{"#2A171C","#160D11"},
                new String[]{"#402129","#241116"},
                15, "#6D3843"));
        problem.setOnClickListener(v -> {
            VerificationEvidenceRuntime.markProblem(this, "Verification Center manual marker");
            refreshLedger();
            refreshRuntimeStatus();
            Toast.makeText(this, "문제 순간을 evidence ledger에 기록했습니다.",
                    Toast.LENGTH_SHORT).show();
        });
        actions.addView(problem, weighted());

        Button newSession = button("새 검증 세션");
        newSession.setOnClickListener(v -> {
            if (activeTestId != null) {
                Toast.makeText(this, "진행 중인 " + activeTestId + " 테스트를 먼저 종료하세요.",
                        Toast.LENGTH_LONG).show();
                return;
            }
            try {
                String next = VerificationEvidenceRuntime.rolloverSession(
                        this, "Verification Center manual cross-session repeat");
                refreshLedger();
                refreshRuntimeStatus();
                Toast.makeText(this, "새 검증 세션 시작 · "
                                + next.substring(0, Math.min(8, next.length())),
                        Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "세션 전환 실패: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        });
        actions.addView(newSession, weighted());

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
        activeCapturePanel.setPadding(dp(16), dp(14), dp(16), dp(14));
        activeCapturePanel.setBackground(gradientRound(
                new String[]{"#10332D","#09211D","#061410"}, 19, "#3F8F72"));
        activeCapturePanel.setElevation(dp(3));
        activeCapturePanel.setVisibility(View.GONE);

        activeCaptureStatus = text("실차 캡처 세션 없음", 13f, Color.WHITE, true);
        activeCapturePanel.addView(activeCaptureStatus);

        activeRawStatus = text("최근 raw 후보(candidate only · PASS 아님) · 아직 없음",
                12f, Color.parseColor("#9CC7D8"), false);
        activeRawStatus.setPadding(0, dp(6), 0, 0);
        activeCapturePanel.addView(activeRawStatus);

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

    private boolean isActiveP0(JSONObject row) {
        if (row == null || !"P0".equals(row.optString("severity"))) return false;
        return !"CLOSED".equals(row.optString("state"));
    }

    private int p0Count() {
        JSONArray rows = knownBadRegistry == null ? null : knownBadRegistry.optJSONArray("known_bad");
        if (rows == null) return 0;
        int count = 0;
        for (int i = 0; i < rows.length(); i++) {
            if (isActiveP0(rows.optJSONObject(i))) count++;
        }
        return count;
    }

    private void showP0Queue() {
        JSONArray rows = knownBadRegistry == null ? null : knownBadRegistry.optJSONArray("known_bad");
        if (rows == null) return;
        java.util.ArrayList<JSONObject> p0Rows = new java.util.ArrayList<>();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (!isActiveP0(row)) continue;
            p0Rows.add(row);
            labels.add(row.optString("id") + " · " + row.optString("feature_id")
                    + " · " + row.optString("state"));
        }
        if (p0Rows.isEmpty()) {
            Toast.makeText(this, "현재 P0 Known-Bad 없음", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("P0 실차 검증 큐")
                .setItems(labels.toArray(new String[0]), (dialog, which) ->
                        showP0Item(p0Rows.get(which)))
                .setNegativeButton("닫기", null)
                .show();
    }

    private void showP0Item(JSONObject row) {
        JSONArray ids = row.optJSONArray("test_ids");
        String[] labels = new String[ids == null ? 0 : ids.length()];
        for (int i = 0; i < labels.length; i++) labels[i] = "시작 · " + ids.optString(i);
        String message = row.optString("summary", "")
                + "\n\nClose gate\n" + row.optString("close_gate", "");
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(row.optString("id") + " · " + row.optString("feature_id"))
                .setMessage(message)
                .setNegativeButton("닫기", null);
        if (labels.length > 0) {
            builder.setItems(labels, (dialog, which) -> {
                String testId = ids.optString(which);
                JSONObject feature = findFeatureForTest(testId);
                if (feature == null) {
                    Toast.makeText(this, "Registry에서 " + testId + "를 찾지 못했습니다.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                openTestDialog(testId, feature.optString("state", "UNKNOWN"), feature);
            });
        }
        builder.show();
    }

    private View featureCard(JSONObject feature) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(gradientRound(
                new String[]{"#102831","#09171D","#061014"}, 19, "#244C59"));
        card.setElevation(dp(1));

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

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(16), dp(18), dp(16));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"}, 22, "#315A68"));

        TextView testHead = text(testId, 20f, stateColor(state), true);
        testHead.setLetterSpacing(0.04f);
        panel.addView(testHead);
        TextView stateLine = text(state + "  ·  실차 캡처", 10f,
                Color.parseColor("#7896A1"), true);
        stateLine.setPadding(0, dp(2), 0, dp(10));
        panel.addView(stateLine);

        TextView guide = text(captureGuide(testId, feature), 12f,
                Color.parseColor("#C7D7DD"), false);
        guide.setLineSpacing(dp(2), 1.05f);
        guide.setBackground(gradientRound(
                new String[]{"#0B1D24","#061117"}, 15, "#274B58"));
        guide.setPadding(dp(14), dp(12), dp(14), dp(12));
        panel.addView(guide);

        AlertDialog testDialog = new AlertDialog.Builder(this)
                .setView(panel)
                .setPositiveButton("실차 캡처 시작", (dialog, which) ->
                        startLiveCapture(testId, state, feature))
                .setNegativeButton("취소", null)
                .create();
        testDialog.show();
    }

    private String captureGuide(String testId, JSONObject feature) {
        StringBuilder out = new StringBuilder();
        out.append(feature.optString("requirement", ""));
        JSONArray steps = feature.optJSONArray("real_vehicle_test");
        if (steps != null && steps.length() > 0) {
            out.append("\n\n실차 절차");
            for (int i = 0; i < steps.length(); i++) {
                out.append("\n").append(i + 1).append(". ").append(steps.optString(i));
            }
        }
        out.append("\n\n마커는 같은 상태를 최소 3회 기록하세요. 한 세션을 마친 뒤 '새 검증 세션'으로 이전 Evidence를 보존하고 별도 세션에서 다시 반복할 수 있습니다.");
        out.append("\n주행 중 필요한 테스트는 운전자가 화면을 조작하지 말고 동승자 또는 안전한 시험 환경에서 기록하세요.");
        if (rawKeysForTest(testId).length > 0) {
            out.append("\n차량 raw 상관 Test는 latest_raw가 실제 기록된 마커만 3회 진행률/PASS 잠금에 반영됩니다.");
        }
        out.append("\n마커 자체는 PASS가 아니며 raw 상관 후보만 만듭니다.");
        return out.toString();
    }

    private String markerProgressText(Map<String,Integer> counts) {
        StringBuilder out = new StringBuilder("마커 진행");
        String[][] plan = markerPlanForTest(activeTestId);
        if (plan.length == 0) {
            out.append(" · 시점 ").append(counts.containsKey("OPERATOR_MARK")
                    ? counts.get("OPERATOR_MARK") : 0);
            return out.toString();
        }
        for (String[] markerSpec : plan) {
            progress(out, counts, markerSpec[0], markerSpec[1]);
        }
        return out.toString();
    }

    private void progress(StringBuilder out, Map<String,Integer> counts,
                          String label, String marker) {
        int count = counts.containsKey(marker) ? counts.get(marker) : 0;
        out.append(" · ").append(label).append(" ")
                .append(Math.min(count, 3)).append("/3");
        if (count > 3) out.append("+");
    }

    private void startLiveCapture(String testId, String state, JSONObject feature) {
        activeTestId = testId;
        activeTestState = state;
        activeFeature = feature;
        activeCorrelationId = VerificationEvidenceRuntime.startTest(this, testId);
        captureFeatureSpecificProbe(testId);
        refreshActiveCaptureUi();
        Toast.makeText(this, testId + " 캡처 시작", Toast.LENGTH_SHORT).show();
    }

    private void captureFeatureSpecificProbe(String testId) {
        if (testId == null || activeCorrelationId == null) return;
        if ("AUD-AVAS-001".equals(testId)) {
            AudioCapabilityProbe.capture(this, testId, activeCorrelationId);
            VerificationEvidenceRuntime.recordTestEvent(
                    this, testId, activeCorrelationId, "TEST_CAPABILITY_REFRESH",
                    "probe=external_avas;vehicle_write=false;actuation=false");
        } else if (testId.startsWith("PARK-HAZ-")) {
            ParkingHazardAutomationProbe.captureCapability(this);
            VerificationEvidenceRuntime.recordTestEvent(
                    this, testId, activeCorrelationId, "TEST_CAPABILITY_REFRESH",
                    "probe=parking_hazard;vehicle_write=false");
        } else if (testId.startsWith("ADAS-LDW-")) {
            LaneDepartureCapabilityProbe.capture(this);
            VerificationEvidenceRuntime.recordTestEvent(
                    this, testId, activeCorrelationId, "TEST_CAPABILITY_REFRESH",
                    "probe=lane_departure;voice_enabled=false;vehicle_write=false");
        } else if (testId.startsWith("WIN-POP-")) {
            PopupMultiWindowCapabilityProbe.capture(this, null);
            VerificationEvidenceRuntime.recordTestEvent(
                    this, testId, activeCorrelationId, "TEST_CAPABILITY_REFRESH",
                    "probe=popup_multiwindow;windowing_mode_request=false;actuation=false");
        }
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
        if (activeCapturePanel == null || activeCaptureActions == null || activeCaptureStatus == null || activeRawStatus == null) return;
        if (activeTestId == null || activeCorrelationId == null) {
            activeCapturePanel.setVisibility(View.GONE);
            activeCaptureActions.removeAllViews();
            return;
        }

        activeCapturePanel.setVisibility(View.VISIBLE);
        Map<String,Integer> counts = activeMarkerCounts();
        long ageMs = VerificationEvidenceRuntime.activeTestAgeMs(this);
        long ageSeconds = ageMs == Long.MAX_VALUE ? -1L : ageMs / 1000L;
        String age = ageSeconds < 0 ? "--:--"
                : String.format(java.util.Locale.US, "%02d:%02d", ageSeconds / 60L, ageSeconds % 60L);
        activeCaptureStatus.setText("실차 캡처 진행 중 · " + activeTestId
                + " · " + age
                + "\n" + markerProgressText(counts)
                + "\n주행 중 필요한 항목은 운전자가 화면을 누르지 말고 동승자가 기록하세요.");
        activeRawStatus.setText(latestRawCandidateText());
        activeCaptureActions.removeAllViews();

        LinearLayout markerRow = new LinearLayout(this);
        markerRow.setOrientation(LinearLayout.HORIZONTAL);
        addMarkerButton(markerRow, "시점 기록", "OPERATOR_MARK");

        for (String[] markerSpec : markerPlanForTest(activeTestId)) {
            addMarkerButton(markerRow, markerSpec[0], markerSpec[1]);
        }
        activeCaptureActions.addView(markerRow,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        LinearLayout controlRow = new LinearLayout(this);
        controlRow.setOrientation(LinearLayout.HORIZONTAL);

        Button rawRefresh = button("raw 새로고침");
        rawRefresh.setOnClickListener(v -> refreshActiveCaptureUi());
        controlRow.addView(rawRefresh, weighted());

        Button finish = button("결과 종료");
        finish.setOnClickListener(v -> finishActiveCaptureDialog());
        controlRow.addView(finish, weighted());

        Button abort = button("중단 · 데이터 유지");
        abort.setOnClickListener(v -> abortActiveCapture());
        controlRow.addView(abort, weighted());

        activeCaptureActions.addView(controlRow,
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
    }

    private String latestRawCandidateText() {
        String[] keys = rawKeysForTest(activeTestId);
        if (keys.length == 0) {
            return "최근 raw 후보 · 이 Test ID는 live vehicle raw 대상 아님 · event/evidence 로그 확인";
        }
        String filtered = filteredRawSnapshotForTest(activeTestId);
        if (filtered.isEmpty()) {
            return "최근 raw 후보(candidate only · PASS 아님) · 아직 없음";
        }
        return "최근 raw 후보(candidate only · PASS 아님) · " + filtered;
    }

    private String filteredRawSnapshotForTest(String testId) {
        return filterRawSnapshot(
                VerificationEvidenceRuntime.liveCorrelationSnapshot(),
                rawKeysForTest(testId));
    }

    private String[] rawKeysForTest(String testId) {
        if ("AUD-GEAR-001".equals(testId)) return new String[]{"gear_raw"};
        if ("AUD-DRV-001".equals(testId) || "AUD-DRV-002".equals(testId)) {
            return new String[]{"operation_raw"};
        }
        if ("AUD-REG-001".equals(testId) || "AUD-REG-002".equals(testId)) {
            return new String[]{"energy_feedback_raw"};
        }
        if ("AUD-SNOW-001".equals(testId) || "AUD-SNOW-002".equals(testId)) {
            return new String[]{"road_surface_raw"};
        }
        if ("AUD-AVH-001".equals(testId) || "AUD-AVH-002".equals(testId)) {
            return new String[]{"avh_raw","avh_enable_raw","speed_raw",
                    "brake_pedal_raw","brake_depth_raw","accel_depth_raw"};
        }
        if ("AUD-EPB-001".equals(testId)) return new String[]{"epb_raw"};
        if ("AUD-ICC-001".equals(testId)) return new String[]{"tja_raw"};
        if ("AUD-BSD-001".equals(testId)) {
            return new String[]{"bsd_raw","turn_left_raw","turn_right_raw"};
        }
        if ("AUD-LVDA-001".equals(testId)) {
            return new String[]{"radar_area7_raw","radar_area8_raw","speed_raw","tja_raw"};
        }
        if ("PARK-HAZ-001".equals(testId) || "PARK-HAZ-002".equals(testId)) {
            return new String[]{"gear_raw","hazard_raw"};
        }
        if ("ADAS-LDW-001".equals(testId) || "ADAS-LDW-002".equals(testId)) {
            return new String[]{"lane_offset_raw","lks_mode_raw","ldsw_type_raw","tja_raw"};
        }
        return new String[0];
    }

    private String filterRawSnapshot(String snapshot, String[] keys) {
        if (snapshot == null || snapshot.trim().isEmpty()) return "";
        java.util.LinkedHashSet<String> wanted = new java.util.LinkedHashSet<>();
        java.util.Collections.addAll(wanted, keys);
        StringBuilder out = new StringBuilder();
        for (String part : snapshot.split(";")) {
            int split = part.indexOf('=');
            if (split <= 0) continue;
            String key = part.substring(0, split).trim();
            if (!wanted.contains(key)) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(part.trim());
        }
        return out.toString();
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
                "Verification Center live correlation marker",
                filteredRawSnapshotForTest(activeTestId));
        refreshLedger();
        refreshActiveCaptureUi();
        Toast.makeText(this, "관찰 마커 기록: " + observation,
                Toast.LENGTH_SHORT).show();
    }

    private String[][] markerPlanForTest(String testId) {
        if ("AUD-GEAR-001".equals(testId)) return new String[][]{
                {"P","GEAR_P_VISIBLE"},{"R","GEAR_R_VISIBLE"},
                {"N","GEAR_N_VISIBLE"},{"D","GEAR_D_VISIBLE"}};
        if ("AUD-DRV-001".equals(testId)) return new String[][]{
                {"ECO","DRIVE_ECO_VISIBLE"},{"SPORT","DRIVE_SPORT_VISIBLE"}};
        if ("AUD-DRV-002".equals(testId)) return new String[][]{
                {"OEM NORMAL","OEM_NORMAL_VISIBLE"}};
        if ("AUD-REG-001".equals(testId)) return new String[][]{
                {"HIGH","REGEN_HIGH_VISIBLE"}};
        if ("AUD-REG-002".equals(testId)) return new String[][]{
                {"OEM STANDARD","OEM_STANDARD_VISIBLE"}};
        if ("AUD-SNOW-001".equals(testId)) return new String[][]{
                {"Snow ON","SNOW_ON_VISIBLE"}};
        if ("AUD-SNOW-002".equals(testId)) return new String[][]{
                {"Snow OFF","SNOW_OFF_VISIBLE"}};
        if ("AUD-AVH-001".equals(testId)) return new String[][]{
                {"버튼 ON","AUTOHOLD_SWITCH_ON_VISIBLE"},
                {"버튼 OFF","AUTOHOLD_SWITCH_OFF_VISIBLE"}};
        if ("AUD-AVH-002".equals(testId)) return new String[][]{
                {"체결 표시","AUTOHOLD_HELD_VISIBLE"},
                {"해제/출발","AUTOHOLD_RELEASE_VISIBLE"}};
        if ("AUD-EPB-001".equals(testId)) return new String[][]{
                {"EPB 체결","EPB_HELD_VISIBLE"},{"EPB 해제","EPB_RELEASED_VISIBLE"}};
        if ("AUD-ICC-001".equals(testId)) return new String[][]{
                {"ICC ON","ICC_ON_VISIBLE"},{"ICC OFF","ICC_OFF_VISIBLE"}};
        if ("AUD-BSD-001".equals(testId)) return new String[][]{
                {"좌 BSD+좌깜빡이","BSD_LEFT_CONTEXT_VISIBLE"},
                {"우 BSD+우깜빡이","BSD_RIGHT_CONTEXT_VISIBLE"}};
        if ("AUD-LVDA-001".equals(testId)) return new String[][]{
                {"전방차 출발","LEADING_CAR_DEPARTURE_VISIBLE"}};
        if ("PARK-HAZ-001".equals(testId)) return new String[][]{
                {"첫 R","PARKING_REVERSE_START_VISIBLE"},
                {"주차 변속","PARKING_GEAR_CHANGE_VISIBLE"},
                {"최종 P","PARKING_FINAL_P_VISIBLE"}};
        if ("PARK-HAZ-002".equals(testId)) return new String[][]{
                {"P 취소","PARKING_P_CANCEL_VISIBLE"},
                {"P 10초","PARKING_P_10S_VISIBLE"}};
        if ("ADAS-LDW-001".equals(testId)) return new String[][]{
                {"좌 이탈","LANE_LEFT_DEPARTURE_VISIBLE"},
                {"우 이탈","LANE_RIGHT_DEPARTURE_VISIBLE"}};
        if ("ADAS-LDW-002".equals(testId)) return new String[][]{
                {"보조 OFF 좌","LANE_LEFT_ASSIST_OFF_VISIBLE"},
                {"보조 OFF 우","LANE_RIGHT_ASSIST_OFF_VISIBLE"},
                {"ICC/ACC 좌","LANE_LEFT_ASSIST_ON_VISIBLE"},
                {"ICC/ACC 우","LANE_RIGHT_ASSIST_ON_VISIBLE"}};
        if ("WIN-SPLIT-001".equals(testId)) return new String[][]{
                {"2분할 보임","SPLIT_TWO_APP_VISIBLE"},
                {"카메라 복귀 정상","SPLIT_CAMERA_RETURN_OK"}};
        if ("WIN-SPLIT-002".equals(testId)) return new String[][]{
                {"바로가기 아이콘","SPLIT_SHORTCUT_ICON_VISIBLE"},
                {"바로가기 2분할","SPLIT_SHORTCUT_LAUNCH_VISIBLE"}};
        if ("AUD-DRVSPK-001".equals(testId)) return new String[][]{
                {"운전석 들림","DRIVER_SEAT_AUDIBLE"},
                {"타 좌석 무음","OTHER_SEATS_SILENT"},
                {"순정경고 정상","OEM_WARNING_PREEMPT_OK"}};
        if ("LCH-ID-001".equals(testId)) return new String[][]{
                {"앱서랍 아이콘","APP_DRAWER_ICON_VISIBLE"},
                {"일반 앱 실행","NORMAL_APP_LAUNCH_VISIBLE"}};
        return new String[0][0];
    }

    private String[] requiredMarkersForTest(String testId) {
        String[][] plan = markerPlanForTest(testId);
        String[] markers = new String[plan.length];
        for (int i = 0; i < plan.length; i++) markers[i] = plan[i][1];
        return markers;
    }

    private Map<String,Integer> activeMarkerCounts() {
        if (activeCorrelationId == null) return new java.util.LinkedHashMap<>();
        if ("AUD-LVDA-001".equals(activeTestId)) {
            return VerificationEvidenceRuntime.operatorObservationCountsWithRequiredRaw(
                    this, activeCorrelationId, rawKeysForTest(activeTestId));
        }
        if (rawKeysForTest(activeTestId).length > 0) {
            return VerificationEvidenceRuntime.operatorObservationCountsWithRaw(
                    this, activeCorrelationId);
        }
        return VerificationEvidenceRuntime.operatorObservationCounts(
                this, activeCorrelationId);
    }

    private boolean activeCorrelationReadyForPass() {
        if (activeCorrelationId == null) return false;
        String[] required = requiredMarkersForTest(activeTestId);
        if (required.length == 0) return true;
        Map<String,Integer> counts = activeMarkerCounts();
        for (String marker : required) {
            Integer count = counts.get(marker);
            if (count == null || count < 3) return false;
        }
        return true;
    }

    private String[] allowedOutcomesForTest(String testId) {
        JSONArray contracts = testContracts == null ? null : testContracts.optJSONArray("contracts");
        if (contracts != null) {
            for (int i = 0; i < contracts.length(); i++) {
                JSONObject contract = contracts.optJSONObject(i);
                if (contract == null || !testId.equals(contract.optString("test_id"))) continue;
                JSONArray allowed = contract.optJSONArray("allowed_outcomes");
                if (allowed == null || allowed.length() == 0) break;
                String[] values = new String[allowed.length()];
                for (int j = 0; j < allowed.length(); j++) values[j] = allowed.optString(j);
                return values;
            }
        }
        return new String[]{"NEED_MORE_DATA"};
    }

    private boolean containsOutcome(String[] outcomes, String value) {
        for (String outcome : outcomes) if (value.equals(outcome)) return true;
        return false;
    }

    private String[] removeOutcome(String[] outcomes, String value) {
        int count = 0;
        for (String outcome : outcomes) if (!value.equals(outcome)) count++;
        String[] filtered = new String[count];
        int index = 0;
        for (String outcome : outcomes) {
            if (!value.equals(outcome)) filtered[index++] = outcome;
        }
        return filtered;
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

        boolean passReady = activeCorrelationReadyForPass();
        boolean markerControlled = requiredMarkersForTest(activeTestId).length > 0;
        String[] outcomes = allowedOutcomesForTest(activeTestId);
        boolean contractAllowsPass = containsOutcome(outcomes, "PASS");
        if (markerControlled && !passReady && contractAllowsPass) {
            outcomes = removeOutcome(outcomes, "PASS");
        }

        String resultMessage = activeFeature == null ? "" : activeFeature.optString("requirement", "");
        if (!contractAllowsPass) {
            resultMessage += "\n\nTest Contract가 PASS를 허용하지 않습니다. 현재는 NEED_MORE_DATA/허용된 결과만 기록할 수 있습니다.";
        }
        if (markerControlled) {
            resultMessage += "\n\n" + markerProgressText(activeMarkerCounts());
            if (!passReady) {
                resultMessage += "\nPASS 잠금: 필요한 마커를 각각 3회 기록해야 합니다.";
            } else {
                resultMessage += "\n현재 세션의 마커 최소 반복 수는 충족했습니다. 전체 VERIFIED에는 별도 세션/실차 검토가 더 필요합니다.";
            }
        }

        final String[] finalOutcomes = outcomes;
        new AlertDialog.Builder(this)
                .setTitle(activeTestId + " 결과")
                .setMessage(resultMessage)
                .setView(note)
                .setItems(finalOutcomes, (dialog, which) ->
                        completeActiveCapture(finalOutcomes[which], note.getText().toString().trim(),
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

    private String uiQualityState(JSONObject audit) {
        if (audit == null) return "CAPTURE REQUIRED";
        JSONObject comparison=audit.optJSONObject("comparison");
        if (comparison!=null && comparison.optBoolean("regression",false)) {
            return "REGRESSION";
        }
        JSONObject summary=audit.optJSONObject("summary");
        if (summary==null) return "REVIEW";
        int touch=summary.optInt("touch_target_violations",0);
        int ellipsis=summary.optInt("ellipsized_texts",0);
        int clips=summary.optInt("partial_clips",0);
        return touch==0 && ellipsis==0 && clips==0 ? "CLEAN" : "REVIEW";
    }

    private String uiQualityLabel() {
        JSONObject audit=UiLayoutAuditRuntime.latestAudit(this);
        return "UI 화면 품질  ·  " + uiQualityState(audit);
    }

    private void applyUiQualityStyle(Button button) {
        String state=uiQualityState(UiLayoutAuditRuntime.latestAudit(this));
        if ("REGRESSION".equals(state)) {
            button.setTextColor(Color.parseColor("#FFD6DB"));
            button.setBackground(pressableGradientRound(
                    new String[]{"#351A20","#1C0F13"},
                    new String[]{"#49232B","#281419"},
                    16,"#7C3E49"));
        } else if ("CLEAN".equals(state)) {
            button.setTextColor(Color.parseColor("#B9FFE7"));
            button.setBackground(pressableGradientRound(
                    new String[]{"#12352F","#0A211D"},
                    new String[]{"#19493F","#0D302A"},
                    16,"#3B8E75"));
        } else if ("REVIEW".equals(state)) {
            button.setTextColor(Color.parseColor("#FFE4A8"));
            button.setBackground(pressableGradientRound(
                    new String[]{"#332A14","#1B160B"},
                    new String[]{"#493A18","#271E0D"},
                    16,"#665423"));
        }
    }

    private void showUiQualitySummary() {
        JSONObject audit=UiLayoutAuditRuntime.latestAudit(this);
        String state=uiQualityState(audit);

        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18),dp(16),dp(18),dp(16));
        panel.setBackground(gradientRound(
                new String[]{"#10252E","#07151B","#040A0E"},22,"#315A68"));

        LinearLayout head=new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout copy=new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title=text("UI QUALITY",21f,Color.WHITE,true);
        title.setLetterSpacing(0.08f);
        copy.addView(title);
        copy.addView(text("Golden Screenshot · layout audit",10f,
                Color.parseColor("#6E8C97"),false));
        head.addView(copy,new LinearLayout.LayoutParams(
                0,ViewGroup.LayoutParams.WRAP_CONTENT,1f));
        TextView stateChip=text(state,10f,
                "REGRESSION".equals(state)?Color.parseColor("#FF9EAA"):
                "CLEAN".equals(state)?Color.parseColor("#72E6B1"):
                Color.parseColor("#FFD166"),true);
        stateChip.setGravity(Gravity.CENTER);
        stateChip.setBackground(gradientRound(
                new String[]{"#15272E","#09171D"},14,"#365563"));
        head.addView(stateChip,new LinearLayout.LayoutParams(dp(132),dp(40)));
        panel.addView(head,new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,dp(58)));

        if (audit == null) {
            TextView empty=text(
                    "아직 HOME UI 검증 스냅샷이 없습니다.\n"
                            + "Launcher 설정 → UI 검증 스냅샷을 실행하면 PNG와 layout audit가 생성됩니다.",
                    12f,Color.parseColor("#A3B8C0"),false);
            empty.setPadding(dp(12),dp(14),dp(12),dp(14));
            empty.setBackground(gradientRound(
                    new String[]{"#0D2027","#071318"},15,"#254957"));
            panel.addView(empty);
        } else {
            JSONObject summary=audit.optJSONObject("summary");
            JSONObject comparison=audit.optJSONObject("comparison");
            int touch=summary==null?0:summary.optInt("touch_target_violations",0);
            int ellipsis=summary==null?0:summary.optInt("ellipsized_texts",0);
            int clips=summary==null?0:summary.optInt("partial_clips",0);

            LinearLayout row1=new LinearLayout(this);
            row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.addView(qualityMetric("TOUCH <48dp",String.valueOf(touch)),weighted());
            row1.addView(qualityMetric("ELLIPSIS",String.valueOf(ellipsis)),weighted());
            row1.addView(qualityMetric("CLIPPING",String.valueOf(clips)),weighted());
            panel.addView(row1,new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,dp(76)));

            LinearLayout row2=new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.addView(qualityMetric("DPI",String.valueOf(audit.optInt("density_dpi",-1))),weighted());
            row2.addView(qualityMetric("FONT",String.format(
                    Locale.US,"%.2f",audit.optDouble("font_scale",-1))),weighted());
            row2.addView(qualityMetric("SCREEN",
                    audit.optInt("width_px",-1)+"×"+audit.optInt("height_px",-1)),weighted());
            panel.addView(row2,new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,dp(76)));

            if(comparison!=null && comparison.optBoolean("baseline_available",false)){
                String delta="Δ touch "+signed(comparison.optInt("delta_touch",0))
                        +" · ellipsis "+signed(comparison.optInt("delta_ellipsis",0))
                        +" · clip "+signed(comparison.optInt("delta_clips",0));
                TextView compare=text(delta,11f,Color.parseColor("#A8C1C8"),false);
                compare.setGravity(Gravity.CENTER_VERTICAL);
                compare.setPadding(dp(12),0,dp(12),0);
                compare.setBackground(gradientRound(
                        new String[]{"#0D2027","#071318"},14,"#254957"));
                panel.addView(compare,new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,dp(42)));
            }
        }

        JSONObject frame=UiFrameTimingRuntime.latest(this);
        if(frame!=null){
            LinearLayout perf=new LinearLayout(this);
            perf.setOrientation(LinearLayout.HORIZONTAL);
            perf.addView(qualityMetric("FRAME P95",
                    String.format(Locale.US,"%.1f ms",frame.optDouble("p95_ms",0))),weighted());
            perf.addView(qualityMetric("FRAME MAX",
                    String.format(Locale.US,"%.1f ms",frame.optDouble("max_ms",0))),weighted());
            perf.addView(qualityMetric(">32 ms",
                    String.valueOf(frame.optInt("over_32ms",0))),weighted());
            panel.addView(perf,new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,dp(76)));

            TextView perfNote=text(
                    "Frame timing은 diagnostic-only · refresh "
                            +String.format(Locale.US,"%.1f Hz",frame.optDouble("refresh_rate_hz",0)),
                    9.5f,Color.parseColor("#6F8994"),false);
            perfNote.setGravity(Gravity.CENTER);
            panel.addView(perfNote,new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,dp(30)));
        }

        VerificationEvidenceRuntime.recordPassiveEvent(
                this,"UI_QUALITY_SUMMARY_OPENED",
                "state="+state+";frame_timing_available="+(frame!=null));

        new AlertDialog.Builder(this)
                .setView(panel)
                .setPositiveButton("확인",null)
                .show();
    }

    private View qualityMetric(String label,String value) {
        LinearLayout card=new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8),dp(8),dp(8),dp(8));
        card.setBackground(gradientRound(
                new String[]{"#0E222A","#07151B"},15,"#284B57"));
        TextView l=text(label,9.5f,Color.parseColor("#7897A1"),true);
        l.setLetterSpacing(0.06f);
        card.addView(l);
        card.addView(text(value,14f,Color.WHITE,true));
        return card;
    }

    private String signed(int value) {
        return value>0 ? "+"+value : String.valueOf(value);
    }

    private TextView statusCard(String label, String value) {
        TextView card = text(label + "\n" + value, 14f, Color.WHITE, true);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(8), dp(12), dp(8), dp(12));
        card.setBackground(gradientRound(
                new String[]{"#102730","#09171D"}, 16, "#2A5362"));
        return card;
    }

    private TextView summaryChip(String label, String value) {
        TextView chip = text(label + "\n" + value, 12f, Color.WHITE, true);
        chip.setGravity(Gravity.CENTER);
        chip.setBackground(gradientRound(
                new String[]{"#0D222A","#071419"}, 14, "#24424F"));
        return chip;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12f);
        b.setAllCaps(false);
        b.setBackground(pressableGradientRound(
                new String[]{"#102831","#09171D"},
                new String[]{"#173B47","#0D252E"},
                14, "#2A5362"));
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

    private GradientDrawable gradientRound(String[] colors, int radiusDp, String stroke) {
        int[] parsed = new int[colors.length];
        for (int i = 0; i < colors.length; i++) parsed[i] = Color.parseColor(colors[i]);
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, parsed);
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(dp(radiusDp));
        if (stroke != null) d.setStroke(dp(1), Color.parseColor(stroke));
        return d;
    }

    private Drawable pressableGradientRound(
            String[] normalColors, String[] pressedColors, int radiusDp, String stroke) {
        android.graphics.drawable.StateListDrawable states =
                new android.graphics.drawable.StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                gradientRound(pressedColors, radiusDp, stroke));
        states.addState(new int[]{},
                gradientRound(normalColors, radiusDp, stroke));
        return states;
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
