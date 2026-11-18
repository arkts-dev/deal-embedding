package dev.deal.apps.vehicle

import dev.deal.embedding.*
import dev.deal.embedding.capabilities.*

fun vehicleCapabilities(): NativeCapabilities {
    val contract = CapabilityContract("host/trip", "Simulated vehicle travel, not live telemetry", listOf(
        CapabilityFunction("estimate", "Estimate travel minutes using the simulation 5 + 2 times integral distance in km (1..1000)", listOf(
            Parameter("distanceKm", WireType("number", minimumNumber = 1.0, maximumNumber = 1000.0))), WireType("number", minimumNumber = 7.0, maximumNumber = 2005.0))))
    return NativeCapabilities(contract, mapOf("estimate" to { args -> val distance = args.getDouble(0); require(distance.isFinite() && distance in 1.0..1000.0 && distance == kotlin.math.floor(distance)); 5 + 2 * distance.toInt() }))
}
