package dev.deal.apps.vehicle

import dev.deal.shell.SharedAppService

class VehicleService : SharedAppService() {
    override val capabilities by lazy { vehicleCapabilities() }
}
