package io.github.liminalium.phonelab.syscheck

import android.content.Context
import org.json.JSONObject

enum class Status { OK, WARN, FAIL, SKIPPED }

/** What one check found: a verdict, a one-line summary, and everything it read. */
data class Outcome(val status: Status, val headline: String, val data: JSONObject)

enum class Kind {
    /** Reads system state. Fast, needs no permissions, runs on every check. */
    INFO,

    /** Exercises hardware (camera, mic, radios). Runs when the user asks. */
    TEST,
}

class Check(
    val id: String,
    val title: String,
    val kind: Kind,
    val permissions: List<String> = emptyList(),
    val run: suspend (Context) -> Outcome,
)

sealed interface CheckState {
    data object Pending : CheckState
    data object Running : CheckState
    data class Done(val outcome: Outcome) : CheckState
}
