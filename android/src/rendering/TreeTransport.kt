package dev.deal.embedding

import org.json.JSONObject

/** Transport adapter: the committed checked tree encoding to neutral nodes and values. */
internal fun treeFromJson(node: JSONObject): UiNode {
    val properties = node.getJSONArray("props")
    val props = (0 until properties.length()).map { index ->
        val prop = properties.getJSONObject(index)
        val value: UiValue = when (prop.optString("kind", "string")) {
            "number" -> UiValue.Num(prop.getDouble("numberValue"))
            "boolean" -> UiValue.Flag(prop.getBoolean("booleanValue"))
            "action" -> UiValue.Slot(prop.getInt("actionSlot"))
            "json" -> UiValue.Json(NeutralJson.parse(prop.optString("stringValue", "null")) ?: JsonValue.Null)
            else -> UiValue.Text(prop.optString("stringValue", ""))
        }
        UiProp(prop.getString("name"), value)
    }
    val nodes = node.getJSONArray("children")
    val children = (0 until nodes.length()).map { treeFromJson(nodes.getJSONObject(it)) }
    val key = listOf(node.optString("structural"), node.optString("keyKind"),
                     node.optDouble("keyNumber").toString(), node.optString("keyString")).joinToString("\u0000")
    return UiNode(node.getString("component").substringAfterLast('.'), props, children, key)
}
