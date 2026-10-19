package com.rm.apogee

import android.app.Activity
import android.os.Bundle
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Optional on-device checks, compiled and optimised with the app by -PperfVerify. */
class PerfValidationActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Thread({ verify() }, "r8-validation").start()
    }

    private fun verify() {
        val cases: List<Pair<String, (OptimizedAppChecks) -> Unit>> = listOf(
            "unoptimized-save-import" to { it.readsAnUnoptimizedWorldFixture() },
            "save-staging-backup" to { it.stagedSolidsKeepFuelAndStateAcrossSaveAndBackupRecovery() },
            "career" to { it.careerUnlocksAndLegacyBoosterGrantSurviveSave() },
            "craft-import-export-provider" to { it.sharedCraftImportExportAndMalformedFiles() },
            "protobuf-tcp" to { it.protobufCommandsAndRealTcpHandshake() },
            "two-game-clients" to { it.twoGameClientsExchangeStructuresAndCommands() },
            "native-audio" to { it.nativeAudioStartsPublishesAndStops() },
        )
        val results = JSONArray()
        var failures = 0
        for ((name, run) in cases) {
            val started = android.os.SystemClock.elapsedRealtime()
            val checks = OptimizedAppChecks(applicationContext)
            val result = JSONObject().put("name", name)
            try {
                checks.perfOnly()
                run(checks)
                result.put("passed", true)
                Log.i("ApogeeR8", "$name PASS")
            } catch (error: Throwable) {
                failures++
                result.put("passed", false).put("error", Log.getStackTraceString(error))
                Log.e("ApogeeR8", "$name FAIL", error)
            } finally { checks.cleanup() }
            results.put(result.put("milliseconds", android.os.SystemClock.elapsedRealtime() - started))
        }
        val report = JSONObject().put("tests", cases.size).put("failures", failures).put("results", results)
        File(getExternalFilesDir(null)!!, "r8-validation.json").writeText(report.toString(2))
        Log.i("ApogeeR8", "finished: ${cases.size} checks, $failures failures")
        runOnUiThread { finish() }
    }
}
