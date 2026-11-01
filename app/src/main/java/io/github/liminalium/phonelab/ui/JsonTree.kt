package io.github.liminalium.phonelab.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** Shows a check's raw data as a collapsible tree. */
@Composable
fun JsonTree(obj: JSONObject) {
    Column(Modifier.padding(top = 8.dp)) {
        obj.keys().forEach { key -> JsonNode(key, obj.opt(key), 0) }
    }
}

private fun isScalar(v: Any?) = v !is JSONObject && v !is JSONArray

private fun text(v: Any?) = if (v == null || v == JSONObject.NULL) "—" else v.toString()

private fun labelOf(o: JSONObject): String? =
    listOf("name", "id", "description", "type", "core", "mime", "ids", "module")
        .firstNotNullOfOrNull { k -> o.opt(k)?.takeIf { isScalar(it) && it != JSONObject.NULL }?.toString() }

@Composable
private fun JsonNode(key: String, value: Any?, depth: Int) {
    when {
        value is JSONObject -> Expandable(key, "${value.length()} fields", depth) {
            value.keys().forEach { k -> JsonNode(k, value.opt(k), depth + 1) }
        }
        value is JSONArray -> {
            val items = (0 until value.length()).map { value.opt(it) }
            val inline = items.all { isScalar(it) }.let { scalar ->
                scalar && items.joinToString(", ") { text(it) }.length <= 80
            }
            if (inline) {
                Leaf(key, if (items.isEmpty()) "none" else items.joinToString(", ") { text(it) }, depth)
            } else {
                Expandable(key, "${items.size} items", depth) {
                    items.forEachIndexed { i, item ->
                        if (item is JSONObject) JsonNode(labelOf(item) ?: "#$i", item, depth + 1)
                        else JsonNode("", item, depth + 1)
                    }
                }
            }
        }
        else -> Leaf(key, text(value), depth)
    }
}

@Composable
private fun Expandable(key: String, summary: String, depth: Int, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(start = (depth * 12).dp, top = 4.dp, bottom = 4.dp),
    ) {
        Text(
            (if (open) "▾ " else "▸ ") + key,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) content()
}

@Composable
private fun Leaf(key: String, value: String, depth: Int) {
    Row(Modifier.fillMaxWidth().padding(start = (depth * 12 + 14).dp, top = 2.dp, bottom = 2.dp)) {
        if (key.isNotEmpty()) {
            Text(
                key,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(0.42f).padding(end = 8.dp),
            )
        }
        SelectionContainer(Modifier.weight(if (key.isEmpty()) 1f else 0.58f)) {
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
