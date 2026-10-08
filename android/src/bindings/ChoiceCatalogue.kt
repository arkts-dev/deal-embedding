package dev.deal.embedding

import dev.deal.embedding.capabilities.*
import org.json.JSONArray
import org.json.JSONObject

/** Bounded template inventory. Unsupported schemas/context go to source generation, never guessed calls. */
internal object ChoiceCatalogue {
    data class Adapter(val alias: String, val module: String, val function: CapabilityFunction, val arguments: List<String>)
    data class Inventory(val reads: List<Adapter>, val preparation: Adapter?, val context: JSONObject)
    private val descriptor = listOf("kind", "provider", "title", "detail", "price", "deadline")

    fun inventory(contracts: List<CapabilityContract>, disclosed: String): Inventory {
        val context = runCatching { JSONObject(disclosed) }.getOrElse { JSONObject() }
        val reads = mutableListOf<Adapter>()
        val preparations = mutableListOf<Adapter>()
        for (contract in contracts) for (fn in contract.functions) {
            if (fn.parameters.map { it.name } == descriptor && fn.parameters.all { it.type.kind == "string" } &&
                fn.result.kind == "record" && fn.result.fields["staged"]?.kind == "boolean") {
                preparations.add(Adapter("prepare", contract.module, fn, emptyList()))
            }
            val fields = fn.result.element?.fields ?: emptyMap()
            if (fn.result.kind != "array" || fn.result.element?.kind != "record" ||
                fields["id"]?.kind != "string" || fields["name"]?.kind != "string" ||
                fields["specification"]?.kind != "string" || fields["cents"]?.kind != "int" ||
                fields["available"]?.kind != "int") continue
            if (fn.parameters.any { it.type.kind != "string" }) continue
            val values = fn.parameters.map { parameter ->
                context.opt(parameter.name)?.takeIf { value -> runCatching { parameter.type.validate(value) }.isSuccess }
            }
            if (values.any { it == null }) continue
            reads.add(Adapter("read${reads.size}", contract.module, fn, values.map { it as String }))
        }
        val preparation = preparations.singleOrNull()
        val supported = preparation != null && runCatching {
            require(context.getInt("quantity") in 1..128)
            listOf("requirement", "specification", "from", "until").forEach { require(context.getString(it).isNotBlank()) }
            val terms = context.getJSONArray("searchTerms")
            require(terms.length() in 1..8)
            (0 until terms.length()).forEach { require(terms.getString(it).isNotBlank()) }
        }.isSuccess
        return Inventory(if (supported) reads else emptyList(), preparation, context)
    }

    private fun questions(inventory: Inventory): JSONObject = JSONObject()
        .put("purpose", JSONArray(listOf("compare", "choose", "unavailable")))
        .put("headline", JSONArray(listOf("hero", "text")))
        .put("notice", JSONArray(listOf("warning", "none")))
        .put("read", JSONArray(inventory.reads.map { it.alias } + "unavailable"))

    fun issued(catalog: String, inventory: Inventory): JSONObject = JSONObject()
        .put("protocol", "embedding/dealui-choice-v2")
        .put("constraint", "Choose the catalogue relevant to the disclosed requirement, not an operations history. Unsupported intents must choose unavailable. Source is always checked after lowering.")
        .put("responseFormat", "Return ONLY {\"answers\":{\"purpose\":\"<alias>\",\"headline\":\"<alias>\",\"notice\":\"<alias>\",\"read\":\"<alias>\"}}. Each answer must be an issued alias.")
        .put("catalog", catalog)
        .put("reads", JSONArray(inventory.reads.map { adapter -> JSONObject().put("alias", adapter.alias)
            .put("module", adapter.module).put("function", adapter.function.json()).put("arguments", JSONArray(adapter.arguments)) }))
        .put("questions", questions(inventory))

    fun validate(raw: String, inventory: Inventory): Pair<Map<String, String>?, String> {
        val parsed = runCatching { JSONObject(raw) }.getOrElse { return null to "CHOICE_JSON: expected JSON object" }
        if (parsed.length() != 1) return null to "CHOICE_SHAPE: expected only answers"
        val answers = parsed.optJSONObject("answers") ?: return null to "CHOICE_SHAPE: missing answers"
        val questions = questions(inventory)
        if (answers.length() != questions.length()) return null to "CHOICE_SHAPE: answer every issued question"
        val chosen = mutableMapOf<String, String>()
        for (alias in questions.keys()) {
            val value = answers.opt(alias)
            val options = questions.getJSONArray(alias)
            if (value !is String || (0 until options.length()).none { options.getString(it) == value }) return null to "CHOICE_ALIAS: invalid $alias"
            if (value == "unavailable") return null to "CHOICE_UNSUPPORTED: $alias"
            chosen[alias] = value
        }
        return chosen to ""
    }

    fun lower(sourceName: String, chosen: Map<String, String>, inventory: Inventory): ExperienceSource {
        val read = inventory.reads.single { it.alias == chosen["read"] }
        val prepare = inventory.preparation ?: error("CHOICE_CONTEXT: native review descriptor capability unavailable")
        val context = inventory.context
        val title = context.getString("requirement")
        val quantity = context.getInt("quantity")
        require(quantity in 1..128) { "CHOICE_CONTEXT: invalid quantity" }
        val terms = context.getJSONArray("searchTerms")
        require(terms.length() in 1..8) { "CHOICE_CONTEXT: search terms required" }
        val deadline = context.getString("until")
        val spec = context.getString("specification")
        val condition = (0 until terms.length()).joinToString(" && ") { "strings.contains(search, ${quote(terms.getString(it).lowercase())})" }
        val from = context.getString("from")
        val purpose = if (chosen["purpose"] == "choose") "Choose" else "Compare"
        val header = if (chosen["headline"] == "hero") "Hero" else "Text"
        val source = """
import * as catalogue from ${quote(read.module)};
import * as review from ${quote(prepare.module)};
import * as strings from "std/string";
export class Row { id: string = ""; title: string = ""; detail: string = ""; price: string = ""; meta: string = ""; available: boolean = false; selected: boolean = false; }
export class Load {}
export class Pick { value: string = ""; }
export class Prepare {}
export class Loaded { rows: Row[] = []; status: string = ""; failed: boolean = false; }
export class Prepared { status: string = ""; failed: boolean = false; }
export class Workspace { rows: Row[] = []; status: string = "Load available options for the requested period."; selected: string = ""; loading: boolean = false; failed: boolean = false; }
function decimal(value: int): string {
    if (value < 0) { throw { code: "BAD_PRICE", message: "Negative catalogue amount" }; }
    if (value === 0) { return "0"; }
    let out: string = ""; let remaining: int = value;
    while (remaining > 0) { let digit: int = remaining % 10; out = strings.substring("0123456789", digit, digit + 1) + out; remaining = remaining / 10; }
    return out;
}
function money(cents: int): string { let rest: int = cents % 100; let pad: string = ""; if (rest < 10) { pad = "0"; } return "€" + decimal(cents / 100) + "." + pad + decimal(rest); }
function lowercase(text: string): string {
    let out: string = "";
    for (let ch: string of text) {
        let found: boolean = false;
        for (let i: int = 0; i < 26; i = i + 1) { if (ch === strings.substring("ABCDEFGHIJKLMNOPQRSTUVWXYZ", i, i + 1)) { out = out + strings.substring("abcdefghijklmnopqrstuvwxyz", i, i + 1); found = true; } }
        if (!found) { out = out + ch; }
    }
    return out;
}
export function initialState(): Workspace { return {}; }
// @ui-update
export function load(state: Workspace, action: Load): Workspace { return { rows: state.rows, status: "Loading catalogue…", selected: "", loading: true }; }
// @ui-effect
export async function fetch(state: Workspace, action: Load): Loaded {
    try {
        let records: table[] = await catalogue.${read.function.name}(${read.arguments.joinToString(", ") { quote(it) }});
        let rows: Row[] = [];
        for (let record: table of records) {
            let id: string = record.id; let name: string = record.name; let specification: string = record.specification;
            let cents: int = record.cents; let available: int = record.available;
            let search: string = lowercase(name + " " + specification);
            if ($condition) {
                if (cents < 0 || cents > ${Int.MAX_VALUE / quantity} || available < 0) { throw { code: "BAD_CATALOGUE", message: "Invalid price or availability" }; }
                let cost: int = cents * $quantity;
                rows[rows.length] = { id: id, title: name, detail: specification, price: money(cost), meta: decimal(available) + " available · quantity $quantity", available: available >= $quantity };
            }
        }
        let status: string = "Select an option; specification still needs your review.";
        if (rows.length === 0) { status = "No matching options. The requirement remains unresolved."; }
        return { rows: rows, status: status };
    } catch (error) { return { rows: state.rows, status: error.code + ": " + error.message, failed: true }; }
}
// @ui-update
export function loaded(state: Workspace, action: Loaded): Workspace { return { rows: action.rows, status: action.status, failed: action.failed }; }
// @ui-update
export function pick(state: Workspace, action: Pick): Workspace {
    let rows: Row[] = []; let selected: string = "";
    for (let row: Row of state.rows) {
        let match: boolean = row.id === action.value && row.available;
        if (match) { selected = row.id; }
        rows[rows.length] = { id: row.id, title: row.title, detail: row.detail, price: row.price, meta: row.meta, available: row.available, selected: match };
    }
    return { rows: rows, selected: selected, status: "Review the selected specification, then prepare for native review." };
}
// @ui-update
export function prepare(state: Workspace, action: Prepare): Workspace { return { rows: state.rows, selected: state.selected, status: "Preparing review…", loading: true }; }
// @ui-effect
export async function stage(state: Workspace, action: Prepare): Prepared {
    try {
        for (let row: Row of state.rows) {
            if (row.id === state.selected && row.available) {
                let detail: string = ${quote("Required: $spec. Period: $from to $deadline. ")} + "Item " + row.id + ":$quantity · " + row.detail;
                let staged: table = await review.${prepare.function.name}("catalogue-selection", ${quote(read.module)}, row.title, detail, row.price, ${quote(deadline)});
                let accepted: boolean = staged.staged;
                if (!accepted) { throw { code: "NOT_STAGED", message: "Native review rejected the descriptor" }; }
                return { status: "Prepared for native review. No reservation has been made." };
            }
        }
        return { status: "Choose an available option first.", failed: true };
    } catch (error) { return { status: error.code + ": " + error.message, failed: true }; }
}
// @ui-update
export function prepared(state: Workspace, action: Prepared): Workspace { return { rows: state.rows, selected: state.selected, status: action.status, failed: action.failed }; }
export function main(): null { return null; }
""".trimIndent()
        val warning = if (chosen["notice"] == "warning") "ui.Notice(text: ${quote("Requested: $spec. Availability is not a reservation.")}, tone: \"warning\")" else ""
        val ui = """
import * as app from "./$sourceName";
import * as ui from "./platform.dealui-pack";
// @ui-root
export view WorkspaceView(state: app.Workspace): View {
    ui.Column() {
        ui.$header(value: ${quote("$purpose $title")})
        ui.Text(value: ${quote("$quantity required · $from to $deadline")})
        ui.Text(value: state.status)
        $warning
        When(state.loading) { ui.Progress(label: "Waiting for connected app") } Else {
            When(state.failed) { ui.Failure(text: state.status, accessibilityLabel: "Retry catalogue", onRetry: action app.Load {}) }
            ForEach(state.rows, row: app.Row, key: row.id) {
                ui.Option(value: row.id, title: row.title, detail: row.detail, price: row.price, meta: row.meta, enabled: row.available, selected: row.selected, accessibilityLabel: row.title, onSelect: action app.Pick { value: payload })
            }
            ui.Button(text: "Load options", accessibilityLabel: "Load options", onClick: action app.Load {})
            When(state.selected !== "") { ui.Button(text: "Prepare for review", accessibilityLabel: "Prepare for review", onClick: action app.Prepare {}) }
        }
    }
}
""".trimIndent()
        return ExperienceSource(source, ui)
    }

    /** DEAL supports quote, backslash, newline and tab escapes, not JSON's slash/unicode escapes. */
    private fun quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\t", "\\t") + "\""
}
