package dev.deal.apps.organizer

import android.content.Context
import dev.deal.embedding.*
import dev.deal.shell.Connections
import dev.deal.shell.AssistanceBuild

class OrganizerHost(context: Context) {
    val embedding = Embedding.open(context,
        EmbeddingConfig("organizer-protocol", GenerationBuild.gateway(), AssistanceBuild.discoveryGateway(),
            retainState = true),
        listOf(organizerCapabilities(context)),
        trustProvider = { uid -> uid != context.applicationInfo.uid && Connections.trusted(context,uid) })
}
