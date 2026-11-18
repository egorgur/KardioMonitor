package ru.kardiomonitor.features.rehab.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import ru.kardiomonitor.features.rehab.domain.DailyTargets
import ru.kardiomonitor.features.rehab.domain.ProgramRevision
import ru.kardiomonitor.features.rehab.domain.RehabPlan
import ru.kardiomonitor.features.rehab.domain.RehabPlanRepository
import ru.kardiomonitor.features.rehab.domain.RehabProgram
import ru.kardiomonitor.features.rehab.domain.RehabStage
import ru.kardiomonitor.features.rehab.domain.TrainingHeartRateZone
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/** Кеш известных версий плана одного пациента в SharedPreferences. */
class SharedPreferencesRehabPlanRepository(context: Context) : RehabPlanRepository {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val current = MutableStateFlow<RehabPlan?>(null)
    private var selectedPatientId: String? = null

    override val plan: StateFlow<RehabPlan?> = current.asStateFlow()

    override fun selectPatient(patientId: String?) {
        if (selectedPatientId == patientId) return
        selectedPatientId = patientId
        current.value = load(patientId)
    }

    override fun save(patientId: String, plan: RehabPlan) {
        require(selectedPatientId == patientId) { "нельзя сохранить план другого пациента" }
        val saved = preferences.edit()
            .putString(PATIENT_ID, patientId)
            .putString(PLAN, RehabPlanJson.encode(plan).toString())
            .commit()
        if (!saved) throw IOException("не удалось сохранить план реабилитации")
        current.value = plan
    }

    private fun load(patientId: String?): RehabPlan? {
        if (patientId == null || preferences.getString(PATIENT_ID, null) != patientId) return null
        val text = preferences.getString(PLAN, null) ?: return null
        return try {
            RehabPlanJson.decode(JSONObject(text))
        } catch (e: Exception) {
            Log.w(TAG, "сохранённый план реабилитации повреждён — будет загружен снова", e)
            null
        }
    }

    private companion object {
        const val TAG = "KardioServer"
        const val PREFERENCES = "rehab_plan"
        const val PATIENT_ID = "patient_id"
        const val PLAN = "plan"
    }
}

internal object RehabPlanJson {
    fun encode(plan: RehabPlan): JSONObject = JSONObject()
        .put("time_zone", plan.timeZone.id)
        .put("revisions", JSONArray(plan.revisions.map(::revision)))

    fun decode(json: JSONObject): RehabPlan {
        val revisions = json.getJSONArray("revisions")
        return RehabPlan(
            ZoneId.of(json.getString("time_zone")),
            List(revisions.length()) { index -> revision(revisions.getJSONObject(index)) },
        )
    }

    private fun revision(revision: ProgramRevision): JSONObject = JSONObject()
        .put("effective_from", revision.effectiveFrom.toString())
        .put("walking_steps_threshold", revision.walkingStepsThreshold)
        .put("program", program(revision.program))

    private fun revision(json: JSONObject): ProgramRevision = ProgramRevision(
        effectiveFrom = LocalDate.parse(json.getString("effective_from")),
        walkingStepsThreshold = json.getInt("walking_steps_threshold"),
        program = program(json.getJSONObject("program")),
    )

    private fun program(program: RehabProgram): JSONObject = JSONObject()
        .put("start_date", program.startDate.toString())
        .put("duration_days", program.durationDays)
        .put("stages", JSONArray(program.stages.map(::stage)))

    private fun program(json: JSONObject): RehabProgram {
        val stages = json.getJSONArray("stages")
        return RehabProgram(
            startDate = LocalDate.parse(json.getString("start_date")),
            durationDays = json.getInt("duration_days"),
            stages = List(stages.length()) { index -> stage(stages.getJSONObject(index)) },
        )
    }

    private fun stage(stage: RehabStage): JSONObject = JSONObject()
        .put("first_day", stage.firstDay)
        .put("last_day", stage.lastDay)
        .put("training_minutes", stage.targets.trainingMinutes)
        .put("steps", stage.targets.steps)
        .put("lower_bpm", stage.targets.trainingZone.lowerBpm)
        .put("upper_bpm", stage.targets.trainingZone.upperBpm)

    private fun stage(json: JSONObject): RehabStage = RehabStage(
        firstDay = json.getInt("first_day"),
        lastDay = json.getInt("last_day"),
        targets = DailyTargets(
            trainingZone = TrainingHeartRateZone(json.getInt("lower_bpm"), json.getInt("upper_bpm")),
            trainingMinutes = json.getInt("training_minutes"),
            steps = json.getInt("steps"),
        ),
    )
}
