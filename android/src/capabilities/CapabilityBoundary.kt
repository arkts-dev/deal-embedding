package dev.deal.embedding.capabilities

import dev.deal.embedding.CapabilityContract
import dev.deal.embedding.ProtocolFailure
import dev.deal.embedding.WireType
import dev.deal.embedding.json
import java.util.Collections

/**
 * One rejected disclosure or schema, located by the offending contract, field path and bound.
 * It is a classified argument rejection, so the host receives `INVALID_ARGUMENT` with the location.
 */
internal class CapabilityRejected(message: String) : ProtocolFailure("INVALID_ARGUMENT", message)

/**
 * The single capability-contract boundary of [spec v0.8](docs/spec-v0.8.md) "Host protocol".
 * The contract decode, provider registration and disclosure admission all validate here; no other
 * path re-derives, relaxes or duplicates these limits.
 */
internal object CapabilityBoundary {
    const val BYTES = 16 * 1024
    const val FUNCTIONS = 32
    const val PARAMETERS = 8
    const val DESCRIPTION = 512
    const val DEPTH = 8
    const val FIELDS = 32
    const val MAXIMUM = 8192
    private val MODULE = Regex("host/[a-z][a-z0-9-]{0,63}")
    private val IDENTIFIER = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val KINDS = setOf("number", "string", "boolean", "null", "record", "array")

    /** `dependencies/deal` `Lexer.KEYWORDS`; a keyword can never name an exported declaration. */
    private val KEYWORDS = setOf(
        "let", "const", "class", "function", "async", "await", "return", "if", "else", "while", "for",
        "of", "break", "continue", "null", "true", "false", "import", "export", "from", "delete", "try",
        "catch", "throw", "as", "typeof", "instanceof",
    )

    private fun reject(subject: String, field: String, bound: String): Nothing =
        throw CapabilityRejected("$subject: $field $bound")

    private fun need(condition: Boolean, subject: String, field: String, bound: String) {
        if (!condition) reject(subject, field, bound)
    }

    /** Identifier syntax of the boundary table. */
    fun identifier(name: String): Boolean = IDENTIFIER.matches(name)

    /** Identifier syntax plus representability as a DEAL exported declaration name. */
    fun declared(name: String): Boolean = identifier(name) && name !in KEYWORDS

    /** Admission of one disclosed contract text before it is decoded. */
    fun disclosed(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8).size
        need(bytes <= BYTES, "contract", "JSON", "must be at most $BYTES UTF-8 bytes, got $bytes")
    }

    /** Every bound of the contract table for one disclosed contract, measured as canonical JSON. */
    fun validate(contract: CapabilityContract) = validate(contract, "contract '${contract.module}'", true)

    /** The bounds of a decoded contract; [disclosed] already bounded the text it came from. */
    fun validateDecoded(contract: CapabilityContract) = validate(contract, "contract '${contract.module}'", false)

    /**
     * The whole proposed set: every contract plus module uniqueness, before any contract replaces an
     * accepted one.
     */
    fun validate(proposed: List<CapabilityContract>) {
        val modules = mutableSetOf<String>()
        proposed.forEachIndexed { index, contract ->
            validate(contract, "contract[$index] '${contract.module}'", true)
            need(modules.add(contract.module), "contract[$index]", "module", "must be unique in the disclosed set, got '${contract.module}'")
        }
    }

    private fun validate(contract: CapabilityContract, subject: String, measureBytes: Boolean) {
        need(contract.version == 1, subject, "version", "must be 1, got ${contract.version}")
        need(MODULE.matches(contract.module), subject, "module", "must match host/[a-z][a-z0-9-]{0,63}, got '${contract.module}'")
        need(contract.description.length in 1..DESCRIPTION, subject, "description", "must be 1 to $DESCRIPTION UTF-16 units, got ${contract.description.length}")
        need(contract.functions.size in 1..FUNCTIONS, subject, "functions", "must number 1 to $FUNCTIONS, got ${contract.functions.size}")
        val names = mutableSetOf<String>()
        contract.functions.forEachIndexed { index, function ->
            val path = "functions[$index]"
            need(names.add(function.name), subject, "$path.name", "must be unique, got '${function.name}'")
            need(identifier(function.name), subject, "$path.name", "must match [A-Za-z][A-Za-z0-9_]{0,63}, got '${function.name}'")
            need(declared(function.name), subject, "$path.name", "must name a DEAL declaration, got the keyword '${function.name}'")
            need(function.description.length in 1..DESCRIPTION, subject, "$path.description", "must be 1 to $DESCRIPTION UTF-16 units, got ${function.description.length}")
            need(function.parameters.size <= PARAMETERS, subject, "$path.parameters", "must number at most $PARAMETERS, got ${function.parameters.size}")
            val parameters = mutableSetOf<String>()
            function.parameters.forEachIndexed { position, parameter ->
                need(parameters.add(parameter.name), subject, "$path.parameters[$position].name", "must be unique, got '${parameter.name}'")
                need(identifier(parameter.name), subject, "$path.parameters[$position].name", "must match [A-Za-z][A-Za-z0-9_]{0,63}, got '${parameter.name}'")
                schema(parameter.type, subject, "$path.parameters[$position].type")
            }
            schema(function.result, subject, "$path.result")
        }
        // One encoding per entry: a decoded contract was bounded as disclosed text, a registered or
        // proposed one as the canonical JSON this disclosure would send. Measured last because only a
        // contract that can be encoded has a disclosed size.
        if (measureBytes) {
            val bytes = contract.json().toString().toByteArray(Charsets.UTF_8).size
            need(bytes <= BYTES, subject, "JSON", "must be at most $BYTES UTF-8 bytes, got $bytes")
        }
    }

    /**
     * The one schema validator of "GenerationContext acquisition": kinds, depth, field count,
     * maximum and numeric bounds with inactive metadata validated too.
     */
    fun schema(type: WireType, subject: String, path: String) {
        val ancestors = Collections.newSetFromMap(java.util.IdentityHashMap<WireType, Boolean>())
        schema(type, subject, path, 0, ancestors)
    }

    private fun schema(type: WireType, subject: String, path: String, depth: Int, ancestors: MutableSet<WireType>) {
        need(depth <= DEPTH, subject, path, "must be at depth at most $DEPTH")
        need(ancestors.add(type), subject, path, "must not be cyclic")
        try {
            need(type.kind in KINDS, subject, "$path.kind", "must be number, string, boolean, null, record or array, got '${type.kind}'")
            need(type.maximum in 0..MAXIMUM, subject, "$path.maximum", "must be an integral 0 to $MAXIMUM, got ${type.maximum}")
            need(type.minimumNumber.isFinite() && type.maximumNumber.isFinite() && type.minimumNumber <= type.maximumNumber,
                subject, "$path.numbers", "must be finite with minimum at most maximum")
            need(type.kind != "array" || type.element != null, subject, "$path.element", "must be present for an array")
            need(type.kind == "array" || type.element == null, subject, "$path.element", "must be absent outside an array")
            need(type.kind == "record" || type.fields.isEmpty(), subject, "$path.fields", "must be empty outside a record")
            need(type.fields.size <= FIELDS, subject, "$path.fields", "must number at most $FIELDS, got ${type.fields.size}")
            val names = mutableSetOf<String>()
            type.fields.forEachIndexed { index, field ->
                need(names.add(field.name), subject, "$path.fields[$index].name", "must be unique, got '${field.name}'")
                need(identifier(field.name), subject, "$path.fields[$index].name", "must match [A-Za-z][A-Za-z0-9_]{0,63}, got '${field.name}'")
                schema(field.type, subject, "$path.fields[$index].type", depth + 1, ancestors)
            }
            type.element?.let { schema(it, subject, "$path.element", depth + 1, ancestors) }
        } finally { ancestors.remove(type) }
    }
}

/**
 * The disclosed provider set of one registry. Admission validates the whole proposal before any
 * contract replaces an accepted one, so a rejected disclosure keeps the accepted contracts, the
 * bindings derived from them and every grant epoch exactly as they were, and invokes no provider.
 */
internal class CapabilityDisclosure<T>(initial: List<T>, private val contract: (T) -> CapabilityContract) {
    private var accepted: Map<String, T> = index(initial)

    fun accepted(): Map<String, T> = accepted

    fun contracts(): List<CapabilityContract> = accepted.values.map(contract)

    /**
     * Replaces the whole accepted set only when the proposal is inside the boundary; while a Session
     * is live an already accepted contract stays frozen instead of being substituted.
     */
    fun admit(proposed: List<T>, live: Boolean): List<CapabilityContract> {
        val next = index(proposed)
        require(!live || accepted.all { (module, prior) -> next[module]?.let { contract(it) == contract(prior) } == true }) {
            "Close active sessions before changing existing capability contracts"
        }
        accepted = next
        return contracts()
    }

    private fun index(proposed: List<T>): Map<String, T> {
        CapabilityBoundary.validate(proposed.map(contract))
        return proposed.associateBy { contract(it).module }
    }
}
