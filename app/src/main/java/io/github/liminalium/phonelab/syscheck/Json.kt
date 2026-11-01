package io.github.liminalium.phonelab.syscheck

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Small builder over org.json that accepts Kotlin values and never throws on NaN. */
class Json(val obj: JSONObject = JSONObject()) {
    fun put(key: String, value: Any?) {
        obj.put(key, jsonValue(value))
    }

    /** Puts the block's result, or a short reason if the platform refused. */
    inline fun attempt(key: String, block: () -> Any?) {
        val value = try {
            block()
        } catch (t: Throwable) {
            "unavailable: ${t.javaClass.simpleName}"
        }
        put(key, value)
    }
}

inline fun json(block: Json.() -> Unit): JSONObject = Json().apply(block).obj

fun jsonValue(v: Any?): Any = when (v) {
    null -> JSONObject.NULL
    is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long -> v
    is Float -> if (v.isFinite()) v.toString().toDouble() else JSONObject.NULL
    is Double -> if (v.isFinite()) v else JSONObject.NULL
    is Number -> v.toLong()
    is Enum<*> -> v.name
    is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), jsonValue(x)) } }
    is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
    is Array<*> -> JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
    is IntArray -> JSONArray().also { a -> v.forEach { a.put(it) } }
    is LongArray -> JSONArray().also { a -> v.forEach { a.put(it) } }
    is FloatArray -> JSONArray().also { a -> v.forEach { a.put(jsonValue(it)) } }
    else -> v.toString()
}

inline fun <T> safe(block: () -> T): T? = try {
    block()
} catch (t: Throwable) {
    null
}

fun readFile(path: String): String? = safe { File(path).readText().trim() }?.ifEmpty { null }

fun fmt(pattern: String, vararg args: Any?): String = String.format(Locale.US, pattern, *args)

fun gb(bytes: Long): String = fmt("%.1f GB", bytes / 1e9)

fun round(v: Float, places: Int = 3): Double =
    if (v.isFinite()) "%.${places}f".format(Locale.US, v).toDouble() else Double.NaN

fun yesNo(b: Boolean?): String = when (b) {
    true -> "✓"
    false -> "✗"
    null -> "?"
}

/** Reads every system property in one `getprop` call. */
object SystemProps {
    private val line = Regex("""^\[(.+?)]: \[(.*)]$""")

    val all: Map<String, String> by lazy {
        safe {
            val p = ProcessBuilder("getprop").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            p.waitFor()
            text.lineSequence().mapNotNull { line.find(it.trim()) }
                .associate { it.groupValues[1] to it.groupValues[2] }
        } ?: emptyMap()
    }

    operator fun get(key: String): String? = all[key]?.ifEmpty { null }
}
