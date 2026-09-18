package com.byd.dolphin.autoassistant.next.cluster

import android.content.Context
import com.byd.dolphin.autoassistant.next.core.NextLogger
import com.byd.dolphin.autoassistant.next.system.NextAdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ClusterResourceReport(
    val files: List<String>,
    val rccInventory: String,
    val libraryInventory: String,
    val qmlFontTranslationHints: String,
    val result: String
)

object ClusterResourceInspector {
    suspend fun inspect(context: Context): ClusterResourceReport =
        withContext(Dispatchers.IO) {
            if (!NextAdb.isPortOpen()) {
                val report = ClusterResourceReport(
                    files = emptyList(),
                    rccInventory = "",
                    libraryInventory = "",
                    qmlFontTranslationHints = "",
                    result = "LOCAL ADB unavailable"
                )
                NextLogger.w("CLUSTER_RESOURCE", report.result)
                return@withContext report
            }

            val find = NextAdb.shell(
                context,
                "sh -c \"find /system /vendor /product /data -maxdepth 7 -type f " +
                    "\\( -iname 'cluster_theme*.rcc' -o -iname '*cluster*.rcc' -o " +
                    "-iname '*.qml' -o -iname '*.qm' -o -iname '*cluster*.so' -o " +
                    "-iname 'libBydCluster.so' -o -iname '*.ttf' -o -iname '*.otf' \\) " +
                    "2>/dev/null | head -n 420\""
            )

            val files = find.output.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .toList()

            val rccFiles = files.filter { it.endsWith(".rcc", true) }.take(18)
            val libraryFiles = files.filter {
                it.endsWith(".so", true) &&
                    (it.contains("cluster", true) || it.contains("BydCluster", true))
            }.take(18)

            val rccInventory = buildString {
                rccFiles.forEach { path ->
                    append("### ")
                    append(path)
                    append("\n")
                    val safe = shellQuote(path)
                    val meta = NextAdb.shell(
                        context,
                        "sh -c \"ls -ln " + safe +
                            " 2>/dev/null; sha256sum " + safe + " 2>/dev/null\""
                    )
                    append(meta.output.take(2200))
                    if (!meta.output.endsWith("\n")) append("\n")
                }
            }.take(24_000)

            val libraryInventory = buildString {
                libraryFiles.forEach { path ->
                    append("### ")
                    append(path)
                    append("\n")
                    val safe = shellQuote(path)
                    val meta = NextAdb.shell(
                        context,
                        "sh -c \"ls -ln " + safe +
                            " 2>/dev/null; sha256sum " + safe + " 2>/dev/null\""
                    )
                    append(meta.output.take(2200))
                    if (!meta.output.endsWith("\n")) append("\n")
                }
            }.take(24_000)

            val inspectTargets = (rccFiles + libraryFiles).distinct().take(24)
            val hints = buildString {
                inspectTargets.forEach { path ->
                    append("### ")
                    append(path)
                    append("\n")
                    val safe = shellQuote(path)
                    val strings = NextAdb.shell(
                        context,
                        "sh -c \"strings -n 4 " + safe +
                            " 2>/dev/null | grep -Ei " +
                            "'qml|qrc:|font|ttf|otf|translation|\\.qm|theme|skin|cluster|speed|navi|warning|adas' " +
                            "| head -n 140\""
                    )
                    append(strings.output.take(7000))
                    if (!strings.output.endsWith("\n")) append("\n")
                }
            }.take(48_000)

            val result = when {
                rccFiles.isNotEmpty() && libraryFiles.isNotEmpty() ->
                    "RCC + cluster library candidates found"
                rccFiles.isNotEmpty() ->
                    "RCC candidates found · cluster library not confirmed"
                libraryFiles.isNotEmpty() ->
                    "cluster library candidates found · RCC not confirmed"
                else ->
                    "no cluster RCC/library candidate found"
            }

            NextLogger.i(
                "CLUSTER_RESOURCE",
                "result=" + result +
                    " files=" + files.size +
                    " rcc=" + rccFiles.size +
                    " libs=" + libraryFiles.size
            )
            NextLogger.i(
                "CLUSTER_RESOURCE_RAWS",
                ("FILES\n" + files.joinToString("\n") +
                    "\nRCC\n" + rccInventory +
                    "\nLIBS\n" + libraryInventory +
                    "\nHINTS\n" + hints).take(80_000)
            )

            ClusterResourceReport(
                files = files,
                rccInventory = rccInventory,
                libraryInventory = libraryInventory,
                qmlFontTranslationHints = hints,
                result = result
            )
        }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
