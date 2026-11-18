package dev.deal.apps.calendar

import dev.deal.embedding.*
import dev.deal.embedding.capabilities.*
import org.json.JSONObject

fun calendarCapabilities(start: () -> Int, propose: (Int) -> Unit): NativeCapabilities {
    val minute = WireType("number", minimumNumber = 0.0, maximumNumber = 1439.0)
    val contract = CapabilityContract("host/calendar", "Selected Calendar event and native-reviewed departure proposal", listOf(
        CapabilityFunction("start", "Read selected event start minute and capture its revision", emptyList(), minute),
        CapabilityFunction("propose", "Stage an integral minute of day (0..1439) for native user confirmation; never writes", listOf(Parameter("minute", minute)), WireType.NullType)))
    return NativeCapabilities(contract, mapOf("start" to { _ -> start() }, "propose" to { args -> val value = args.getDouble(0); require(value.isFinite() && value in 0.0..1439.0 && value == kotlin.math.floor(value)); propose(value.toInt()); JSONObject.NULL }))
}
