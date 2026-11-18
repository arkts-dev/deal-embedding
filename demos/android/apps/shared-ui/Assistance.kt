package dev.deal.shell

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.deal.embedding.*
import dev.deal.embedding.discovery.*

fun assistanceProtocol(context: Context, storageName: String = "assistance"): ApplicationProtocol {
    return Embedding.open(context, EmbeddingConfig(storageName, AssistanceBuild.gateway(), AssistanceBuild.discoveryGateway(), retainState = true),
        trustProvider = { uid -> uid != context.applicationInfo.uid && Connections.trusted(context, uid) })
}

/** App lifecycle/placement and retention decisions, not an execution driver. */
internal fun discoveryPreference() = if (AssistanceBuild.discoveryBackend == "nano") "discovery" else "discovery.${AssistanceBuild.discoveryBackend}"
internal fun discoveryEnabled(context: Context) = Connections.settings(context).getBoolean(discoveryPreference(), AssistanceBuild.discoveryBackend == "nano")

/** Demo policy adapter; framework owns assistance execution and presentation. */
@Composable fun AssistedTask(
    facts: List<DiscoveryFact>, storageName: String = "assistance", title: String = "",
    protocolFactory: (Context) -> ApplicationProtocol = { assistanceProtocol(it, storageName) },
    hooks: AssistanceHooks? = null,
    review: @Composable (Experience) -> Unit = {}, content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val selectedHooks = hooks ?: remember(context) { DemoAssistanceHooks(context) }
    dev.deal.embedding.AssistedTask(facts, storageName, title, protocolFactory, selectedHooks,
        inspect = { experience, trace, since ->
            context.startActivity(AssistanceShell.inspect(context, experience, trace, since).putExtra("storage", storageName))
        }, review = review, content = content)
}

open class DemoAssistanceHooks(protected val context: Context) : AssistanceHooks {
    protected val settings get() = Connections.settings(context)
    override fun generationEnabled() = settings.getBoolean("generation", false)
    override fun discoveryEnabled() = dev.deal.shell.discoveryEnabled(context)
    override fun applyGrants(protocol: ApplicationProtocol) = Connections.apply(context, protocol)
    override fun saved(identity: String) = settings.getString("saved.$identity", null)
    override fun save(identity: String, experience: String) { check(settings.edit().putString("saved.$identity", experience).commit()) }
    override fun dismissed(identity: String) = settings.getBoolean("dismissed.$identity", false)
    override fun dismiss(identity: String) { check(settings.edit().putBoolean("dismissed.$identity", true).commit()) }
    override fun resetting() = AssistanceShell.resetting
    override fun register(owner: Any, stop: () -> Unit) = AssistanceShell.register(owner, stop)
    override fun unregister(owner: Any) = AssistanceShell.unregister(owner)
}
