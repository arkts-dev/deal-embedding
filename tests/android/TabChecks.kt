package dev.deal.cutover.tests

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device check of the actual painter over controlled Tabs trees: only the selected child is
 * composed, that child's controls and the tab row dispatch through the painter, and each keyed
 * child keeps the state it owns across a switch without seeing another child's state or reviving
 * superseded input. The composition, dispatch and saveable state holder are the painter's own,
 * observed through the framework's accessibility bridge; the tree is drawn through the public
 * painter exactly as a hosted surface draws it.
 */

private fun tabsProp(name: String, value: String) =
    JSONObject().put("name", name).put("kind", "string").put("stringValue", value)

private fun tabsNumber(name: String, value: Double) =
    JSONObject().put("name", name).put("kind", "number").put("numberValue", value)

private fun tabsStrings(name: String, value: String) =
    JSONObject().put("name", name).put("kind", "json").put("stringValue", value)

private fun tabsAction(name: String, slot: Int) =
    JSONObject().put("name", name).put("kind", "action").put("actionSlot", slot)

/**
 * One transported node. `structural` is the child's own structural path, so the painter's panel
 * identity is the child's, not the selection's and not the panel's position.
 */
private fun tabsNode(component: String, props: List<JSONObject>, children: List<JSONObject>, structural: String) = JSONObject()
    .put("component", component).put("props", JSONArray(props)).put("children", JSONArray(children))
    .put("structural", structural).put("keyKind", "none").put("keyNumber", 0).put("keyString", "")

/** One tab: a labelled field of its own and a body only this tab renders. */
private fun tabPanel(structural: String, label: String, value: String, slot: Int, body: String) = tabsNode(
    "Column", emptyList(),
    listOf(
        tabsNode("TextField", listOf(tabsProp("label", label), tabsProp("value", value), tabsAction("onChange", slot)), emptyList(), "$structural/0"),
        tabsNode("Text", listOf(tabsProp("text", body)), emptyList(), "$structural/1"),
    ),
    structural,
)

/**
 * Two labelled tabs; only the selected child may reach the display. `order` lists the child indices in
 * the order the tab row carries them, so a child can be moved to another position without changing
 * its own identity.
 */
private fun tabsTree(selected: Int, firstValue: String = "a", order: List<Int> = listOf(0, 1)): JSONObject {
    val children = listOf(
        tabPanel("tabs/0", "Note", firstValue, 1, "First panel body"),
        tabPanel("tabs/1", "Memo", "b", 2, "Second panel body"),
    )
    check(order.size == children.size && order.toSet() == children.indices.toSet()) { "order must list every child once" }
    val labels = order.joinToString(",") { "\"" + listOf("First", "Second")[it] + "\"" }
    return tabsNode(
        "Column", emptyList(),
        listOf(
            tabsNode(
                "Tabs",
                listOf(tabsStrings("labels", "[$labels]"), tabsNumber("selected", selected.toDouble()), tabsAction("onChange", 0)),
                order.map { children[it] },
                "tabs",
            )
        ),
        "root",
    )
}

/** A tree with no Tabs at all, so every rendered panel leaves the display. */
private fun emptyTree() = tabsNode("Column", emptyList(), emptyList(), "root")

/**
 * One tab whose only content is a full surface, so an injected tap lands on it, and a second plain
 * tab, so the row keeps one label per child.
 */
private fun pointerTree(width: Double = 1000.0) = tabsNode(
    "Column", emptyList(),
    listOf(
        tabsNode(
            "Tabs",
            listOf(tabsStrings("labels", """["Pointer","Other"]"""), tabsNumber("selected", 0.0), tabsAction("onChange", 0)),
            listOf(
                tabsNode(
                    "Column", emptyList(),
                    listOf(
                        tabsNode(
                            "Surface",
                            listOf(tabsNumber("width", width), tabsNumber("height", 1000.0), tabsStrings("shapes", "[]"), tabsAction("onPointer", 3)),
                            emptyList(), "pointer/0",
                        )
                    ),
                    "pointer",
                ),
                tabsNode("Column", emptyList(), listOf(tabsNode("Text", listOf(tabsProp("text", "Other panel body")), emptyList(), "other/0")), "other"),
            ),
            "tabs",
        )
    ),
    "root",
)

/** The displayed window's bounds, so an injected tap does not assume one screen size. */
private fun windowBounds(instrumentation: Instrumentation): android.graphics.Rect {
    val bounds = android.graphics.Rect()
    tabWindow(instrumentation).getBoundsInScreen(bounds)
    check(bounds.width() > 0 && bounds.height() > 0) { "the displayed window has no bounds" }
    return bounds
}

/** One real tap on the displayed surface, injected through the instrumentation's input channel. */
private fun tapSurface(instrumentation: Instrumentation, x: Float, y: Float) {
    val down = SystemClock.uptimeMillis()
    injectPointer(instrumentation, down, down, MotionEvent.ACTION_DOWN, x, y)
    injectPointer(instrumentation, down, down + 40, MotionEvent.ACTION_UP, x, y)
}

/** Presses the displayed surface without lifting, so the surface still owns a live gesture. */
private fun pressSurface(instrumentation: Instrumentation, x: Float, y: Float): Long {
    val down = SystemClock.uptimeMillis()
    injectPointer(instrumentation, down, down, MotionEvent.ACTION_DOWN, x, y)
    return down
}

/** Lifts the press that [pressSurface] started. */
private fun releaseSurface(instrumentation: Instrumentation, down: Long, x: Float, y: Float) {
    injectPointer(instrumentation, down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
}

private fun injectPointer(instrumentation: Instrumentation, down: Long, at: Long, action: Int, x: Float, y: Float) {
    val event = MotionEvent.obtain(down, at, action, x, y, 0)
    check(instrumentation.uiAutomation.injectInputEvent(event, true)) {
        "the input channel refused the pointer event $action"
    }
    event.recycle()
}

/** One accessibility node as the check sees it, captured before the bridge seals it. */
private class TabNode(val text: String?, val description: String?, val editable: Boolean, val clickable: Boolean)

private fun tabWindow(instrumentation: Instrumentation): AccessibilityNodeInfo {
    val automation = instrumentation.uiAutomation
    val deadline = SystemClock.elapsedRealtime() + 8000
    while (SystemClock.elapsedRealtime() < deadline) {
        automation.rootInActiveWindow?.let { return it }
        Thread.sleep(50)
    }
    error("the accessibility bridge exposed no window")
}

private fun tabNodes(instrumentation: Instrumentation): List<TabNode> {
    val found = mutableListOf<TabNode>()
    fun visit(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 64) return
        val value = node.text?.toString()?.takeIf { it.isNotEmpty() }
        found.add(TabNode(value, node.contentDescription?.toString(), node.isEditable, node.isClickable))
        for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
    }
    visit(tabWindow(instrumentation), 0)
    return found
}

/** Performs one accessibility action on the single rendered node the predicate selects. */
private fun tabAct(instrumentation: Instrumentation, what: String, select: (AccessibilityNodeInfo) -> Boolean,
                   action: Int, arguments: Bundle? = null): Boolean {
    val matching = mutableListOf<AccessibilityNodeInfo>()
    fun visit(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 64) return
        if (select(node)) matching.add(node)
        for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
    }
    visit(tabWindow(instrumentation), 0)
    check(matching.size == 1) { "expected exactly one $what in the rendered window, found ${matching.size}" }
    return matching.single().performAction(action, arguments)
}

/** Clicks the rendered tab whose label matches, through the clickable control that carries it. */
private fun clickTab(instrumentation: Instrumentation, label: String) {
    val labelled = mutableListOf<AccessibilityNodeInfo>()
    fun visit(node: AccessibilityNodeInfo?, depth: Int) {
        if (node == null || depth > 64) return
        if (node.text?.toString() == label) labelled.add(node)
        for (index in 0 until node.childCount) visit(node.getChild(index), depth + 1)
    }
    visit(tabWindow(instrumentation), 0)
    check(labelled.size == 1) { "expected exactly one node labelled $label in the rendered window, found ${labelled.size}" }
    var target: AccessibilityNodeInfo? = labelled.single()
    var steps = 0
    while (target != null && !target.isClickable && steps < 8) {
        target = target.parent
        steps++
    }
    val control = target ?: error("the tab labelled $label exposes no clickable control")
    check(control.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "the rendered tab refused the click action" }
}

/** Runs the painter over controlled Tabs trees and reports the observed device evidence. */
fun checkTabsRendering(instrumentation: Instrumentation): String {
    val evidence = mutableListOf<String>()
    var activity: Activity? = null
    try {
        TabPreviewActivity.tree = null
        TabPreviewActivity.delivered.clear()
        activity = instrumentation.startActivitySync(Intent(instrumentation.context, TabPreviewActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val live = activity

        fun show(tree: JSONObject) {
            live.runOnUiThread { TabPreviewActivity.tree = tree }
            instrumentation.waitForIdleSync()
        }

        fun await(what: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            var last: Throwable? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                last = null
                if (runCatching(predicate).onFailure { last = it }.getOrDefault(false)) return
                Thread.sleep(50)
            }
            error("Timed out waiting for $what" + (last?.let { " (last error $it)" } ?: ""))
        }

        fun visible(): List<String> {
            val nodes = tabNodes(instrumentation)
            return nodes.mapNotNull { it.text } + nodes.mapNotNull { it.description }
        }

        /** The rendered window as the bridge reports it, for diagnosable check failures. */
        fun rendered(): String = tabNodes(instrumentation).joinToString(" | ") {
            (if (it.editable) "editable" else if (it.clickable) "clickable" else "node") + ":" + it.text + "~" + it.description
        }

        fun field(): String {
            val editable = tabNodes(instrumentation).filter { it.editable }
            check(editable.size == 1) { "the selected panel renders one editable field, not ${editable.size}: ${rendered()}" }
            return editable.single().text ?: ""
        }

        fun editField(value: String) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }
            check(tabAct(instrumentation, "editable field", { it.isEditable }, AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) {
                "the rendered field refused the text action"
            }
        }

        // Only the selected child is composed, and the tab row carries one label per child.
        show(tabsTree(0))
        await("the first panel") { "First panel body" in visible() }
        check("Second panel body" !in visible()) { "only the selected child is composed" }
        check(field() == "a") { "the selected panel's control carries its committed value: ${rendered()}" }
        check("First" in visible() && "Second" in visible()) { "the tab row shows one label per child" }
        evidence.add("selection: only the first panel body rendered, both tab labels visible")

        // A control inside the selected panel delivers through the painter.
        editField("typed")
        await("the panel field dispatch") { (1 to "typed") in TabPreviewActivity.delivered.toList() }
        await("the rendered field to show the typed text") { field() == "typed" }
        check(field() == "typed") { "the rendered field keeps the typed text: ${rendered()}" }
        evidence.add("controls: the selected panel's field delivered its action slot with the typed text")

        // The tab row's own control delivers the chosen index as a number payload.
        clickTab(instrumentation, "Second")
        await("the tab row dispatch") { (0 to "1") in TabPreviewActivity.delivered.toList() }
        evidence.add("tab row: a tab delivered its index as a number payload")

        // Switching renders exactly the new child, which shows its own committed value.
        show(tabsTree(1))
        await("the second panel") { "Second panel body" in visible() && field() == "b" }
        check("First panel body" !in visible()) { "the previous child leaves the composition" }
        check(field() == "b") { "the second panel's field shows its own value, never the other panel's draft: ${rendered()}" }
        evidence.add("switch: the second panel replaced the first and kept its own value")

        editField("other")
        await("the second panel field dispatch") { (2 to "other") in TabPreviewActivity.delivered.toList() }

        // Returning restores the state each keyed child owned, and never the other child's.
        show(tabsTree(0))
        await("the first panel to return with its own text") { "First panel body" in visible() && field() == "typed" }
        check(field() == "typed") { "a returning panel restores its own typed state instead of the committed value: ${rendered()}" }
        show(tabsTree(1))
        await("the second panel to return with its own text") { "Second panel body" in visible() && field() == "other" }
        check(field() == "other") { "each keyed child keeps the state it owns: ${rendered()}" }
        evidence.add("retention: each panel restored its own typed text after switching")

        // Retention follows the child's own identity, not the panel's position: the memo panel moves
        // to the first position and still shows the text it typed in the second one.
        show(tabsTree(0, order = listOf(1, 0)))
        await("the moved panel to keep its own text") { "Second panel body" in visible() && field() == "other" }
        check(field() == "other") { "a panel keeps its state by its own identity, not by its position: ${rendered()}" }
        evidence.add("identity: the memo panel moved to the first position and kept its own typed text")

        // A changed committed value wins over superseded input from the same panel.
        show(tabsTree(0, firstValue = "c"))
        await("the superseded draft to be replaced") { field() == "c" }
        evidence.add("superseded input: a changed committed value replaced the retained draft")

        // A panel's Surface delivers the gestures it owns and ends them when the panel leaves: a
        // surface still holding a gesture is cancelled once per pointer, and an idle one is never
        // cancelled for free.
        fun pointerEvents(from: Int) = TabPreviewActivity.delivered.toList().drop(from)
        fun hasPointer(from: Int, phase: String) = pointerEvents(from).any { it.first == 3 && it.second?.contains("\"$phase\"") == true }
        fun settle() {
            instrumentation.waitForIdleSync()
            Thread.sleep(250)
        }

        show(pointerTree())
        await("the surface panel") { "Pointer" in visible() }
        // The surface starts below the tab row and covers a thousand density units, so the window's
        // centre line, most of the way down, is inside it on any phone or tablet size.
        val window = windowBounds(instrumentation)
        val tapX = window.exactCenterX()
        val tapY = window.top + window.height() * 0.6f
        var mark = TabPreviewActivity.delivered.size
        tapSurface(instrumentation, tapX, tapY)
        await("the surface pointer down") { hasPointer(mark, "down") }
        await("the surface pointer up") { hasPointer(mark, "up") }
        show(emptyTree())
        await("the surface panel to leave the display") { "Pointer" !in visible() }
        settle()
        check(pointerEvents(mark).none { it.first == 3 && it.second?.contains("\"cancel\"") == true }) {
            "a panel that left the display fabricated no cancel for a pointer that was already up: ${pointerEvents(mark)}"
        }
        evidence.add("pointer: the panel's surface delivered the tap it received and fabricated no cancel on leaving")

        // A gesture still down when its surface restarts ends with exactly one cancel that carries the
        // pointer it ends, never a fabricated id and never a silently stranded gesture.
        show(pointerTree())
        await("the surface panel to return") { "Pointer" in visible() }
        val pressed = windowBounds(instrumentation)
        val pressX = pressed.exactCenterX()
        val pressY = pressed.top + pressed.height() * 0.6f
        mark = TabPreviewActivity.delivered.size
        val held = pressSurface(instrumentation, pressX, pressY)
        val cancels: List<Pair<Int, String?>>
        try {
            await("the surface pointer down") { hasPointer(mark, "down") }
            val heldId = JSONObject(pointerEvents(mark).first { it.first == 3 && it.second?.contains("\"down\"") == true }.second!!).getInt("id")
            show(pointerTree(width = 400.0))
            await("the surface to end the gesture it held") { pointerEvents(mark).any { it.first == 3 && it.second?.contains("\"cancel\"") == true } }
            settle()
            cancels = pointerEvents(mark).filter { it.first == 3 && it.second?.contains("\"cancel\"") == true }
            check(cancels.size == 1) { "a gesture still down ends with one cancel when its surface restarts: ${pointerEvents(mark)}" }
            check(JSONObject(cancels.single().second!!).getInt("id") == heldId) {
                "the cancel carries the pointer it ends: ${cancels.single().second}"
            }
        } finally {
            // A failed item never leaves the device holding a gesture for the next run.
            releaseSurface(instrumentation, held, pressX, pressY)
        }
        evidence.add("pointer: a gesture still down when its surface restarted ended with one cancel carrying its id")

        return "tabs: " + evidence.joinToString("; ") +
            " (dispatched " + TabPreviewActivity.delivered.toList().joinToString(",") { "${it.first}:${it.second}" } + ")"
    } finally {
        activity?.finish()
        TabPreviewActivity.tree = null
    }
}
