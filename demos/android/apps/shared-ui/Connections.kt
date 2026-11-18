package dev.deal.shell

import android.content.Context
import android.content.pm.PackageManager
import android.content.SharedPreferences
import dev.deal.embedding.ApplicationProtocol
import dev.deal.embedding.capabilities.CapabilityService

/** Demo-app trust is separate from provider sharing and each consuming tool's grants. */
object Connections {
    val apps = setOf("calendar", "organizer", "rental", "pizza", "todo", "vehicle").map { "dev.deal.apps.$it" }.toSet()
    fun trusted(context: Context, uid: Int): Boolean {
        val packages = context.packageManager.getPackagesForUid(uid)?.toSet() ?: return false
        return packages.isNotEmpty() && packages.all { it in apps } &&
            context.packageManager.checkSignatures(uid, context.applicationInfo.uid) == PackageManager.SIGNATURE_MATCH
    }
    fun settings(context: Context): SharedPreferences = context.getSharedPreferences("hive", 0)
    fun apply(context: Context, api: ApplicationProtocol): List<String> {
        val settings = settings(context)
        val modules = api.refresh().map { it.module }
        modules.forEach { if (settings.getBoolean("grant.$it", false)) api.grant(it) else api.revoke(it) }
        return modules
    }
    fun name(module: String) = when (module) {
        "host/agenda", "host/calendar" -> "Calendar"
        "host/show", "host/organizer" -> "Organizer"
        "host/trip" -> "Vehicle"
        else -> module.substringAfterLast('/').replaceFirstChar { it.uppercase() }
    }
}

/** Sharing policy only. Binder validation, cancellation and invocation stay in embedding. */
abstract class SharedAppService : CapabilityService() {
    private val sharing by lazy { getSharedPreferences("provider", 0) }
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "allowed") consentChanged() }
    override fun onCreate() { super.onCreate(); sharing.registerOnSharedPreferenceChangeListener(listener) }
    override fun onDestroy() { sharing.unregisterOnSharedPreferenceChangeListener(listener); super.onDestroy() }
    final override fun consent() = sharing.getBoolean("allowed", false)
    final override fun authorize(uid: Int) = Connections.trusted(this, uid)
}
