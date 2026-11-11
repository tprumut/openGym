package ch.duartesantos.opengym.watch

import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors openGym's doFinishWorkout(): append once, compute volume, PR ids and
 * exWeights against the freshly-read state, then bump _ts.
 */
object WorkoutMerger {
    data class Result(
        val state: JSONObject,
        val workoutId: String,
        val duplicate: Boolean,
        val volume: Double,
        val prs: List<String>
    )

    private val setKeys = listOf("w", "r", "sec", "min", "speed", "rir", "rpe")

    fun merge(state: JSONObject, workout: JSONObject): Result {
        val id = workout.optString("id").trim()
        if (id.isBlank() || id.length > 200) bad("workout.id is missing or too long")

        val workouts = state.optJSONArray("workouts") ?: JSONArray()
        for (i in 0 until workouts.length()) {
            if (workouts.optJSONObject(i)?.optString("id") == id) {
                return Result(state, id, duplicate = true, volume = 0.0, prs = emptyList())
            }
        }

        val date = workout.optString("d").trim()
        if (!DATE.matches(date)) bad("workout.d must be an ISO date")

        val start = workout.optLong("start", -1L)
        val end = workout.optLong("end", -1L)
        if (start <= 0L || end <= 0L || end < start) bad("workout.start/end are invalid")

        val name = workout.optString("name", "Workout").trim().take(120).ifBlank { "Workout" }
        val routineId = if (workout.has("routineId") && !workout.isNull("routineId")) {
            workout.optString("routineId").take(200)
        } else {
            null
        }
        val bw = number(workout, "bw")

        val sourceEntries = workout.optJSONArray("entries")
            ?: bad("workout.entries is missing")
        if (sourceEntries.length() == 0 || sourceEntries.length() > 200) {
            bad("workout.entries is empty or too large")
        }

        val entries = JSONArray()
        for (i in 0 until sourceEntries.length()) {
            val source = sourceEntries.optJSONObject(i) ?: bad("workout.entries[$i] is invalid")
            val exerciseId = source.optString("id").trim()
            if (exerciseId.isBlank()) bad("workout.entries[$i].id is missing")

            val sourceSets = source.optJSONArray("sets")
                ?: bad("workout.entries[$i].sets is missing")
            if (sourceSets.length() == 0 || sourceSets.length() > 100) {
                bad("workout.entries[$i].sets is empty or too large")
            }

            val sets = JSONArray()
            var hasDone = false
            for (j in 0 until sourceSets.length()) {
                val sourceSet = sourceSets.optJSONObject(j)
                    ?: bad("workout.entries[$i].sets[$j] is invalid")
                val set = cleanSet(sourceSet)
                if (set.optBoolean("done")) hasDone = true
                sets.put(set)
            }
            if (!hasDone) continue

            var topW = number(source, "topW") ?: 0.0
            if (topW <= 0.0) topW = maxDoneWeight(sets)

            val entry = JSONObject()
                .put("id", exerciseId)
                .put("sets", sets)
                .put("target", source.optJSONObject("target")?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)
                .put("topW", if (topW > 0.0) topW else JSONObject.NULL)
            entries.put(entry)
        }

        if (entries.length() == 0) bad("workout has no completed sets")

        val prs = mutableListOf<String>()
        val exWeights = state.optJSONObject("exWeights") ?: JSONObject()
        var volume = 0.0

        for (i in 0 until entries.length()) {
            val entry = entries.getJSONObject(i)
            val exerciseId = entry.getString("id")
            val sets = entry.getJSONArray("sets")
            var maxDoneWeight = 0.0
            var maxWeight = entry.optDouble("topW", 0.0)

            for (j in 0 until sets.length()) {
                val set = sets.getJSONObject(j)
                if (!set.optBoolean("done")) continue
                val w = set.optDouble("w", 0.0)
                val r = set.optDouble("r", 0.0)
                volume += w * r
                if (w > maxDoneWeight) maxDoneWeight = w
                if (w > maxWeight) maxWeight = w
            }

            if (maxDoneWeight > 0.0 && maxDoneWeight > bestWeightFor(workouts, exerciseId)) {
                prs.add(exerciseId)
            }

            if (maxWeight > 0.0) {
                val current = exWeights.optJSONObject(exerciseId)
                val currentWeight = current?.optDouble("w", 0.0) ?: 0.0
                if (current == null || maxWeight > currentWeight) {
                    exWeights.put(
                        exerciseId,
                        JSONObject().put("w", maxWeight).put("d", date)
                    )
                }
            }
        }

        val clean = JSONObject()
            .put("id", id)
            .put("d", date)
            .put("start", start)
            .put("end", end)
            .put("routineId", routineId ?: JSONObject.NULL)
            .put("name", name)
            .put("bw", bw ?: JSONObject.NULL)
            .put("entries", entries)
            .put("vol", volume)
            .put("prs", JSONArray(prs))

        workouts.put(clean)
        state.put("workouts", workouts)
        state.put("exWeights", exWeights)
        state.put("_ts", System.currentTimeMillis())
        state.remove("active")

        return Result(state, id, duplicate = false, volume = volume, prs = prs)
    }

    private fun cleanSet(source: JSONObject): JSONObject {
        val out = JSONObject()
        for (key in setKeys) {
            val value = number(source, key) ?: continue
            out.put(key, value)
        }
        out.put("done", source.optBoolean("done", false))
        return out
    }

    private fun maxDoneWeight(sets: JSONArray): Double {
        var max = 0.0
        for (i in 0 until sets.length()) {
            val set = sets.getJSONObject(i)
            if (!set.optBoolean("done")) continue
            val w = set.optDouble("w", 0.0)
            if (w > max) max = w
        }
        return max
    }

    private fun bestWeightFor(workouts: JSONArray, exerciseId: String): Double {
        var best = 0.0
        for (i in 0 until workouts.length()) {
            val workout = workouts.optJSONObject(i) ?: continue
            val entries = workout.optJSONArray("entries") ?: continue
            for (j in 0 until entries.length()) {
                val entry = entries.optJSONObject(j) ?: continue
                if (entry.optString("id") != exerciseId) continue

                val topW = entry.optDouble("topW", 0.0)
                if (topW > best) best = topW

                val sets = entry.optJSONArray("sets") ?: continue
                for (k in 0 until sets.length()) {
                    val set = sets.optJSONObject(k) ?: continue
                    if (!set.optBoolean("done")) continue
                    val w = set.optDouble("w", 0.0)
                    if (w > best) best = w
                }
            }
        }
        return best
    }

    private fun number(source: JSONObject, key: String): Double? {
        if (!source.has(key) || source.isNull(key)) return null
        val value = source.optDouble(key, Double.NaN)
        if (value.isNaN() || value.isInfinite()) bad("$key is not a number")
        return value
    }

    private fun bad(message: String): Nothing = throw ApiException("bad-request", message)

    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
}
