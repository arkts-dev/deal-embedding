package dev.deal.apps.rental

import dev.deal.shell.SharedAppService

class RentalService : SharedAppService() {
    override val capabilities by lazy { rentalCapabilities(this) }
}
