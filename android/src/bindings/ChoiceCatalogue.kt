package dev.deal.embedding

import org.json.JSONArray
import org.json.JSONObject

/**
 * The compiler issues the options; the model only picks. Every non-diagnostic option lowers
 * to source this renderer can check, and every adapter it emits is an actual catalog function
 * with the catalog's own record fields, so a choice cannot name something that does not exist.
 */
internal object ChoiceCatalogue {
    const val DIAGNOSTIC = "unavailable"

    data class Question(val alias: String, val instructions: String, val options: List<Option>)
    data class Option(val alias: String, val label: String, val description: String, val diagnostic: Boolean = false)

    /** A capability the workspace may call, with the fields its result actually carries. */
    data class Adapter(val module: String, val function: String, val arguments: List<String>, val fields: List<Pair<String, String>>)

    fun questions(): List<Question> = listOf(
        Question("purpose", "What is this workspace for, judged from the intent?",
            listOf(
                Option("compare", "Compare options", "Several alternatives with their coverage, price and terms."),
                Option("choose", "Choose one thing", "A single decision from listed candidates."),
                Option("track", "Track progress", "Show existing work and let the user act on it."),
                Option("explain", "Explain a situation", "State the facts and the problem plainly; no decision."),
                Option(DIAGNOSTIC, "None of these", "The intent needs something else.", diagnostic = true),
            )),
        Question("headline", "How prominent is the opening heading?",
            listOf(Option("hero", "Large heading", "Hero for the dominant statement."), Option("text", "Normal heading", "Text where a large heading would be excessive."))),
        Question("body", "What carries the content?",
            listOf(
                Option("options", "Selectable options", "Option entries showing coverage and price."),
                Option("items", "Plain rows", "Item rows with a title, detail and trailing value."),
                Option("none", "No list", "Only headings and prose."),
            )),
        Question("notice", "Is there a condition the user must not miss?",
            listOf(Option("warning", "Yes, a warning", "A Notice naming what cannot be met or what changed."), Option("none", "No notice", "Nothing qualifies the content."))),
        Question("capabilities", "What does the workspace do with the connected apps?",
            listOf(
                Option("read", "Read and display", "Await one catalog read function and show its results."),
                Option("prepare", "Prepare one operation", "Collect a note, then stage one operation for native confirmation."),
                Option("none", "Nothing", "Pure local interaction."),
            )),
        Question("loading", "How are waiting and failure shown?",
            listOf(Option("progress", "Progress and failure", "Progress while awaiting, Failure with retry on error."), Option("none", "Neither", "No asynchronous work."))),
    )

    fun issued(catalog: String, adapters: List<Adapter>): JSONObject = JSONObject()
        .put("protocol", "embedding/dealui-choice-v1")
        .put("assurance", "This is a compiler-verified inventory. Every non-diagnostic option lowers to source this renderer checks.")
        .put("constraint", "Answer every question with exactly one supplied alias. Do not propose components, properties, code or content outside the options.")
        .put("responseFormat", "Return ONLY a JSON object of the form {\"answers\":{\"<question alias>\":\"<option alias>\", ...}} with one entry per question and no other text.")
        .put("catalog", catalog)
        .put("availableCapabilities", JSONArray(adapters.map { JSONObject().put("module", it.module).put("function", it.function).put("arguments", JSONArray(it.arguments)) }))
        .put("questions", JSONArray(questions().map { question ->
            JSONObject().put("alias", question.alias).put("type", "choice").put("instructions", question.instructions)
                .put("options", JSONArray(question.options.map { option ->
                    JSONObject().put("alias", option.alias).put("label", option.label).put("description", option.description).put("diagnostic", option.diagnostic) }))
        }))

    fun validate(raw: String): Pair<Map<String, String>?, String> {
        val parsed = runCatching { JSONObject(raw) }.getOrElse { return null to "Answer is not a JSON object" }
        val answers = parsed.optJSONObject("answers") ?: return null to "Answer has no answers object"
        val questions = questions().associateBy { it.alias }
        if (answers.length() != questions.size) return null to "Answer must address every question; found " + answers.length()
        val chosen = mutableMapOf<String, String>()
        for ((alias, question) in questions) {
            if (!answers.has(alias)) return null to "Answer is missing " + alias
            val value = answers.optString(alias)
            val option = question.options.firstOrNull { it.alias == value } ?: return null to "Answer chose an option outside the issued set for " + alias
            if (option.diagnostic) return null to "Requirement is outside the issued options: " + alias
            chosen[alias] = value
        }
        return chosen to ""
    }

    /**
     * Lowers a validated selection. Exactly one effect is emitted, on the action the view
     * dispatches, and its adapter is a real catalog function.
     */
    fun lower(sourceName: String, chosen: Map<String, String>, read: Adapter?, prepare: Adapter?, requirement: String): ExperienceSource {
        val headline = if (chosen["headline"] == "hero") "Hero" else "Text"
        val warning = chosen["notice"] == "warning"
        val rows = chosen["body"] != "none"
        val mode = chosen["capabilities"]
        val adapter = if (mode == "prepare") prepare else if (mode == "read") read else null
        val completes = adapter != null
        // A read adapter must be callable without collected input; otherwise the choice is not usable.
        val callable = adapter != null && (mode == "prepare" || adapter.arguments.isEmpty())

        val deal = buildString {
            if (callable) append("import * as host from \"").append(adapter!!.module).append("\";\n")
            append("export class Row { id: string = \"\"; title: string = \"\"; detail: string = \"\"; trailing: string = \"\"; selected: boolean = false; }\n")
            append("export class Pick { value: string = \"\"; }\n")
            append("export class Run {}\n")
            append("export class Done { headline: string = \"\"; status: string = \"\"; rows: Row[] = []; loading: boolean = false; }\n")
            append("export class Workspace { headline: string = \"\"; status: string = \"\"; action: string = \"Continue\"; loading: boolean = false; note: string = \"\"; selected: string = \"\"; notice: string = \"\"; rows: Row[] = []; }\n")
            append("export function initialState(): Workspace { return { headline: \"Ready when you are\", status: \"Review the details below.\", action: \"Continue\", loading: false, note: \"\", selected: \"\", notice: ")
                .append(if (warning) quote(requirement) else "\"\"").append(", rows: [] }; }\n")
            append("// @ui-update\nexport function pick(state: Workspace, action: Pick): Workspace { return { headline: state.headline, status: state.status, action: state.action, loading: state.loading, note: state.note, selected: action.value, notice: state.notice, rows: state.rows }; }\n")
            if (callable) {
                append("// @ui-update\nexport function run(state: Workspace, action: Run): Workspace { return { headline: state.headline, status: \"Checking the connected apps…\", action: state.action, loading: true, note: state.note, selected: state.selected, notice: state.notice, rows: state.rows }; }\n")
                append("// @ui-effect\nexport async function work(state: Workspace, action: Run): Done {\n")
                append("    try {\n")
                if (mode == "prepare") {
                    append("        let staged: table = await host.").append(adapter!!.function).append("(").append(prepareArguments(adapter)).append(");\n")
                    append("        let rows: Row[] = [];\n")
                    append("        return { headline: state.headline, status: \"Prepared for your review.\", rows: rows, loading: false };\n")
                } else {
                    append("        let records: table[] = await host.").append(adapter!!.function).append("();\n")
                    append("        let rows: Row[] = [];\n")
                    append("        for (let record: table of records) {\n")
                    // Declare exactly the fields this row reads, with their declared types.
                    val used = listOf(adapter.fields.first().first) +
                        listOfNotNull(adapter.fields.getOrNull(1)?.first, adapter.fields.getOrNull(2)?.first,
                            adapter.fields.firstOrNull { it.first == "price" || it.first == "cents" || it.first == "deadline" }?.first)
                    used.distinct().forEach { field ->
                        val type = adapter.fields.first { it.first == field }.second
                        append("            let ").append(field).append(": ").append(type).append(" = record.").append(field).append(";\n")
                    }
                    append("            let selected: boolean = false;\n")
                    val idField = adapter.fields.first().first
                    val titleField = adapter.fields.getOrNull(1)?.first ?: idField
                    val detailField = adapter.fields.getOrNull(2)?.first
                    val trailingField = adapter.fields.firstOrNull { it.first == "price" || it.first == "cents" || it.first == "deadline" }?.first
                    append("            let rowId: string = ").append(stringOf(idField, adapter)).append(";\n")
                    append("            let rowTitle: string = ").append(stringOf(titleField, adapter)).append(";\n")
                    append("            let rowDetail: string = ").append(stringOf(detailField, adapter)).append(";\n")
                    append("            let rowTrailing: string = ").append(stringOf(trailingField, adapter)).append(";\n")
                    append("            rows[rows.length] = { id: rowId, title: rowTitle, detail: rowDetail, trailing: rowTrailing, selected: selected };\n")
                    append("        }\n")
                    append("        return { headline: state.headline, status: \"Entries loaded.\", rows: rows, loading: false };\n")
                }
                append("    } catch (error) {\n")
                append("        return { headline: state.headline, status: error.code + \": \" + error.message, rows: state.rows, loading: false };\n")
                append("    }\n}\n")
                append("// @ui-update\nexport function done(state: Workspace, action: Done): Workspace { return { headline: action.headline, status: action.status, action: state.action, loading: action.loading, note: state.note, selected: state.selected, notice: state.notice, rows: action.rows }; }\n")
            }
            append("export function main(): null { return null; }\n")
        }

        val ui = buildString {
            append("import * as app from \"./").append(sourceName).append("\";\nimport * as ui from \"./platform.dealui-pack\";\n\n// @ui-root\nexport view WorkspaceView(state: app.Workspace): View {\n    ui.Column() {\n")
            append("        ui.").append(headline).append("(value: state.headline)\n")
            append("        ui.Text(value: state.status)\n")
            if (warning) append("        ui.Notice(text: state.notice, tone: \"warning\")\n")
            if (rows && callable) {
                // The progress notice is additive; the list is always present so the surface is
                // complete before the first effect runs.
                append("        When(state.loading) { ui.Progress(label: \"Checking the connected apps\") }\n")
                append("        ForEach(state.rows, row: app.Row, key: row.id) {\n")
                append("            ui.Option(value: row.id, title: row.title, detail: row.detail, price: row.trailing, selected: row.selected, onSelect: action app.Pick { value: payload })\n")
                append("        }\n")
            } else if (rows) {
                append("        ForEach(state.rows, row: app.Row, key: row.id) {\n")
                append("            ui.Option(value: row.id, title: row.title, detail: row.detail, price: row.trailing, selected: row.selected, onSelect: action app.Pick { value: payload })\n")
                append("        }\n")
            }
            if (callable) append("        ui.Button(text: state.action, accessibilityLabel: state.action, onClick: action app.Run {})\n")
            append("    }\n}\n")
        }
        return ExperienceSource(deal, ui)
    }

    /** Field access must match the declared type: DEAL has no toString. */
    private fun stringOf(field: String?, adapter: Adapter): String {
        if (field == null) return "\"\""
        val type = adapter.fields.firstOrNull { it.first == field }?.second
        return if (type == "int") field.toString() + ".__toString()" else field
    }

    /** Every argument a prepare function declares, taken from the disclosed note. */
    private fun prepareArguments(adapter: Adapter): String = adapter.arguments.joinToString(", ") { parameter ->
        when (parameter) {
            "kind" -> "\"operation\""
            "provider" -> "\"organizer\""
            "title" -> "state.note"
            "detail" -> "state.selected"
            else -> "state.note"
        }
    }

    private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
}
