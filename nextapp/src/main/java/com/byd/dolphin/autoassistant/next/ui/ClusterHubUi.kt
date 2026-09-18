package com.byd.dolphin.autoassistant.next.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.byd.dolphin.autoassistant.next.cluster.ClusterHubLab
import com.byd.dolphin.autoassistant.next.cluster.ClusterResourceInspector
import com.byd.dolphin.autoassistant.next.cluster.TbtCorrelationProbe
import com.byd.dolphin.autoassistant.next.integrated.InstalledAppItem
import com.byd.dolphin.autoassistant.next.integrated.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CHBg = Color(0xFF0B151E)
private val CHBorder = Color(0xFF28404E)
private val CHText = Color(0xFFF2F8FA)
private val CHMuted = Color(0xFF91A4B3)
private val CHCyan = Color(0xFF3AD7FF)
private val CHGreen = Color(0xFF4AD995)
private val CHAmber = Color(0xFFFFC857)
private val CHRed = Color(0xFFFF6B6B)

@Composable
fun ClusterHubPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val apps = remember { InstalledApps.launcherApps(context) }

    val tbtCorrelation by TbtCorrelationProbe.state.collectAsState()

    var selectedPackage by remember { mutableStateOf(apps.firstOrNull()?.packageName.orEmpty()) }
    var picker by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            "계기판 Display/Surface를 먼저 스캔하세요. 앱 투사·커스텀 UI는 P/정차 + 보조 Display 확인 시에만 실행합니다."
        )
    }

    Text(
        "CLUSTER HUB",
        color = CHText,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
    )
    Text(
        "Native TBT · App Projection · Custom UI · Theme/Qt/RCC · Surface/Display · Stock Restore",
        color = CHMuted,
        fontSize = 9.sp
    )
    Spacer(Modifier.height(8.dp))

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = CHBg,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, CHBorder)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Text(
                    "투사 앱",
                    color = CHMuted,
                    fontSize = 9.sp,
                    modifier = Modifier.width(52.dp)
                )
                HubButton(
                    apps.firstOrNull { it.packageName == selectedPackage }?.label ?: "앱 선택",
                    CHCyan
                ) { picker = true }
            }

            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HubButton("CAPABILITY SCAN", CHCyan) {
                    scope.launch {
                        val report = withContext(Dispatchers.IO) {
                            ClusterHubLab.scan(context)
                        }
                        status =
                            "display=" + report.displays.joinToString { d ->
                                d.id.toString() + ":" + d.name + " " +
                                    d.width + "x" + d.height +
                                    if (d.isDefault) "(MAIN)" else "(SECONDARY)"
                            } +
                            " · instrumentMethods=" + report.instrumentMethods.size +
                            " · surfaceMethods=" + report.surfaceMethods.size +
                            " · themes=" + report.themeCandidates.size
                    }
                }

                HubButton("NATIVE TBT TEST", CHGreen) {
                    val result = ClusterHubLab.sendNativeTbtTest(context)
                    status = "Native TBT success=" + result.success + " · " + result.detail
                }

                HubButton("APP → CLUSTER LAB", CHAmber) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ClusterHubLab.projectInstalledApp(context, selectedPackage)
                        }
                        status = "App projection success=" + result.success + " · " + result.detail
                    }
                }

                HubButton("CUSTOM UI LAB", CHAmber) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ClusterHubLab.launchCustomClusterUi(context)
                        }
                        status = "Custom UI success=" + result.success + " · " + result.detail
                    }
                }
            }

            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HubButton("DISPLAY / SURFACE SCAN", CHCyan) {
                    scope.launch {
                        val text = withContext(Dispatchers.IO) {
                            ClusterHubLab.scanProjectionInternals(context)
                        }
                        status = text.lineSequence().take(10).joinToString(" | ").take(900)
                    }
                }

                HubButton("THEME · QT · RCC SCAN", CHRed) {
                    scope.launch {
                        val list = withContext(Dispatchers.IO) {
                            ClusterHubLab.scanThemeCandidates(context)
                        }
                        status = if (list.isEmpty()) {
                            "테마/API/RCC 후보 미발견 · 전체 로그 확인"
                        } else {
                            "후보 " + list.size + "개 · " + list.take(8).joinToString(" | ")
                        }
                    }
                }

                HubButton("RESOURCE INSPECTOR", CHRed) {
                    scope.launch {
                        status = "Cluster Resource Inspector 실행 중…"
                        val report = withContext(Dispatchers.IO) {
                            ClusterResourceInspector.inspect(context)
                        }
                        status = report.result + " · files=" + report.files.size +
                            " · read-only inventory logged"
                    }
                }

                HubButton("STOCK RESTORE", CHGreen) {
                    val result = ClusterHubLab.restoreStock(context)
                    status = result.detail
                }
            }

            Spacer(Modifier.height(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                HubButton(
                    if (tbtCorrelation.active) "TBT LISTENING…" else "TBT CORRELATION 60s",
                    CHAmber
                ) {
                    if (tbtCorrelation.active) {
                        TbtCorrelationProbe.stopWindow()
                        status = "TBT correlation stopped"
                    } else {
                        TbtCorrelationProbe.startWindow(context, 60_000L)
                        status = "60초 동안 순정 TMAP/내비 안내를 발생시키세요. broadcast/service/logcat을 read-only로 관찰합니다."
                    }
                }
                HubButton("TBT RESULT", CHCyan) {
                    status =
                        tbtCorrelation.result +
                        " · events=" + tbtCorrelation.eventCount +
                        " · receivers=" + tbtCorrelation.receiverCandidates.size +
                        " · packages=" + tbtCorrelation.packageCandidates.size
                }
            }

            if (tbtCorrelation.active || tbtCorrelation.result != "IDLE") {
                Spacer(Modifier.height(6.dp))
                Text(
                    "TBT · " + tbtCorrelation.result +
                        " · events " + tbtCorrelation.eventCount +
                        " · receiver " + tbtCorrelation.receiverCandidates.size,
                    color = if (tbtCorrelation.eventCount > 0) CHGreen else CHAmber,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(9.dp))
            Text(
                status,
                color = CHMuted,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HubBadge("TBT · BETA", CHAmber)
                HubBadge("APP PROJECTION · LAB", CHRed)
                HubBadge("THEME / CUSTOM UI · LAB", CHRed)
                HubBadge("OEM SAFETY LAYER PRESERVE", CHGreen)
            }
        }
    }

    if (picker) {
        ClusterAppPicker(
            apps = apps,
            onDismiss = { picker = false },
            onSelect = {
                selectedPackage = it.packageName
                picker = false
            }
        )
    }
}

@Composable
private fun HubButton(text: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.13f),
            contentColor = CHText
        ),
        border = BorderStroke(1.dp, color.copy(alpha = 0.55f)),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(text, fontSize = 8.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HubBadge(text: String, color: Color) {
    Surface(
        color = color.copy(alpha = 0.10f),
        shape = RoundedCornerShape(9.dp),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text,
            color = color,
            fontSize = 7.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun ClusterAppPicker(
    apps: List<InstalledAppItem>,
    onDismiss: () -> Unit,
    onSelect: (InstalledAppItem) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("계기판 투사 앱 선택") },
        text = {
            Column(
                Modifier
                    .height(430.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                apps.forEach { app ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(app) }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            app.label,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            app.packageName,
                            fontSize = 8.sp,
                            color = CHMuted
                        )
                    }
                }
            }
        },
        confirmButton = {
            HubButton("닫기", CHCyan, onDismiss)
        }
    )
}
