package io.github.liminalium.phonelab.syscheck

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.liminalium.phonelab.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

class SystemCheckViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()

    val checks = Checks.all
    val states = mutableStateMapOf<String, CheckState>()

    var createdAt by mutableStateOf<Instant?>(null)
        private set
    var readingInfo by mutableStateOf(false)
        private set
    var testing by mutableStateOf(false)
        private set
    var savedFile by mutableStateOf<File?>(null)
        private set
    var exportedPath by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)

    private var runId: String? = null
    private var exportUri: Uri? = null

    val busy get() = readingInfo || testing

    init {
        // First launch runs the check; later launches show the last saved report.
        val latest = ReportStore.latest(ctx)
        if (latest == null || !load(latest)) runInfo()
    }

    fun runInfo() {
        if (busy) return
        viewModelScope.launch {
            readingInfo = true
            val now = Instant.now()
            runId = ReportStore.idFor(now)
            createdAt = now
            exportUri = null
            exportedPath = null
            states.clear()
            checks.forEach { states[it.id] = CheckState.Pending }
            checks.filter { it.kind == Kind.INFO }.forEach { runOne(it) }
            readingInfo = false
            persist(export = true)
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    /** Call after the permission dialog; tests without their permission are marked skipped. */
    fun runTests() {
        if (busy || runId == null) return
        viewModelScope.launch {
            testing = true
            val tests = checks.filter { it.kind == Kind.TEST }
            tests.forEach { states[it.id] = CheckState.Pending }
            val (ready, blocked) = tests.partition { t -> t.permissions.all { granted(it) } }
            blocked.forEach { t ->
                val missing = t.permissions.filterNot { granted(it) }.joinToString(", ") { it.substringAfterLast('.') }
                states[t.id] = CheckState.Done(Outcome(Status.SKIPPED, "permission not granted: $missing", JSONObject()))
            }
            // GNSS needs up to 30 s of listening, so it runs alongside the others.
            val gnss = ready.firstOrNull { it.id == "gnss" }
            val gnssJob = gnss?.let { launch { runOne(it) } }
            ready.filter { it !== gnss }.forEach { runOne(it) }
            gnssJob?.join()
            runOne(Checks.byId("readiness"))
            testing = false
            persist(export = true)
        }
    }

    /** Settings screens change readiness, so it is re-read whenever the app comes back. */
    fun refreshReadiness() {
        if (busy || runId == null) return
        viewModelScope.launch {
            runOne(Checks.byId("readiness"))
            persist(export = false)
        }
    }

    private suspend fun runOne(check: Check) {
        states[check.id] = CheckState.Running
        val outcome = try {
            withContext(Dispatchers.Default) { check.run(ctx) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Outcome(
                Status.FAIL,
                "check crashed: ${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""}",
                json { put("stackTrace", t.stackTraceToString().take(4000)) },
            )
        }
        states[check.id] = CheckState.Done(outcome)
    }

    private suspend fun persist(export: Boolean) {
        val id = runId ?: return
        val report = buildReport()
        try {
            val file = withContext(Dispatchers.IO) { ReportStore.save(ctx, id, report) }
            savedFile = file
            if (export) {
                val (uri, path) = withContext(Dispatchers.IO) { ReportStore.exportToDownloads(ctx, file, exportUri) }
                exportUri = uri
                exportedPath = path
            }
        } catch (t: Throwable) {
            message = "Could not save the report: ${t.message}"
        }
    }

    private fun load(file: File): Boolean = try {
        val root = JSONObject(file.readText())
        val sections = root.getJSONObject("sections")
        checks.forEach { c ->
            val s = sections.optJSONObject(c.id)
            states[c.id] = if (s == null) {
                CheckState.Pending
            } else {
                CheckState.Done(
                    Outcome(Status.valueOf(s.getString("status")), s.getString("headline"), s.optJSONObject("data") ?: JSONObject()),
                )
            }
        }
        runId = root.getString("runId")
        createdAt = Instant.parse(root.getString("createdAt"))
        savedFile = file
        true
    } catch (t: Throwable) {
        states.clear()
        false
    }

    fun capabilities(): List<Capability> = computeCapabilities(states)

    fun buildReport(): JSONObject = json {
        put("schema", "phonelab.syscheck/1")
        put("app", json {
            put("version", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
        })
        put("runId", runId)
        put("createdAt", createdAt?.toString())
        put("sections", JSONObject().also { sections ->
            checks.forEach { c ->
                val done = states[c.id] as? CheckState.Done ?: return@forEach
                sections.put(c.id, json {
                    put("title", c.title)
                    put("kind", c.kind)
                    put("status", done.outcome.status)
                    put("headline", done.outcome.headline)
                    put("data", done.outcome.data)
                })
            }
        })
        put("capabilities", JSONArray().also { a -> capabilities().forEach { a.put(it.toJson()) } })
    }

    /** A compact text version for pasting into a chat. */
    fun summaryText(): String = buildString {
        appendLine("PhoneLab system check ${BuildConfig.VERSION_NAME} · $createdAt")
        for (kind in Kind.entries) {
            appendLine()
            appendLine(if (kind == Kind.INFO) "# Phone info" else "# Hardware tests")
            checks.filter { it.kind == kind }.forEach { c ->
                when (val s = states[c.id]) {
                    is CheckState.Done -> appendLine("${c.title}: ${s.outcome.status} — ${s.outcome.headline}")
                    else -> appendLine("${c.title}: not run")
                }
            }
        }
        appendLine()
        appendLine("# Modules on this phone")
        capabilities().forEach { c ->
            appendLine("${c.verdict.symbol} ${c.area} / ${c.module}" + (c.note?.let { " — $it" } ?: ""))
        }
    }
}
