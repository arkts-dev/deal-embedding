package dev.deal.apps.organizer

import dev.deal.embedding.*
import android.content.Context
import dev.deal.embedding.capabilities.NativeCapabilities
import dev.deal.embedding.nativeCapabilities

/** Contract registration only; domain behavior and persistence are Organizer-owned services. */
fun organizerCapabilities(context: Context): NativeCapabilities {
    val service = ProposalService(ProposalStore(context))
    return nativeCapabilities(organizerCapabilityContract) { invocation -> mapOf(
        "stage" to { args -> service.stage(args.getString(0), args.getString(1), args.getString(2),
            args.getString(3), args.getString(4), args.getString(5), invocation.experience) },
        "staged" to { _ -> service.staged() }
    ) }
}
