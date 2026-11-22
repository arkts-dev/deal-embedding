package dev.deal.embedding.capabilities

import dev.deal.embedding.CapabilityContract
import dev.deal.embedding.CapabilityFunction
import dev.deal.embedding.Parameter
import dev.deal.embedding.WireField
import dev.deal.embedding.WireType
import dev.deal.embedding.json
import dev.deal.embedding.nativeCapabilities
import dev.deal.embedding.parse
import org.json.JSONArray

/**
 * Deterministic items for the one capability-contract boundary of spec v0.8 "Host protocol".
 * Each declaration below is a public disclosure path: the contract decode (`CapabilityContract.parse`),
 * provider registration (`nativeCapabilities`) and the registry's atomic disclosure admission
 * (`CapabilityDisclosure`, the contract and provider state `CapabilityRegistry` publishes). Every bound
 * is exercised below, at and above it, and a valid-plus-invalid batch proves a rejected set replaces
 * nothing. Declarations are derived on demand from the accepted contracts (`CapabilityBindings`,
 * `ExperienceCompiler`), so preserving the accepted set preserves them; grants live in the registry's
 * permission probe, which disclosure never writes. The registry's own wiring of the live-session flag
 * and its Android session bookkeeping are exercised by the host integration owner, not here.
 */

private val scalar = WireType("number", maximum = 0, minimumNumber = -1000.0, maximumNumber = 1000.0)

private fun function(
    name: String = "read",
    parameters: List<Parameter> = emptyList(),
    result: WireType = scalar,
    description: String = "Read fixture value",
) = CapabilityFunction(name, description, parameters, result)

private fun contract(
    module: String = "host/probe",
    functions: List<CapabilityFunction> = listOf(function()),
    description: String = "Explicit fixture read",
) = CapabilityContract(module, description, functions, 1)

/** A contract whose canonical JSON is exactly [bytes] UTF-8 bytes inside the name/description bounds. */
private fun sized(bytes: Int): CapabilityContract {
    fun build(descriptions: (Int) -> Int) = CapabilityContract("host/probe", "Probe",
        (0 until 32).map { index -> function(name = "read$index", description = "d".repeat(descriptions(index))) }, 1)
    val base = build { 1 }.json().toString().toByteArray(Charsets.UTF_8).size
    val extra = bytes - base
    require(extra in 0..(32 * (CapabilityBoundary.DESCRIPTION - 1))) { "Fixture cannot size a contract to $bytes bytes" }
    val lengths = IntArray(32) { 1 }
    var remaining = extra
    for (index in 0 until 32) { val added = minOf(remaining, CapabilityBoundary.DESCRIPTION - 1); lengths[index] += added; remaining -= added }
    return build { lengths[it] }.also { check(it.json().toString().toByteArray(Charsets.UTF_8).size == bytes) { "Fixture sizing drifted" } }
}

/** The contract is inside every bound. */
private fun accepted(subject: String, block: () -> Unit) {
    try { block() } catch (error: CapabilityRejected) { throw AssertionError("$subject: unexpected rejection ${error.message}") }
}

private fun mentions(error: Throwable, subject: String, parts: Array<out String>) {
    val message = error.message ?: ""
    for (part in parts) check(message.contains(part)) { "$subject: rejection '$message' does not name '$part'" }
}

/** The boundary rejects with `INVALID_ARGUMENT` and names the contract, field and bound. */
private fun rejected(subject: String, vararg located: String, block: () -> Unit) {
    try {
        block()
        throw AssertionError("$subject: expected a located boundary rejection")
    } catch (error: CapabilityRejected) {
        check(error.code == "INVALID_ARGUMENT") { "$subject: boundary rejection must be INVALID_ARGUMENT, got ${error.code}" }
        mentions(error, subject, located)
    }
}

/** A decode refusal that is not a boundary rejection still names the field. */
private fun refused(subject: String, vararg located: String, block: () -> Unit) {
    try {
        block()
        throw AssertionError("$subject: expected a decode refusal")
    } catch (error: IllegalArgumentException) { mentions(error, subject, located) }
}

/** Disclosure admission state over an opaque provider entry: the registry's contract/provider shape. */
private class Entry(val contract: CapabilityContract)

fun main() {
    // Version 1 is the only accepted contract version.
    accepted("version 1") { CapabilityBoundary.validate(contract()) }
    rejected("version 2", "version", "must be 1", "got 2") { CapabilityBoundary.validate(CapabilityContract("host/probe", "Explicit fixture read", listOf(function()), 2)) }

    // 16384 UTF-8 bytes of disclosed contract JSON, below and above.
    accepted("contract at 16384 UTF-8 bytes") { CapabilityBoundary.validate(sized(16384)) }
    rejected("contract at 16385 UTF-8 bytes", "16384", "16385") { CapabilityBoundary.validate(sized(16385)) }
    accepted("disclosed text at 16384 UTF-8 bytes") { check(CapabilityContract.parse(sized(16384).json().toString()) == sized(16384)) { "Decode must preserve the contract at the bound" } }
    rejected("disclosed text at 16385 UTF-8 bytes", "16384", "16385") { CapabilityContract.parse(sized(16385).json().toString()) }
    rejected("disclosed text padded over the byte bound", "16384") { CapabilityContract.parse(" " + sized(16384).json().toString() + " ") }

    // Module identity.
    accepted("host/calendar-v1") { CapabilityBoundary.validate(contract(module = "host/calendar-v1")) }
    accepted("one character local segment") { CapabilityBoundary.validate(contract(module = "host/a")) }
    accepted("63 character local segment") { CapabilityBoundary.validate(contract(module = "host/" + "a".repeat(63))) }
    // The spec quantifier is `[a-z][a-z0-9-]{0,63}`: 63 further characters are still inside it.
    accepted("64 character local segment") { CapabilityBoundary.validate(contract(module = "host/" + "a".repeat(64))) }
    rejected("module without the host prefix", "module", "host/[a-z][a-z0-9-]{0,63}", "calendar") { CapabilityBoundary.validate(contract(module = "calendar")) }
    rejected("module with an uppercase segment", "module", "host/Calendar") { CapabilityBoundary.validate(contract(module = "host/Calendar")) }
    rejected("local segment past the quantifier", "module", "host/" + "a".repeat(65)) { CapabilityBoundary.validate(contract(module = "host/" + "a".repeat(65))) }
    rejected("module with a trailing separator", "module", "host/a/") { CapabilityBoundary.validate(contract(module = "host/a/")) }

    // Function count, uniqueness and declaration representability.
    accepted("one function") { CapabilityBoundary.validate(contract()) }
    accepted("32 uniquely named functions") { CapabilityBoundary.validate(contract(functions = (0 until 32).map { function(name = "read$it") })) }
    rejected("no functions", "functions", "must number 1 to 32", "got 0") { CapabilityBoundary.validate(contract(functions = emptyList())) }
    rejected("33 functions", "functions", "must number 1 to 32", "got 33") { CapabilityBoundary.validate(contract(functions = (0 until 33).map { function(name = "read$it") })) }
    rejected("duplicate function name", "functions[1].name", "must be unique", "'read'") { CapabilityBoundary.validate(contract(functions = listOf(function(name = "read"), function(name = "read")))) }
    rejected("function name outside the identifier syntax", "functions[0].name", "[A-Za-z][A-Za-z0-9_]{0,63}", "read-me") { CapabilityBoundary.validate(contract(functions = listOf(function(name = "read-me")))) }
    rejected("keyword function name", "functions[0].name", "DEAL declaration", "class") { CapabilityBoundary.validate(contract(functions = listOf(function(name = "class")))) }
    rejected("function name above 64 characters", "functions[0].name", "[A-Za-z][A-Za-z0-9_]{0,63}") { CapabilityBoundary.validate(contract(functions = listOf(function(name = "r" + "a".repeat(64))))) }

    // Parameter count, uniqueness and syntax.
    accepted("8 uniquely named parameters") { CapabilityBoundary.validate(contract(functions = listOf(function(parameters = (0 until 8).map { Parameter("p$it", scalar) })))) }
    rejected("9 parameters", "functions[0].parameters", "must number at most 8", "got 9") { CapabilityBoundary.validate(contract(functions = listOf(function(parameters = (0 until 9).map { Parameter("p$it", scalar) })))) }
    rejected("duplicate parameter name", "functions[0].parameters[1].name", "must be unique", "'p0'") { CapabilityBoundary.validate(contract(functions = listOf(function(parameters = listOf(Parameter("p0", scalar), Parameter("p0", scalar)))))) }
    rejected("parameter name outside the identifier syntax", "functions[0].parameters[0].name", "1st") { CapabilityBoundary.validate(contract(functions = listOf(function(parameters = listOf(Parameter("1st", scalar)))))) }

    // Descriptions measure UTF-16 units.
    accepted("1 unit contract description") { CapabilityBoundary.validate(contract(description = "d")) }
    accepted("512 unit contract description") { CapabilityBoundary.validate(contract(description = "d".repeat(512))) }
    rejected("empty contract description", "description", "must be 1 to 512", "got 0") { CapabilityBoundary.validate(contract(description = "")) }
    rejected("513 unit contract description", "description", "got 513") { CapabilityBoundary.validate(contract(description = "d".repeat(513))) }
    rejected("supplementary scalars count two units", "description", "got 514") { CapabilityBoundary.validate(contract(description = "\uD83D\uDE00".repeat(257))) }
    accepted("512 unit function description") { CapabilityBoundary.validate(contract(functions = listOf(function(description = "d".repeat(512))))) }
    rejected("empty function description", "functions[0].description", "must be 1 to 512") { CapabilityBoundary.validate(contract(functions = listOf(function(description = "")))) }
    rejected("513 unit function description", "functions[0].description", "got 513") { CapabilityBoundary.validate(contract(functions = listOf(function(description = "d".repeat(513))))) }

    // Schemas: depth, fields, maximum, kinds, bounds and inactive metadata.
    fun depth(levels: Int): WireType = if (levels == 0) scalar else WireType("record", fields = listOf(WireField("next", depth(levels - 1))))
    accepted("schema at depth 8") { CapabilityBoundary.validate(contract(functions = listOf(function(result = depth(8))))) }
    rejected("schema at depth 9", "functions[0].result", "must be at depth at most 8") { CapabilityBoundary.validate(contract(functions = listOf(function(result = depth(9))))) }
    fun fields(count: Int) = WireType("record", fields = (0 until count).map { WireField("f$it", scalar) })
    accepted("32 record fields") { CapabilityBoundary.validate(contract(functions = listOf(function(result = fields(32))))) }
    rejected("33 record fields", "functions[0].result.fields", "must number at most 32", "got 33") { CapabilityBoundary.validate(contract(functions = listOf(function(result = fields(33))))) }
    accepted("string maximum 0") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("string", maximum = 0))))) }
    accepted("string maximum 8192") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("string", maximum = 8192))))) }
    rejected("string maximum 8193", "functions[0].result.maximum", "8192", "got 8193") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("string", maximum = 8193))))) }
    rejected("negative maximum", "functions[0].result.maximum", "got -1") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("string", maximum = -1))))) }
    rejected("inactive maximum on a number", "functions[0].result.maximum", "got 8193") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("number", maximum = 8193))))) }
    rejected("fields outside a record", "functions[0].result.fields", "must be empty outside a record") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("number", fields = listOf(WireField("x", scalar))))))) }
    rejected("element outside an array", "functions[0].result.element", "must be absent outside an array") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("string", element = scalar))))) }
    rejected("array without element metadata", "functions[0].result.element", "must be present for an array") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("array"))))) }
    rejected("unknown wire kind", "functions[0].result.kind", "decimal") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("decimal"))))) }
    rejected("non-finite numeric bound", "functions[0].result.numbers", "must be finite") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("number", maximumNumber = Double.POSITIVE_INFINITY))))) }
    rejected("minimum above maximum", "functions[0].result.numbers", "minimum at most maximum") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("number", minimumNumber = 2.0, maximumNumber = 1.0))))) }
    rejected("duplicate record field name", "functions[0].result.fields[1].name", "must be unique", "'f'") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("record", fields = listOf(WireField("f", scalar), WireField("f", scalar))))))) }
    rejected("invalid record field name", "functions[0].result.fields[0].name", "f-1") { CapabilityBoundary.validate(contract(functions = listOf(function(result = WireType("record", fields = listOf(WireField("f-1", scalar))))))) }
    rejected("parameter schema at depth 9", "functions[0].parameters[0].type", "must be at depth at most 8") {
        CapabilityBoundary.validate(contract(functions = listOf(function(parameters = listOf(Parameter("value", depth(9)))))))
    }
    rejected("parameter maximum 8193", "functions[0].parameters[0].type.maximum", "got 8193") {
        CapabilityBoundary.validate(contract(functions = listOf(function(parameters = listOf(Parameter("value", WireType("string", maximum = 8193)))))))
    }
    rejected("parameter schema past the field bound", "functions[0].parameters[0].type.fields", "got 33") {
        CapabilityBoundary.validate(contract(functions = listOf(function(parameters = listOf(Parameter("value", fields(33)))))))
    }

    // The whole proposed set rejects duplicate modules before anything is published.
    accepted("unique modules in one set") { CapabilityBoundary.validate(listOf(contract(module = "host/one"), contract(module = "host/two"))) }
    rejected("duplicate module in one set", "contract[1]", "module", "must be unique", "host/one") { CapabilityBoundary.validate(listOf(contract(module = "host/one"), contract(module = "host/one"))) }

    // Decode and round trip through the production codec.
    val decoded = contract(functions = listOf(function(parameters = listOf(Parameter("minute", scalar)), result = WireType("record", fields = listOf(WireField("id", WireType("string", maximum = 64)), WireField("label", WireType("string", maximum = 64), optional = true))))))
    val roundTrip = CapabilityContract.parse(decoded.json().toString())
    check(roundTrip == decoded) { "Contract decode must preserve every field, including optional absence" }
    check(roundTrip.json().toString() == decoded.json().toString()) { "Contract decode must round trip canonically" }
    refused("text with an unknown field", "Capability contract", "unknown or missing fields") { CapabilityContract.parse(decoded.json().put("extra", true).toString()) }
    refused("text with a non-integral version", "integral version") { CapabilityContract.parse(decoded.json().put("version", 1.5).toString()) }
    rejected("text with a version 2 contract", "version", "must be 1") { CapabilityContract.parse(CapabilityContract("host/probe", "Explicit fixture read", listOf(function()), 2).json().toString()) }
    rejected("provider registration past the parameter bound", "functions[0].parameters", "must number at most 8") {
        nativeCapabilities(contract(functions = listOf(function(parameters = (0 until 9).map { Parameter("p$it", scalar) }))), mapOf("read" to { _: JSONArray -> 1 }))
    }
    rejected("provider registration of an oversized contract", "16384") { nativeCapabilities(sized(16385), mapOf("read0" to { _: JSONArray -> 1 })) }

    // Atomic disclosure: a valid-plus-invalid batch replaces no contract, declaration or provider.
    var invocations = 0
    val probe = nativeCapabilities(contract("host/probe"), mapOf("read" to { _: JSONArray -> invocations++; 7 }))
    val disclosure = CapabilityDisclosure(listOf(probe)) { it.contract }
    val acceptedBefore = disclosure.accepted()
    val contractsBefore = disclosure.contracts()
    check(contractsBefore == listOf(probe.contract)) { "Initial disclosure must publish the registered contract" }
    rejected("duplicate module in a valid-plus-invalid batch", "host/probe", "module", "must be unique") {
        disclosure.admit(listOf(probe, nativeCapabilities(contract("host/probe"), mapOf("read" to { _: JSONArray -> invocations++; 8 }))), live = false)
    }
    check(invocations == 0) { "Admission must not invoke a provider" }
    check(disclosure.accepted() === acceptedBefore) { "A rejected disclosure replaced the accepted provider set" }
    check(disclosure.contracts() == contractsBefore) { "A rejected disclosure changed the disclosed contracts" }
    check(probe.invoke("read", JSONArray()) == 7 && invocations == 1) { "The accepted provider still answers after a rejection" }
    val extra = nativeCapabilities(contract("host/extra"), mapOf("read" to { _: JSONArray -> invocations++; 9 }))
    accepted("an accepted proposal") { check(disclosure.admit(listOf(probe, extra), live = false).map { it.module } == listOf("host/probe", "host/extra")) }
    val liveContracts = disclosure.contracts()
    val liveAccepted = disclosure.accepted()
    val changed = nativeCapabilities(CapabilityContract("host/probe", "Changed read", listOf(function(description = "Changed")), 1), mapOf("read" to { _: JSONArray -> invocations++; 10 }))
    refused("changed contract while a Session is live", "Close active sessions before changing existing capability contracts") { disclosure.admit(listOf(changed, extra), live = true) }
    check(disclosure.contracts() == liveContracts && disclosure.accepted() === liveAccepted) { "A live Session keeps its accepted contract" }

    // Per-contract violations reject their whole batch through the same admission.
    val entries = CapabilityDisclosure(listOf(Entry(contract("host/probe")))) { it.contract }
    val entryAccepted = entries.accepted()
    val entryContracts = entries.contracts()
    val invalid = Entry(CapabilityContract("host/invalid", "d".repeat(513), listOf(function()), 1))
    rejected("valid-plus-invalid batch", "contract[2] 'host/invalid'", "description", "got 513") {
        entries.admit(listOf(Entry(contract("host/probe")), Entry(contract("host/extra")), invalid), live = false)
    }
    check(entries.accepted() === entryAccepted) { "A rejected batch replaced the accepted entry set" }
    check(entries.contracts() == entryContracts) { "A rejected batch changed the disclosed contracts" }
    rejected("disclosure of an oversized contract", "16384") { entries.admit(listOf(Entry(sized(16385))), live = false) }
    check(entries.contracts() == entryContracts) { "An oversized disclosure replaced the accepted contracts" }
    accepted("the same batch without the invalid contract") {
        check(entries.admit(listOf(Entry(contract("host/probe")), Entry(contract("host/extra"))), live = false).map { it.module } == listOf("host/probe", "host/extra"))
    }

    println("capability boundary: every contract bound rejects with a located INVALID_ARGUMENT and atomic disclosure keeps the accepted set and providers")
}
