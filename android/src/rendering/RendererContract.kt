package dev.deal.embedding

import android.content.Context
import org.json.JSONObject

/**
 * The issued pack facts and the private host target, delivered to a compiled Session as one object
 * before it mounts. The facts are the generated projections the renderer tables are compiled from, so
 * the Session boundary and the renderer read one conformance-checked source; a Session built without
 * them fails closed instead of committing an unvalidated candidate.
 *
 * Only immutable packaged text is cached. The target is host configuration: it never comes from
 * candidate source, a checker argument or tree content.
 */
internal object RendererContract {
    private val scripts = java.util.WeakHashMap<android.content.res.AssetManager, MutableMap<String, String>>()

    @Synchronized fun script(context: Context, target: String): String =
        scripts.getOrPut(context.assets) { mutableMapOf() }.getOrPut(target) {
            RendererContractScript.assemble(
                target,
                projection(context, "embedding/prop-constraints.json"),
                projection(context, "embedding/card-profile.json"),
                projection(context, "embedding/prop-declarations.json"),
            )
        }

    /** A missing or corrupt projection is an issued-pack failure, never an unclassified I/O or syntax error. */
    private fun projection(context: Context, path: String): String {
        val text = try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (error: java.io.IOException) {
            RendererContractScript.missing(path)
        }
        try {
            JSONObject(text)
        } catch (error: org.json.JSONException) {
            RendererContractScript.unparseable(path)
        }
        return text
    }
}
