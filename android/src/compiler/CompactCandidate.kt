package dev.deal.embedding

import org.json.JSONArray
import org.json.JSONObject
import deal.lexer.Lexer
import deal.ast.TokenType

/** Initial-source construction only. It never edits or repairs an existing source pair. */
internal object CompactCandidate {
    class Rejected(message: String) : IllegalArgumentException(message)
    private fun reject(message: String): Nothing = throw Rejected(message)
    private fun keys(o: JSONObject, vararg names: String) {
        if (o.keys().asSequence().toSet() != names.toSet()) reject("Expected fields: ${names.joinToString()}")
    }
    private fun id(s: String): String {
        if (!s.matches(Regex("[A-Za-z][A-Za-z0-9_]*"))) reject("Invalid identifier: $s")
        val tokens = Lexer(s, "compact").tokenize()
        if (tokens.tokens().first().type() != TokenType.IDENTIFIER) reject("Reserved identifier: $s")
        return s
    }
    private fun text(o: JSONObject, key: String): String = o.getString(key)
    private fun objects(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }
    private fun quoted(s: String): String {
        // DEAL does not support JSON Unicode escapes. Keep literal Unicode; reject unsupported controls.
        if (s.any { it.code < 32 && it !in "\n\r\t" }) reject("Unsupported control character")
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
    }
    fun assemble(plan: JSONObject, modules: Set<String>): JSONObject {
        try { return emit(plan, modules) }
        catch (e: Rejected) { throw e }
        catch (e: org.json.JSONException) { throw Rejected("Malformed compact candidate: ${e.message}") }
    }
    private fun emit(p: JSONObject, modules: Set<String>): JSONObject {
        keys(p, "imports", "records", "state", "derived", "controls", "actions", "effects", "functions", "view")
        val innerView = p.getString("view")
        if (Lexer(innerView, "compact-view").tokenize().tokens().any { it.lexeme() in setOf("import", "export") && it.type() != TokenType.STRING_LITERAL }) reject("view must contain inner statements only, not imports or a root declaration")
        val customFunctions = p.getString("functions")
        val functionTokens = Lexer(customFunctions, "compact-functions").tokenize().tokens()
        if (functionTokens.any { it.lexeme() in setOf("import", "class") && it.type() != TokenType.STRING_LITERAL } || functionTokens.zipWithNext().any { (a, b) -> a.lexeme() == "function" && b.lexeme() in setOf("copyState", "recompute", "initialState", "main") }) reject("functions contains framework-owned declarations")
        val imports = objects(p.getJSONArray("imports"))
        val aliases = mutableSetOf("values")
        val importsText = imports.joinToString("\n") { o ->
            keys(o, "alias", "module")
            val alias = id(text(o, "alias")); val module = text(o, "module")
            if (!aliases.add(alias)) reject("Duplicate import alias")
            if (module !in modules && module !in setOf("std/math", "std/string", "std/table")) reject("Undisclosed import: $module")
            "import * as $alias from ${quoted(module)};"
        }
        val records = objects(p.getJSONArray("records"))
        val names = mutableSetOf("State")
        val recordNames = records.map { id(it.getString("name")) }.toSet()
        fun type(s: String): String {
            val base = s.removeSuffix("[]")
            if (base !in setOf("number", "string", "boolean", "table") && base !in recordNames) reject("Unsupported field type: $s")
            return s
        }
        fun fields(a: JSONArray, initial: Boolean): List<JSONObject> = objects(a).also { list ->
            val seen = mutableSetOf<String>()
            list.forEach { f ->
                if (initial) keys(f, "name", "type", "value") else keys(f, "name", "type")
                if (!seen.add(id(f.getString("name")))) reject("Duplicate field")
                type(f.getString("type")); if (initial && f.getString("value").isBlank()) reject("Missing explicit initializer")
            }
        }
        fun declaration(className: String, fs: List<JSONObject>): String {
            if (!names.add(id(className))) reject("Duplicate class: $className")
            return "export class $className { " + fs.joinToString(" ") { "${it.getString("name")}: ${it.getString("type")};" } + " }\n"
        }
        val declarations = StringBuilder()
        records.forEach { r -> keys(r, "name", "fields"); declarations.append(declaration(r.getString("name"), fields(r.getJSONArray("fields"), false))) }
        val state = fields(p.getJSONArray("state"), true)
        if (state.any { it.getString("name") in setOf("loading", "error") }) reject("loading/error are framework-owned")
        val derived = objects(p.getJSONArray("derived"))
        val stateNames = state.map { it.getString("name") }.toMutableSet()
        derived.forEach { d ->
            keys(d, "name", "type", "value", "depends", "expression")
            if (!stateNames.add(id(d.getString("name")))) reject("Duplicate derived/state field")
            type(d.getString("type")); if (d.getString("value").isBlank() || d.getString("expression").isBlank()) reject("Missing derived expression/initializer")
        }
        val remaining = derived.toMutableList(); val ordered = mutableListOf<JSONObject>(); val resolved = (state.map { it.getString("name") } + listOf("loading", "error")).toMutableSet()
        while (remaining.isNotEmpty()) {
            val next = remaining.firstOrNull { d -> val a = d.getJSONArray("depends"); (0 until a.length()).all { a.getString(it) in resolved } }
                ?: reject("Cyclic or unknown derived dependency")
            ordered.add(next); resolved.add(next.getString("name")); remaining.remove(next)
        }
        val all = state + derived
        fun statePairs(prefix: String) = all.joinToString(", ") { val n = it.getString("name"); "$n: $prefix.$n" }
        val stateDecl = "export class State { loading: boolean; error: string; " + all.joinToString(" ") { "${it.getString("name")}: ${it.getString("type")};" } + " }\n"
        val generated = StringBuilder()
        generated.append("function copyState(state: State): State { return {loading: state.loading, error: state.error" + (if (all.isEmpty()) "" else ", " + statePairs("state")) + "}; }\n")
        generated.append("function recompute(state: State): State {\nlet next: State = copyState(state);\n")
        ordered.forEach { generated.append("next.${it.getString("name")} = ${it.getString("expression")};\n") }
        generated.append("return next;\n}\n")
        generated.append("export function initialState(): State { let next: State = {loading: false, error: \"\"" + all.joinToString("") { ", ${it.getString("name")}: ${it.getString("value")}" } + "}; return recompute(next); }\n")
        val controlViews = StringBuilder(); val buttons = StringBuilder()
        val actionNames = mutableSetOf<String>()
        fun action(name: String, fs: List<JSONObject>, body: String) {
            if (!actionNames.add(name)) reject("Duplicate action")
            declarations.append(declaration(name, fs))
            generated.append("// @ui-update\nexport function update$name(state: State, action: $name): State {\nlet next: State = copyState(state);\n$body\nreturn recompute(next);\n}\n")
        }
        objects(p.getJSONArray("controls")).forEach { c ->
            keys(c, "field", "label", "format", "minimum", "maximum", "step", "invalidate")
            val field = id(c.getString("field"))
            if (state.none { it.getString("name") == field && it.getString("type") == "number" }) reject("Control must reference numeric state")
            val lo = c.getDouble("minimum"); val hi = c.getDouble("maximum"); val step = c.getDouble("step")
            if (!lo.isFinite() || !hi.isFinite() || !step.isFinite() || lo > hi || step <= 0 || kotlin.math.floor(step) != step) reject("Invalid control bounds")
            fun decimal(v: Double) = java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()
            val lowText = decimal(lo); val highText = decimal(hi); val stepText = decimal(step)
            val format = c.getString("format"); if (format !in setOf("integer", "euro-cents")) reject("Unsupported control format")
            val invalidate = c.getJSONArray("invalidate")
            var invalidation = ""
            for (i in 0 until invalidate.length()) {
                val f = invalidate.getString(i)
                if (state.none { it.getString("name") == f && it.getString("type") == "boolean" }) reject("Invalid stale-result flag")
                invalidation += "next.$f = false;\n"
            }
            val name = "Set" + field.replaceFirstChar { it.uppercase() }
            action(name, listOf(JSONObject().put("name", "value").put("type", "number")), "if (!values.inBounds(action.value, $lo, $hi)) { return next; }\nnext.$field = action.value;\n$invalidation")
            controlViews.append("ui.IntField(value: state.$field, label: ${quoted(c.getString("label"))}, minimum: $lowText, maximum: $highText, step: $stepText, format: ${quoted(format)}, enabled: !state.loading, onChange: action app.$name { value: payload }, accessibilityLabel: ${quoted(c.getString("label"))})\n")
        }
        objects(p.getJSONArray("actions")).forEach { a ->
            keys(a, "name", "fields", "body")
            action(id(a.getString("name")), fields(a.getJSONArray("fields"), false), a.getString("body"))
        }
        val effects = objects(p.getJSONArray("effects"))
        effects.forEach { e ->
            keys(e, "name", "label", "body")
            val name = id(e.getString("name")); val done = name + "Done"
            action(name, emptyList(), "next.loading = true; next.error = \"\";")
            declarations.append(declaration(done, listOf(JSONObject().put("name", "error").put("type", "string")) + all))
            generated.append("// @ui-effect\nexport async function run$name(state: State, action: $name): $done {\nlet next: State = copyState(state);\ntry {\n${e.getString("body")}\nreturn {error: \"\"" + (if (all.isEmpty()) "" else ", " + statePairs("next")) + "};\n} catch (error) { return {error: \"Check access and try again\"" + (if (all.isEmpty()) "" else ", " + statePairs("state")) + "}; }\n}\n")
            generated.append("// @ui-update\nexport function finish$name(state: State, action: $done): State { let next: State = {loading: false, error: action.error" + (if (all.isEmpty()) "" else ", " + statePairs("action")) + "}; return recompute(next); }\n")
            buttons.append("ui.PrimaryAction(text: ${quoted(e.getString("label"))}, enabled: !state.loading, onClick: action app.$name {})\n")
        }
        val retry = effects.firstOrNull()?.getString("name")
        val errorView = if (retry != null) "When(state.error !== \"\") { ui.Failure(text: state.error, onRetry: action app.$retry {}) }\n" else ""
        val deal = "$importsText\nimport * as values from \"ui/values\";\n$declarations$stateDecl${p.getString("functions")}\n$generated\nexport function main(): null { return null; }\n"
        val ui = "import * as app from \"./experience\";\nimport * as ui from \"./platform.dealui-pack\";\n// @ui-root\nexport view Workspace(state: app.State): View { ui.Column() {\n$controlViews$buttons\nWhen(state.loading) { ui.Progress(label: \"Loading…\") } Else {\n$errorView${p.getString("view")}\n}\n} }\n"
        return JSONObject().put("deal", deal).put("dealui", ui)
    }
}
