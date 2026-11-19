package dev.deal.embedding

import java.io.File

/**
 * Library-build renderer conformance: the entry set, each capability string, the projected
 * constraints and card profile and the prop declarations must agree with the packaged pack.
 */
object RendererConformance {
    @JvmStatic
    fun main(args: Array<String>) {
        val path = File(args[0])
        val pack = deal.ui.UiParser.parsePack(path.toPath(), path.readText())
        RenderComponent.verify(pack)
        println("renderer conformance: ${RenderComponent.entries.size} entries agree with the pack at version ${pack.version()}")
    }
}
