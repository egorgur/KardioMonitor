package ru.kardiomonitor.bandprotocol.xiaomi

import java.io.File
import java.io.IOException
import org.json.JSONObject

internal class XiaomiReverseReferenceResult {
    enum class Stage { PREPARE, OPEN, HISTORY, SAMPLE_READY, AWAIT_REFERENCE, CAPTURE, COMPLETE, FAILED }
    var stage = Stage.PREPARE
    var latestSelection = false
    var windowRelation: XiaomiHistoryReferenceSample.WindowRelation? = null
    var failureStage: String? = null
    var sampleVerified = false
    var sampleRepeatVerified = false
    var sampleRetentionUnchanged = false
    var clientClosedBeforeReference = false
    var keyRemoved = false
    var requestRemoved = false
    var signalRemoved = false
    var referenceCaptured = false
    var clinicalImportBlocked = false
    var sampleMemoryCleared = false
    var comparison: XiaomiHistoryReferenceComparison? = null
    val passed: Boolean get() = stage == Stage.COMPLETE && failureStage == null && sampleVerified &&
        sampleRepeatVerified && sampleRetentionUnchanged && clientClosedBeforeReference && keyRemoved &&
        requestRemoved && signalRemoved && referenceCaptured && clinicalImportBlocked && sampleMemoryCleared &&
        comparison?.matches == true && (!latestSelection || windowRelation == XiaomiHistoryReferenceSample.WindowRelation.INSIDE)

    fun write(file: File) {
        val json = JSONObject().put("schema_version", if (latestSelection) 2 else 1).put("stage", stage.name.lowercase(java.util.Locale.ROOT))
            .put("failure_stage", failureStage ?: JSONObject.NULL).put("test_passed", passed)
            .put("sample_verified", sampleVerified).put("sample_repeat_verified", sampleRepeatVerified)
            .put("sample_retention_unchanged", sampleRetentionUnchanged)
            .put("client_closed_before_reference", clientClosedBeforeReference)
            .put("key_removed", keyRemoved).put("request_removed", requestRemoved).put("signal_removed", signalRemoved)
            .put("reference_captured", referenceCaptured).put("clinical_import_blocked", clinicalImportBlocked)
            .put("sample_memory_cleared", sampleMemoryCleared)
            .put("reference_record_located", comparison?.recordLocated ?: JSONObject.NULL)
            .put("reference_timezone_matches", comparison?.timezoneMatches ?: JSONObject.NULL)
            .put("reference_heart_rate_matches", comparison?.heartRateMatches ?: JSONObject.NULL)
            .put("reference_steps_match", comparison?.stepsMatch ?: JSONObject.NULL)
        if (latestSelection) json.put("sample_selection", "latest")
            .put("reference_window_relation", windowRelation?.name?.lowercase(java.util.Locale.ROOT) ?: JSONObject.NULL)
        val temporary = File(file.parentFile, "reverse-result.tmp")
        try {
            temporary.writeText(json.toString(), Charsets.US_ASCII)
            if (!temporary.renameTo(file)) throw IOException()
        } catch (_: Exception) { throw IOException("reverse_reference_report_failed") }
        finally { temporary.delete() }
    }
    override fun toString(): String = "XiaomiReverseReferenceResult"
}
