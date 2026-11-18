package dev.deal.apps.todo

import dev.deal.shell.SharedAppService

class TodoService : SharedAppService() {
    override val capabilities by lazy { todoCapabilities(this) }
}
