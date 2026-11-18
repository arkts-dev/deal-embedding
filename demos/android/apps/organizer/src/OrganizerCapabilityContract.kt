package dev.deal.apps.organizer

import dev.deal.embedding.*

/** Organizer-owned signatures; bindings and transport metadata derive from this contract. */
internal val organizerCapabilityContract: CapabilityContract = run {
    val text = WireType("string")
    CapabilityContract("host/organizer", "Stage a prepared operation for native review; never a write", functions = listOf(
        CapabilityFunction("stage", "Prepare one operation for the organizer to confirm natively", parameters = listOf(
            Parameter("kind", WireType("string", maximum = 64)), Parameter("provider", WireType("string", maximum = 64)),
            Parameter("title", WireType("string", maximum = 120)), Parameter("detail", WireType("string", maximum = 512)),
            Parameter("price", WireType("string", maximum = 32)), Parameter("deadline", WireType("string", maximum = 64))
        ), result = WireType("record", fields = listOf(WireField("staged", WireType("boolean")), WireField("reference", text)))),
        CapabilityFunction("staged", "Read the operations prepared so far", result = WireType("array", maximum = 16,
            element = WireType("record", fields = listOf(
                WireField("kind", text), WireField("provider", text), WireField("title", text),
                WireField("detail", text), WireField("price", text), WireField("deadline", text)))))
    ))
}
