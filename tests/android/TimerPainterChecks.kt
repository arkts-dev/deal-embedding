package dev.deal.cutover.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.deal.embedding.DealRenderer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil

/**
 * A host-owned lifecycle the check drives while the page itself stays resumed. The lifecycle owner
 * around a renderer is its visibility contract, so below RESUMED a tick loop that was not released
 * keeps writing its reading and the still-visible text moves: the check can then tell a released
 * timer from one that is only hidden.
 */
class DrivenLifecycle : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /** Move the owner to [state]; the check calls this on the main thread. */
    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

/**
 * Test-only host: the production renderer paints one native Timer from the intent's epoch. With
 * `driven` the node renders under [DrivenLifecycle] so the check can move the renderer's
 * visibility while the page stays visible; otherwise the activity's own lifecycle applies.
 */
class TimerPainterActivity : ComponentActivity() {
    @Volatile var epoch = 0L

    /** Actions the renderer dispatched: ticking is presentation only, so this stays zero. */
    @Volatile var dispatched = 0

    private var driven: DrivenLifecycle? = null
    private var down = true
    private val tree = mutableStateOf(rootTree(JSONArray()))

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        down = intent.getBooleanExtra("down", true)
        epoch = if (intent.hasExtra("epochMillis")) intent.getLongExtra("epochMillis", 0L)
                else System.currentTimeMillis() + intent.getLongExtra("offsetMillis", 30_000L)
        tree.value = timerTree(epoch, down)
        val gate = if (intent.getBooleanExtra("driven", false)) DrivenLifecycle().also {
            it.moveTo(Lifecycle.State.RESUMED)
            driven = it
        } else null
        setContent {
            MaterialTheme {
                if (gate == null) {
                    DealRenderer(tree.value, "screen") { _, _ -> dispatched++ }
                } else {
                    CompositionLocalProvider(LocalLifecycleOwner provides gate) {
                        DealRenderer(tree.value, "screen") { _, _ -> dispatched++ }
                    }
                }
            }
        }
    }

    /** The renderer's visibility gate: from here the renderer is hidden while the page stays visible. */
    fun hide() = requireNotNull(driven) { "this host renders under the activity lifecycle" }.moveTo(Lifecycle.State.STARTED)

    /** The renderer is visible again. */
    fun show() = requireNotNull(driven) { "this host renders under the activity lifecycle" }.moveTo(Lifecycle.State.RESUMED)

    /** Drop the Timer node while the page stays open, so nothing left in the tree can paint it. */
    fun removeTimer() {
        tree.value = textTree("timer removed")
    }

    /** Paint the same epoch again as a new node, which is a new identity for the tick loop. */
    fun restoreTimer() {
        tree.value = timerTree(epoch, down)
    }
}

private fun prop(name: String, value: Any): JSONObject = JSONObject().put("name", name).apply {
    when (value) {
        is Double -> put("kind", "number").put("numberValue", value)
        is Boolean -> put("kind", "boolean").put("booleanValue", value)
        else -> put("kind", "string").put("stringValue", value.toString())
    }
}

private fun node(component: String, props: JSONArray, children: JSONArray = JSONArray()): JSONObject =
    JSONObject().put("component", component).put("props", props).put("children", children)

private fun rootTree(children: JSONArray): JSONObject = node("root", JSONArray(), children)

private fun timerTree(epoch: Long, down: Boolean): JSONObject = rootTree(JSONArray().put(
    node("Timer", JSONArray().put(prop("millis", epoch.toDouble())).put(prop("down", down))),
))

private fun textTree(text: String): JSONObject =
    rootTree(JSONArray().put(node("Text", JSONArray().put(prop("text", text)))))

private fun composeRoot(view: View): RootForTest? {
    if (view is RootForTest) return view
    if (view !is ViewGroup) return null
    for (index in 0 until view.childCount) composeRoot(view.getChildAt(index))?.let { return it }
    return null
}

/** Every text the painted tree currently publishes through semantics. */
private fun texts(activity: Activity): List<String> {
    val root = composeRoot(activity.window.decorView) ?: return emptyList()
    val out = mutableListOf<String>()
    fun visit(node: SemanticsNode) {
        val config = node.config
        if (config.contains(SemanticsProperties.Text)) {
            out.add(config[SemanticsProperties.Text].joinToString("") { it.text })
        }
        node.children.forEach(::visit)
    }
    visit(root.semanticsOwner.rootSemanticsNode)
    return out
}

private fun shownTexts(instrumentation: Instrumentation, activity: Activity): List<String> {
    var shown: List<String> = emptyList()
    instrumentation.runOnMainSync { shown = texts(activity) }
    return shown
}

private fun timerText(shown: List<String>): String =
    shown.firstOrNull { it.endsWith(" left") || it.endsWith(" ago") } ?: ""

private fun awaitTexts(instrumentation: Instrumentation, activity: Activity, timeoutMillis: Long = 5_000L,
                       accept: (List<String>) -> Boolean): List<String> {
    val deadline = System.currentTimeMillis() + timeoutMillis
    var last: List<String> = emptyList()
    while (System.currentTimeMillis() < deadline) {
        val shown = shownTexts(instrumentation, activity)
        last = shown
        if (accept(shown)) return shown
        Thread.sleep(50)
    }
    error("the painted tree never showed the expected presentation within ${timeoutMillis}ms, last: $last")
}

private fun awaitTimer(instrumentation: Instrumentation, activity: Activity, timeoutMillis: Long = 5_000L,
                       accept: (String) -> Boolean): String {
    val shown = awaitTexts(instrumentation, activity, timeoutMillis) { candidate ->
        val text = timerText(candidate)
        text.isNotEmpty() && accept(text)
    }
    return timerText(shown)
}

private fun seconds(text: String): Int = runCatching {
    text.substringBefore(" ").split(":").fold(0) { total, part -> total * 60 + part.toInt() }
}.getOrElse { error("the painter showed an unparsable Timer text: '$text'") }

private fun deadlineSeconds(epochMillis: Long): Int = ceil((epochMillis - System.currentTimeMillis()) / 1_000.0).toInt()

private fun originSeconds(epochMillis: Long): Int = ((System.currentTimeMillis() - epochMillis) / 1_000).toInt()

private fun open(instrumentation: Instrumentation, down: Boolean, offsetMillis: Long,
                 driven: Boolean = false, epochMillis: Long? = null): TimerPainterActivity {
    val intent = Intent(instrumentation.context, TimerPainterActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("down", down)
        .putExtra("offsetMillis", offsetMillis)
        .putExtra("driven", driven)
    if (epochMillis != null) intent.putExtra("epochMillis", epochMillis)
    return instrumentation.startActivitySync(intent) as TimerPainterActivity
}

/** Wait until the page has actually left the resumed state, so a sampling window is not a guess. */
private fun awaitNotResumed(instrumentation: Instrumentation, activity: ComponentActivity) {
    val deadline = System.currentTimeMillis() + 5_000L
    while (System.currentTimeMillis() < deadline) {
        var state = Lifecycle.State.RESUMED
        instrumentation.runOnMainSync { state = activity.lifecycle.currentState }
        if (!state.isAtLeast(Lifecycle.State.RESUMED)) return
        Thread.sleep(50)
    }
    error("the page never left the resumed state")
}

/** Wait until the page is resumed again, so the sample below is the first resumed frame's reading. */
private fun awaitResumed(instrumentation: Instrumentation, activity: ComponentActivity) {
    val deadline = System.currentTimeMillis() + 5_000L
    while (System.currentTimeMillis() < deadline) {
        var state = Lifecycle.State.CREATED
        instrumentation.runOnMainSync { state = activity.lifecycle.currentState }
        if (state.isAtLeast(Lifecycle.State.RESUMED)) return
        Thread.sleep(50)
    }
    error("the page never returned to the resumed state")
}

/**
 * The first reading the frame after a state change publishes that differs from [frozen]; the frame,
 * never the expected value, is awaited. A renderer that resumes from its frozen reading changes its
 * text only by the seconds it replays inside this window, so it stays more than the one-second
 * tolerance off the clock, while a reading recomputed from the clock is on it. The wait is bounded
 * well below the hidden gap, so a frame that never repaints fails here instead of passing later.
 */
private fun firstChangedTimer(instrumentation: Instrumentation, activity: Activity, frozen: String,
                              settleMillis: Long = 2_500L): String {
    val deadline = System.currentTimeMillis() + settleMillis
    while (System.currentTimeMillis() < deadline) {
        val text = timerText(shownTexts(instrumentation, activity))
        if (text.isNotEmpty() && text != frozen) return text
        Thread.sleep(50)
    }
    error("the paint after the state change kept the frozen reading '$frozen' for ${settleMillis}ms")
}

private fun close(instrumentation: Instrumentation, activity: Activity) {
    instrumentation.runOnMainSync { activity.finish() }
    instrumentation.waitForIdleSync()
}

/**
 * Production-renderer check: the packaged Compose renderer, mounted in a real activity, must show
 * an epoch deadline and an epoch origin as distances from the presentation clock that advance
 * while visible, must release its tick loop when the host lifecycle leaves RESUMED and recompute
 * from the clock when it returns without replaying the skipped seconds, must stop at a reached
 * deadline, must dispatch nothing while ticking, and must stop painting when the node leaves the
 * tree.
 *
 * The removed epoch-division painter printed the raw epoch as a fixed distance, so it equals every
 * negative-control reading and fails the clock comparisons and the progress items below. A tick loop
 * without the lifecycle gate keeps writing its reading while the host is below RESUMED, so it fails
 * the release item; a tick loop that resumes its frozen reading instead of reading the clock fails
 * the return items, whose first changed reading is still more than the one-second tolerance off the
 * clock. Timer styles resolve through `StyleFamilies.heading`, proven by the deterministic rule item;
 * the one-line typography mapping it feeds mirrors the Text painter and is not observable through
 * semantics.
 */
fun checkTimerPainter(instrumentation: Instrumentation): String {
    val steps = mutableListOf<String>()

    // Direction one and the release gate: `down` is a deadline measured from the presentation clock,
    // and this node renders under a host lifecycle the check drives. The page stays visible while the
    // renderer is told it is hidden, so a released tick loop leaves the painted second where it was
    // while an ungated loop keeps writing its reading and the visible text moves.
    val gated = open(instrumentation, down = true, offsetMillis = 60_000L, driven = true)
    try {
        val epoch = gated.epoch
        val first = awaitTimer(instrumentation, gated) { true }
        check(first.endsWith(" left")) { "a deadline renders as remaining time, got '$first'" }
        check(first != EpochDivisionNegativeControl.text(epoch, down = true)) { "the painter displayed the epoch itself: '$first'" }
        check(abs(seconds(first) - deadlineSeconds(epoch)) <= 1) { "a 60s deadline near its start showed '$first'" }
        val advanced = awaitTimer(instrumentation, gated) { seconds(it) < seconds(first) }
        steps += "deadline=$first; visible=$advanced"

        instrumentation.runOnMainSync { gated.hide() }
        Thread.sleep(500)
        val released = timerText(shownTexts(instrumentation, gated))
        check(released.isNotEmpty()) { "the hidden renderer lost its timer" }
        Thread.sleep(4_000)
        val stillReleased = timerText(shownTexts(instrumentation, gated))
        check(stillReleased == released) { "ticking survived the host leaving RESUMED: '$released' then '$stillReleased'" }
        steps += "released=$released"

        // Returning re-derives from the presentation clock instead of replaying the skipped seconds:
        // the first resumed reading that leaves the frozen one already reports the current distance,
        // while a renderer that resumes from its frozen reading stays seconds off the clock.
        instrumentation.runOnMainSync { gated.show() }
        val resumedGate = firstChangedTimer(instrumentation, gated, stillReleased)
        val expectedAfterGate = deadlineSeconds(epoch)
        check(abs(seconds(resumedGate) - expectedAfterGate) <= 1) {
            "returning to RESUMED did not recompute from the clock: '$resumedGate' against ${expectedAfterGate}s"
        }
        check(seconds(resumedGate) < seconds(stillReleased)) {
            "the resumed reading did not advance past the frozen one: '$stillReleased' then '$resumedGate'"
        }
        check(gated.dispatched == 0) { "ticking dispatched ${gated.dispatched} action(s)" }
        steps += "resumed=$resumedGate"
    } finally {
        close(instrumentation, gated)
    }

    // The same renderer under the activity's own lifecycle: a backgrounded page paints no moving
    // second, its own resume reads the clock instead of replaying the hidden seconds, a removed node
    // stops painting, and a node painted again is a new identity that starts from the clock.
    val activity = open(instrumentation, down = true, offsetMillis = 60_000L)
    try {
        val epoch = activity.epoch
        val before = awaitTimer(instrumentation, activity) { true }
        check(abs(seconds(before) - deadlineSeconds(epoch)) <= 1) { "a 60s deadline near its start showed '$before'" }

        instrumentation.runOnMainSync { activity.moveTaskToBack(true) }
        awaitNotResumed(instrumentation, activity)
        instrumentation.waitForIdleSync()
        val hidden = timerText(shownTexts(instrumentation, activity))
        check(hidden.isNotEmpty()) { "the hidden page lost its timer" }
        check(seconds(hidden) > 0) { "the deadline passed before it was hidden: '$hidden'" }
        Thread.sleep(4_000)
        val stillHidden = timerText(shownTexts(instrumentation, activity))
        check(stillHidden == hidden) { "the stopped page kept painting seconds: '$hidden' then '$stillHidden'" }
        steps += "hidden=$stillHidden"

        // The first frame after the page resumes reports the current distance, not a replay of the
        // seconds it skipped: the first reading that leaves the frozen one is already on the clock,
        // while a renderer that resumes its frozen reading stays seconds behind it.
        val resume = Intent(instrumentation.context, TimerPainterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        instrumentation.runOnMainSync { activity.startActivity(resume) }
        awaitResumed(instrumentation, activity)
        val returned = firstChangedTimer(instrumentation, activity, stillHidden)
        val expected = deadlineSeconds(epoch)
        check(abs(seconds(returned) - expected) <= 1) {
            "return after hiding did not recompute from the clock: '$returned' against ${expected}s"
        }
        check(seconds(returned) <= seconds(hidden) - 1) {
            "the resumed reading did not advance past the frozen one: '$hidden' then '$returned'"
        }
        steps += "returned=$returned"

        // Removing the node leaves no timer in the painted tree: its text disappears, stays gone
        // while a live Timer would have repainted, and the rest of the page keeps rendering.
        instrumentation.runOnMainSync { activity.removeTimer() }
        awaitTexts(instrumentation, activity) { shown -> "timer removed" in shown && timerText(shown).isEmpty() }
        Thread.sleep(3_500)
        val removed = shownTexts(instrumentation, activity)
        check(timerText(removed).isEmpty()) { "a removed timer kept painting: $removed" }
        check("timer removed" in removed) { "removing the timer displaced the page: $removed" }
        // Painted again, the node is a new identity: its first frame starts from the presentation
        // clock instead of continuing the reading of the timer removed 3.5s earlier, which is outside
        // the tolerance, so the first reading it publishes decides it.
        instrumentation.runOnMainSync { activity.restoreTimer() }
        val readded = awaitTimer(instrumentation, activity) { true }
        val repainted = deadlineSeconds(epoch)
        check(abs(seconds(readded) - repainted) <= 1) {
            "a new Timer node did not start from the clock: '$readded' against ${repainted}s"
        }
        val readdedAdvanced = awaitTimer(instrumentation, activity) { seconds(it) < seconds(readded) }
        check(activity.dispatched == 0) { "ticking dispatched ${activity.dispatched} action(s)" }
        steps += "removed=${removed.joinToString("|")}; readded=$readded->$readdedAdvanced"
    } finally {
        close(instrumentation, activity)
    }

    // Direction two: an origin counts elapsed time forward in the same production painter.
    val origin = open(instrumentation, down = false, offsetMillis = -3_000L)
    try {
        val elapsed = awaitTimer(instrumentation, origin) { true }
        check(elapsed.endsWith(" ago")) { "an origin renders as elapsed time, got '$elapsed'" }
        check(elapsed != EpochDivisionNegativeControl.text(origin.epoch, down = false)) {
            "the painter displayed the epoch itself: '$elapsed'"
        }
        check(abs(seconds(elapsed) - originSeconds(origin.epoch)) <= 1) { "a 3s-old origin showed '$elapsed'" }
        val progressed = awaitTimer(instrumentation, origin) { seconds(it) > seconds(elapsed) }
        steps += "origin=$elapsed->$progressed"
    } finally {
        close(instrumentation, origin)
    }

    // Signed finite epochs are valid: a pre-1970 origin is a real distance from the clock, painted
    // as the elapsed hours instead of as the epoch itself.
    val old = open(instrumentation, down = false, offsetMillis = 0L, epochMillis = -86_400_000L)
    try {
        val elapsed = awaitTimer(instrumentation, old) { true }
        check(elapsed.endsWith(" ago")) { "a pre-1970 origin renders as elapsed time, got '$elapsed'" }
        check(elapsed != EpochDivisionNegativeControl.text(old.epoch, down = false)) {
            "the painter displayed the epoch itself: '$elapsed'"
        }
        check(abs(seconds(elapsed) - originSeconds(old.epoch)) <= 1) { "a 56-year-old origin showed '$elapsed'" }
        steps += "negative=$elapsed"
    } finally {
        close(instrumentation, old)
    }

    // A reached deadline keeps painting zero instead of counting past it, and it never reaches zero
    // before the deadline: a rounded-down reading would show zero up to a second early. The exact
    // boundary arithmetic is pinned by the deterministic tick-loop item over the same rule object.
    val finished = open(instrumentation, down = true, offsetMillis = 4_000L)
    try {
        val reached = finished.epoch
        val zero = awaitTimer(instrumentation, finished, timeoutMillis = 8_000L) { it == "0:00 left" }
        val observedAt = System.currentTimeMillis()
        check(observedAt >= reached) { "the countdown reached zero ${reached - observedAt}ms before its deadline" }
        check(observedAt <= reached + 2_000L) {
            "the countdown reached zero ${observedAt - reached}ms after its deadline"
        }
        Thread.sleep(1_200)
        val after = timerText(shownTexts(instrumentation, finished))
        check(after == zero) { "a finished countdown kept changing: '$zero' then '$after'" }
        check(finished.dispatched == 0) { "a finished countdown dispatched ${finished.dispatched} action(s)" }
        steps += "stopped=$zero"
    } finally {
        close(instrumentation, finished)
    }
    return steps.joinToString("; ")
}

/** The exact spelling the removed epoch-division presentation produced, for the negative control. */
private object EpochDivisionNegativeControl {
    fun text(epochMillis: Long, down: Boolean): String =
        "${Math.floorDiv(epochMillis, 1_000L)}s" + if (down) " left" else " ago"
}
