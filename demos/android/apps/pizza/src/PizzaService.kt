package dev.deal.apps.pizza

import dev.deal.shell.SharedAppService

class PizzaService : SharedAppService() {
    override val capabilities by lazy { pizzaCapabilities(this) }
}
