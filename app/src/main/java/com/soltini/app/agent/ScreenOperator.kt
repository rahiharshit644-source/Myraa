package com.soltini.app.agent

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * ScreenOperator — High-level semantic UI interaction engine for Autonomous Phone Control.
 *
 * Provides intelligent, robust, and semantic phone control primitives:
 * - Smart Element Clicking: matches by text, content description, resource ID, or hint; automatically falls back to parent clickable containers or center-coordinate taps.
 * - Targeted Field Typing: finds the editable input field associated with a label/placeholder, focuses it, types the text via A11y actions, and verifies input.
 * - Directional & Percentage Swiping: scrolls or swipes smoothly across normalized screen percentages or directions (UP, DOWN, LEFT, RIGHT).
 * - Semantic Screen Inspection: returns structured interactive UI elements with IDs, labels, coordinates, and types so the AI operator can make informed decisions.
 */
class ScreenOperator(private val context: Context) {

    companion object {
        private const val TAG = "ScreenOperator"
    }

    private val accessibilityService: SoltiniAccessibilityService?
        get() = SoltiniAccessibilityService.getInstance()

    /**
     * Checks if the accessibility service is ready to perform gestures and inspect screens.
     */
    fun isAvailable(): Boolean = accessibilityService != null && !accessibilityService!!.isPausedLite

    /**
     * Structure describing an interactive UI element on the screen.
     */
    data class UIElement(
        val text: String,
        val description: String,
        val className: String,
        val bounds: Rect,
        val isClickable: Boolean,
        val isEditable: Boolean,
        val isScrollable: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
        val viewId: String?
    ) {
        val centerX: Int get() = bounds.centerX()
        val centerY: Int get() = bounds.centerY()

        fun toJsonObject(): JSONObject = JSONObject().apply {
            put("text", text)
            if (description.isNotBlank()) put("description", description)
            put("class", className.substringAfterLast('.'))
            put("centerX", centerX)
            put("centerY", centerY)
            put("bounds", "[${bounds.left},${bounds.top} to ${bounds.right},${bounds.bottom}]")
            put("clickable", isClickable)
            put("editable", isEditable)
            if (isCheckable) put("checked", isChecked)
            if (!viewId.isNullOrBlank()) put("id", viewId.substringAfterLast('/'))
        }
    }

    /**
     * Inspects the current screen and returns structured interactive elements
     * along with full visible text content.
     */
    fun inspectScreen(): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active. Enable it in Settings.")
        val elements = mutableListOf<UIElement>()
        val allTexts = mutableListOf<String>()

        try {
            svc.withRootNodes { roots ->
                for (root in roots) {
                    traverseNode(root, elements, allTexts)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error inspecting screen: ${e.message}", e)
            return errorResult("Failed to inspect screen: ${e.message}")
        }

        val interactiveArray = JSONArray()
        elements.take(40).forEach { interactiveArray.put(it.toJsonObject()) }

        return JSONObject().apply {
            put("status", "success")
            put("interactive_count", elements.size)
            put("interactive_elements", interactiveArray)
            put("visible_text_summary", allTexts.distinct().take(25).joinToString(" • "))
        }
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        elements: MutableList<UIElement>,
        allTexts: MutableList<String>
    ) {
        if (node.isPassword) return

        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName
        val className = node.className?.toString() ?: ""

        val label = if (text.isNotBlank()) text else desc
        if (label.isNotBlank()) {
            allTexts.add(label)
        }

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val isMeaningful = (node.isClickable || node.isEditable || node.isCheckable || node.isScrollable) &&
                bounds.width() > 0 && bounds.height() > 0

        if (isMeaningful) {
            elements.add(
                UIElement(
                    text = text,
                    description = desc,
                    className = className,
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked,
                    viewId = viewId
                )
            )
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, elements, allTexts)
            child.recycle()
        }
    }

    /**
     * Clicks an element by matching its text, label, description, or view ID.
     * Features:
     * - Intelligent Hunting (Auto-Scroll): If element is offscreen, automatically scrolls down to find it.
     * - Self-Correction: Tries ACTION_CLICK, falls back to clickable parent, and finally physical center coordinate tap.
     */
    fun clickElement(target: String, autoScroll: Boolean = true, maxScrolls: Int = 3): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active")
        if (target.isBlank()) return errorResult("target query is required")

        // 1. First attempt on currently visible screen
        val firstAttempt = findAndClickDirect(target)
        if (firstAttempt != null) return firstAttempt

        // 2. If not found and autoScroll is enabled, intelligently hunt downwards
        if (autoScroll) {
            for (scrollCount in 1..maxScrolls) {
                Log.d(TAG, "Target '$target' not on screen, hunting down ($scrollCount/$maxScrolls)...")
                swipeDirection("UP", 0.45f)
                safeSleep(400)

                val scrolledAttempt = findAndClickDirect(target)
                if (scrolledAttempt != null) {
                    scrolledAttempt.put("auto_scrolled", true)
                    scrolledAttempt.put("scroll_count", scrollCount)
                    return scrolledAttempt
                }
            }

            // 3. Optional: Try scrolling up once if user started from the bottom
            Log.d(TAG, "Target '$target' not found scrolling down, hunting upward...")
            swipeDirection("DOWN", 0.5f)
            safeSleep(400)
            val upAttempt = findAndClickDirect(target)
            if (upAttempt != null) {
                upAttempt.put("auto_scrolled_up", true)
                return upAttempt
            }
        }

        return errorResult("Could not find element matching '$target' on screen (scanned visible nodes and auto-scrolled $maxScrolls times).")
    }

    private fun findAndClickDirect(target: String): JSONObject? {
        val svc = accessibilityService ?: return null
        val query = target.lowercase().trim()

        return svc.withRootNodes { roots ->
            for (root in roots) {
                // Ordinal support: "first link", "pehla link", "second link", "doosra link"
                val isFirst = query.contains("first") || query.contains("1st") || query.contains("pehla") || query.contains("top")
                val isSecond = query.contains("second") || query.contains("2nd") || query.contains("doosra")

                val matchedNode = if (isFirst || isSecond) {
                    val clickableList = mutableListOf<AccessibilityNodeInfo>()
                    collectClickableNodes(root, clickableList)
                    val chosen = if (isSecond && clickableList.size > 1) clickableList[1] else clickableList.firstOrNull()
                    clickableList.forEach { if (it != chosen) it.recycle() }
                    chosen
                } else {
                    findMatchingNode(root, query)
                } ?: continue

                val bounds = Rect()
                matchedNode.getBoundsInScreen(bounds)

                // 1. Try standard Accessibility action
                var clicked = matchedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                // 2. If node was not clickable directly, try clickable parent
                if (!clicked) {
                    var parent = matchedNode.parent
                    while (parent != null && !clicked) {
                        if (parent.isClickable) {
                            clicked = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        }
                        val next = parent.parent
                        parent.recycle()
                        parent = next
                    }
                }

                // 3. Physical coordinate tap at center (Self-Correction for WebViews/Canvas/Compose touch)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    val cx = bounds.centerX().toFloat()
                    val cy = bounds.centerY().toFloat()
                    if (!clicked) {
                        Log.i(TAG, "Standard click failed for '$target', self-correcting via physical tap at ($cx, $cy)")
                        clicked = svc.tapAt(cx, cy)
                    }
                }

                matchedNode.recycle()

                if (clicked) {
                    return@withRootNodes JSONObject().apply {
                        put("status", "success")
                        put("action", "clicked")
                        put("target", target)
                        put("center_x", bounds.centerX())
                        put("center_y", bounds.centerY())
                    }
                }
            }
            null
        }
    }

    private fun collectClickableNodes(node: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable && (node.text?.isNotBlank() == true || node.contentDescription?.isNotBlank() == true)) {
            list.add(AccessibilityNodeInfo.obtain(node))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableNodes(child, list)
            child.recycle()
        }
    }

    /**
     * Types text into an editable field identified by a label, placeholder, or hint.
     * Features:
     * - Auto-Scroll: if targetField is not visible on screen, scrolls down to locate it.
     * - Auto-Focus: if search bar / input is not yet active (e.g. Chrome url_bar), physically taps it first to open keyboard.
     * - Self-Correction: verifies text entered; if standard A11y input failed, taps physical center and uses clipboard paste.
     * - Optional submit: triggers Enter / Search IME action immediately after typing.
     */
    fun typeIntoField(
        targetField: String?,
        textToType: String,
        clearFirst: Boolean = false,
        autoScroll: Boolean = true,
        autoDismissKeyboard: Boolean = false,
        pressEnterAfter: Boolean = false
    ): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active")

        // 1. First attempt to find and type on visible viewport
        var result = tryTypeIntoFieldDirect(targetField, textToType, clearFirst, pressEnterAfter)

        // 2. If targetField specified and not found, auto-scroll to find it
        if (result == null && !targetField.isNullOrBlank() && autoScroll) {
            for (scrollCount in 1..2) {
                Log.d(TAG, "Field '$targetField' not visible, auto-scrolling down ($scrollCount/2)...")
                swipeDirection("UP", 0.45f)
                safeSleep(400)

                result = tryTypeIntoFieldDirect(targetField, textToType, clearFirst, pressEnterAfter)
                if (result != null) {
                    result.put("auto_scrolled", true)
                    break
                }
            }
        }

        if (autoDismissKeyboard && !pressEnterAfter) {
            safeSleep(150)
            svc.hideSoftKeyboard()
        }

        return result ?: errorResult("No editable input field matching '${targetField ?: "any"}' found on screen to type into.")
    }

    private fun tryTypeIntoFieldDirect(
        targetField: String?,
        textToType: String,
        clearFirst: Boolean,
        pressEnterAfter: Boolean = false
    ): JSONObject? {
        val svc = accessibilityService ?: return null

        return svc.withRootNodes { roots ->
            var editableNode: AccessibilityNodeInfo? = null

            // 1. If targetField specified, find matching field
            if (!targetField.isNullOrBlank()) {
                val query = targetField.lowercase().trim()
                for (root in roots) {
                    editableNode = findEditableByLabel(root, query)
                    if (editableNode != null) break
                }

                // If not found as an active editable field, check if it's a search bar / address bar / input container
                if (editableNode == null) {
                    for (root in roots) {
                        val matchingNode = findMatchingNode(root, query)
                        if (matchingNode != null) {
                            val bounds = Rect()
                            matchingNode.getBoundsInScreen(bounds)
                            matchingNode.recycle()
                            if (bounds.width() > 0 && bounds.height() > 0) {
                                Log.i(TAG, "Field '$targetField' found as inactive container, tapping at (${bounds.centerX()}, ${bounds.centerY()}) to open cursor/keyboard...")
                                svc.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                                safeSleep(350)
                                break
                            }
                        }
                    }
                }
            }

            // 2. Check currently focused input node across active windows
            if (editableNode == null) {
                for (root in roots) {
                    val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    if (focused != null) {
                        editableNode = focused
                        break
                    }
                    val a11yFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
                    if (a11yFocused != null && (a11yFocused.isEditable || a11yFocused.className?.toString()?.contains("EditText", ignoreCase = true) == true)) {
                        editableNode = a11yFocused
                        break
                    }
                }
            }

            // 3. Fallback to first editable on screen
            if (editableNode == null) {
                for (root in roots) {
                    editableNode = findFirstEditable(root)
                    if (editableNode != null) break
                }
            }

            // 4. If still no editableNode found, try typing directly using typeText (which pastes into active cursor/IME)
            if (editableNode == null) {
                Log.i(TAG, "No editableNode in tree, attempting global typeText for '$textToType'...")
                val typedGlobal = svc.typeText(textToType)
                if (typedGlobal) {
                    if (pressEnterAfter) {
                        safeSleep(200)
                        svc.pressImeEnter()
                    }
                    return@withRootNodes JSONObject().apply {
                        put("status", "success")
                        put("action", "typed")
                        put("text", textToType)
                        if (!targetField.isNullOrBlank()) put("field", targetField)
                        if (pressEnterAfter) put("submitted", true)
                    }
                }
                return@withRootNodes null
            }

            try {
                val bounds = Rect()
                editableNode.getBoundsInScreen(bounds)

                // Focus & ensure input session is active
                editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                editableNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)

                val textToSet = if (clearFirst) textToType else {
                    val current = editableNode.text?.toString() ?: ""
                    if (current.isBlank()) textToType else "$current $textToType"
                }

                // Use robust setTextOrPaste (which handles WebViews, Chrome & native fields)
                var success = svc.setTextOrPaste(editableNode, textToSet)

                // Self-Correction: If direct action failed, physically tap the field center and paste via clipboard/service
                if (!success && bounds.width() > 0 && bounds.height() > 0) {
                    Log.i(TAG, "Direct setText failed for '$targetField', self-correcting via physical tap + typeText")
                    svc.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    safeSleep(200)
                    success = svc.typeText(textToSet)
                }

                if (success && pressEnterAfter) {
                    safeSleep(200)
                    svc.pressImeEnter(editableNode)
                }

                editableNode.recycle()

                if (success) {
                    JSONObject().apply {
                        put("status", "success")
                        put("action", "typed")
                        put("text", textToType)
                        if (!targetField.isNullOrBlank()) put("field", targetField)
                        if (pressEnterAfter) put("submitted", true)
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                editableNode.recycle()
                null
            }
        }
    }

    /**
     * Submits the current search or form by pressing the IME Enter / Search key.
     */
    fun pressEnter(): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active")
        val success = svc.pressImeEnter()
        return if (success) {
            JSONObject().apply {
                put("status", "success")
                put("action", "enter_pressed")
            }
        } else {
            errorResult("Could not press enter on keyboard")
        }
    }

    /**
     * Toggles a checkbox, switch, or radio button by matching its label or description.
     */
    fun toggleCheckable(target: String, desiredState: Boolean? = null): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active")
        if (target.isBlank()) return errorResult("target label is required")

        val query = target.lowercase().trim()

        val toggledResult = svc.withRootNodes { roots ->
            for (root in roots) {
                val node = findCheckableByLabel(root, query) ?: continue
                val currentState = node.isChecked
                var clicked = false

                if (desiredState == null || currentState != desiredState) {
                    clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    if (!clicked) {
                        val bounds = Rect()
                        node.getBoundsInScreen(bounds)
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            clicked = svc.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        }
                    }
                } else {
                    // Already in desired state
                    clicked = true
                }

                node.recycle()

                if (clicked) {
                    return@withRootNodes JSONObject().apply {
                        put("status", "success")
                        put("action", "toggled")
                        put("target", target)
                        put("checked", desiredState ?: !currentState)
                    }
                }
            }
            null
        }

        return toggledResult ?: errorResult("Could not find checkbox or switch matching '$target'.")
    }

    /**
     * Selects an option from a dropdown or spinner menu.
     * Clicks the dropdown container, waits for the popup/dialog list, and clicks the matching option.
     */
    fun selectDropdownOption(dropdownLabel: String, optionText: String): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active")
        if (optionText.isBlank()) return errorResult("optionText is required")

        // 1. If dropdownLabel is provided, click the dropdown to open options
        if (dropdownLabel.isNotBlank()) {
            val openResult = clickElement(dropdownLabel, autoScroll = true)
            if (openResult.optString("status") != "success") {
                Log.w(TAG, "Could not click dropdown label '$dropdownLabel', will attempt direct option click")
            }
            safeSleep(450) // Wait for popup/dialog options to render
        }

        // 2. Click the option text inside the opened popup/dropdown
        val selectResult = clickElement(optionText, autoScroll = true)
        return if (selectResult.optString("status") == "success") {
            JSONObject().apply {
                put("status", "success")
                put("action", "dropdown_selected")
                put("dropdown", dropdownLabel)
                put("selected_option", optionText)
            }
        } else {
            errorResult("Failed to select option '$optionText' from dropdown '$dropdownLabel'.")
        }
    }

    /**
     * Multi-step Form Automation:
     * Fills multiple fields across an app form in one seamless flow:
     * - Supports text, number, email, dropdowns, switches, and checkboxes.
     * - Auto-scrolls if fields are further down the page.
     * - Auto-dismisses the soft keyboard after completion.
     * - Optionally clicks the submit button.
     */
    fun fillForm(
        fields: JSONArray,
        autoSubmit: Boolean = false,
        submitButton: String? = null
    ): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active")
        if (fields.length() == 0) return errorResult("fields array is empty")

        val results = JSONArray()
        var successCount = 0

        for (i in 0 until fields.length()) {
            val item = fields.optJSONObject(i) ?: continue
            val label = item.optString("label", item.optString("field", ""))
            val value = item.optString("value", "")
            val type = item.optString("type", "text").lowercase().trim()

            Log.i(TAG, "Filling form field: label='$label', value='$value', type='$type'")

            val fieldResult: JSONObject = when (type) {
                "checkbox", "switch" -> {
                    val desired = if (value.equals("false", ignoreCase = true) || value == "0") false else true
                    toggleCheckable(label, desired)
                }
                "dropdown", "select", "spinner" -> {
                    selectDropdownOption(dropdownLabel = label, optionText = value)
                }
                else -> {
                    // Text, email, phone, number, etc.
                    typeIntoField(
                        targetField = label.takeIf { it.isNotBlank() },
                        textToType = value,
                        clearFirst = true,
                        autoScroll = true,
                        autoDismissKeyboard = false
                    )
                }
            }

            val isSuccess = fieldResult.optString("status") == "success"
            if (isSuccess) successCount++

            results.put(JSONObject().apply {
                put("label", label)
                put("value", value)
                put("type", type)
                put("status", if (isSuccess) "success" else "failed")
                if (!isSuccess) put("error", fieldResult.optString("error", "Unknown field error"))
            })

            safeSleep(250) // Brief natural pacing between fields
        }

        // Auto-dismiss keyboard when form filling is complete so submit button or next items are visible
        svc.hideSoftKeyboard()
        safeSleep(300)

        var submitted = false
        var submitResultMsg: String? = null

        if (autoSubmit || !submitButton.isNullOrBlank()) {
            val buttonToClick = submitButton?.takeIf { it.isNotBlank() } ?: "Submit"
            Log.i(TAG, "Attempting auto-submit with button: '$buttonToClick'")
            val submitRes = clickElement(buttonToClick, autoScroll = true)
            submitted = submitRes.optString("status") == "success"
            submitResultMsg = if (submitted) "Form submitted via '$buttonToClick'" else "Could not find or click submit button '$buttonToClick'"
        }

        return JSONObject().apply {
            put("status", if (successCount > 0) "success" else "failed")
            put("action", "form_filled")
            put("total_fields", fields.length())
            put("successful_fields", successCount)
            put("field_results", results)
            put("auto_submitted", submitted)
            if (submitResultMsg != null) put("submit_message", submitResultMsg)
        }
    }

    /**
     * Explicitly dismisses the onscreen soft keyboard if visible.
     */
    fun hideKeyboard(): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active")
        val dismissed = svc.hideSoftKeyboard()
        return JSONObject().apply {
            put("status", "success")
            put("keyboard_dismissed", dismissed)
        }
    }

    /**
     * Smooth scroll or directional swipe across the display.
     * Direction: "UP", "DOWN", "LEFT", "RIGHT".
     */
    fun swipeDirection(direction: String, distancePercent: Float = 0.5f): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active")
        val metrics = getDisplayMetrics()
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()

        val clampedDist = distancePercent.coerceIn(0.1f, 0.9f)
        val cx = width / 2f
        val cy = height / 2f

        val (x1, y1, x2, y2) = when (direction.uppercase().trim()) {
            "UP", "SCROLL_DOWN" -> {
                // Swiping finger UP scrolls content DOWN
                val delta = height * clampedDist / 2f
                listOf(cx, cy + delta, cx, cy - delta)
            }
            "DOWN", "SCROLL_UP" -> {
                // Swiping finger DOWN scrolls content UP
                val delta = height * clampedDist / 2f
                listOf(cx, cy - delta, cx, cy + delta)
            }
            "LEFT", "NEXT" -> {
                // Swiping finger LEFT moves to NEXT page/tab
                val delta = width * clampedDist / 2f
                listOf(cx + delta, cy, cx - delta, cy)
            }
            "RIGHT", "PREVIOUS" -> {
                // Swiping finger RIGHT moves to PREVIOUS page/tab
                val delta = width * clampedDist / 2f
                listOf(cx - delta, cy, cx + delta, cy)
            }
            else -> return errorResult("Invalid direction: $direction. Use UP, DOWN, LEFT, or RIGHT.")
        }

        val success = svc.swipe(x1, y1, x2, y2, durationMs = 350)
        return if (success) {
            JSONObject().apply {
                put("status", "success")
                put("action", "swiped")
                put("direction", direction.uppercase())
                put("from", "($x1, $y1)")
                put("to", "($x2, $y2)")
            }
        } else {
            errorResult("Failed to dispatch swipe gesture.")
        }
    }

    /**
     * Taps at normalized screen percentage coordinates (e.g. 0.5, 0.5 for center of screen).
     */
    fun tapPercent(percentX: Float, percentY: Float): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service is not active")
        val metrics = getDisplayMetrics()
        val px = (percentX.coerceIn(0f, 1f) * metrics.widthPixels)
        val py = (percentY.coerceIn(0f, 1f) * metrics.heightPixels)

        val success = svc.tapAt(px, py)
        return if (success) {
            JSONObject().apply {
                put("status", "success")
                put("action", "tapped")
                put("x", px.toInt())
                put("y", py.toInt())
            }
        } else {
            errorResult("Failed to dispatch tap gesture.")
        }
    }

    private fun getDisplayMetrics(): DisplayMetrics {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics
    }

    private fun findMatchingNode(node: AccessibilityNodeInfo, rawQuery: String): AccessibilityNodeInfo? {
        val query = rawQuery.lowercase().trim()
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""
        val hint = node.hintText?.toString()?.lowercase() ?: ""

        // 1. Direct contains check
        if (text.contains(query) || desc.contains(query) || viewId.contains(query) || hint.contains(query)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        // 2. Query words stripping (e.g. "search button" -> "search", "search bar" -> "search", "submit button" -> "submit")
        val cleanQuery = query
            .replace("button", "")
            .replace("btn", "")
            .replace("bar", "")
            .replace("icon", "")
            .replace("link", "")
            .replace("box", "")
            .replace("pe", "")
            .replace("par", "")
            .replace("ko", "")
            .replace("karo", "")
            .replace("click", "")
            .replace("tap", "")
            .replace("dabao", "")
            .trim()

        if (cleanQuery.isNotEmpty() && cleanQuery.length >= 2) {
            if (text.contains(cleanQuery) || desc.contains(cleanQuery) || viewId.contains(cleanQuery) || hint.contains(cleanQuery)) {
                return AccessibilityNodeInfo.obtain(node)
            }
            if ((text.isNotEmpty() && cleanQuery.contains(text) && text.length >= 3) ||
                (desc.isNotEmpty() && cleanQuery.contains(desc) && desc.length >= 3)) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        // 3. Search specific detection (e.g. "search", "url", "google", "chrome")
        val isSearch = query.contains("search") || query.contains("url") || query.contains("dhoond") || query.contains("find")
        if (isSearch) {
            if (viewId.contains("search") || viewId.contains("url_bar") || viewId.contains("query") ||
                desc.contains("search") || text.contains("search") || hint.contains("search") ||
                desc.contains("type url") || text.contains("type url")) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findMatchingNode(child, rawQuery)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findEditableByLabel(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val hint = node.hintText?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val isEdit = node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true
        val cleanQuery = query.replace("bar", "").replace("box", "").replace("field", "").replace("input", "").trim()

        if (isEdit) {
            if (text.contains(query) || hint.contains(query) || desc.contains(query) || viewId.contains(query) ||
                (cleanQuery.length >= 2 && (text.contains(cleanQuery) || hint.contains(cleanQuery) || desc.contains(cleanQuery) || viewId.contains(cleanQuery)))) {
                return AccessibilityNodeInfo.obtain(node)
            }
        }

        // Search bar query heuristic
        val isSearch = query.contains("search") || query.contains("url") || query.contains("dhoond")
        if (isEdit && isSearch && (viewId.contains("search") || viewId.contains("url_bar") || hint.contains("search") || desc.contains("search"))) {
            return AccessibilityNodeInfo.obtain(node)
        }

        // Sometimes the label is a sibling or parent label of the editable node
        if (text.contains(query) || desc.contains(query) || (cleanQuery.length >= 2 && (text.contains(cleanQuery) || desc.contains(cleanQuery)))) {
            val parent = node.parent
            if (parent != null) {
                val editableInParent = findFirstEditable(parent)
                parent.recycle()
                if (editableInParent != null) return editableInParent
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditableByLabel(child, query)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstEditable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findCheckableByLabel(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""

        if (node.isCheckable && (text.contains(query) || desc.contains(query))) {
            return AccessibilityNodeInfo.obtain(node)
        }

        // If label is on a sibling or parent container of the checkable
        if (text.contains(query) || desc.contains(query)) {
            val parent = node.parent
            if (parent != null) {
                val checkableInParent = findFirstCheckable(parent)
                parent.recycle()
                if (checkableInParent != null) return checkableInParent
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findCheckableByLabel(child, query)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findFirstCheckable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isCheckable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstCheckable(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findDropdownByLabel(node: AccessibilityNodeInfo, query: String): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val cls = node.className?.toString()?.lowercase() ?: ""

        val isDropdownType = cls.contains("spinner") || cls.contains("dropdown") || desc.contains("dropdown") || desc.contains("select")

        if (isDropdownType && (text.contains(query) || desc.contains(query))) {
            return AccessibilityNodeInfo.obtain(node)
        }

        if (text.contains(query) || desc.contains(query)) {
            val parent = node.parent
            if (parent != null) {
                val dropInParent = findFirstDropdown(parent)
                parent.recycle()
                if (dropInParent != null) return dropInParent
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findDropdownByLabel(child, query)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findFirstDropdown(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val cls = node.className?.toString()?.lowercase() ?: ""
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        if (cls.contains("spinner") || cls.contains("dropdown") || desc.contains("dropdown") || desc.contains("select")) {
            return AccessibilityNodeInfo.obtain(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findFirstDropdown(child)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun safeSleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun errorResult(msg: String): JSONObject = JSONObject().apply {
        put("status", "error")
        put("error", msg)
    }
}
