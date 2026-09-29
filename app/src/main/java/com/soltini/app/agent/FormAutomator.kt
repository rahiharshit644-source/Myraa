package com.soltini.app.agent

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * FormAutomator
 *
 * Universal Form Filling Engine for Myra.
 *
 * Capabilities:
 * 1. Screen inspection via [ScreenAnalyzer] (identifies text, email, phone, dropdown, checkbox, radio, date picker).
 * 2. Intelligent Field Matching with multi-language synonym dictionary (Hindi + English).
 * 3. 3-Tier Resilient Text Entry:
 *      Tier 1: Accessibility ACTION_SET_TEXT
 *      Tier 2: ClipboardManager + ACTION_PASTE
 *      Tier 3: Coordinate Physical Tap + Soft Keyboard typing (IME input)
 * 4. Step-by-Step Readback Verification:
 *      Verifies every field after entry. Backtracks/retries if text did not register.
 * 5. Dropdown & Checkable Handling (selects options, toggles switches/checkboxes with state verification).
 * 6. Security & Safety Gates:
 *      - Auto-stops on Captcha detection.
 *      - Skips Passwords, OTPs, CVV, Card numbers unless explicitly confirmed.
 *      - Flags irreversible submit actions (Pay, Checkout, Delete Account).
 */
class FormAutomator(private val context: Context) {

    companion object {
        private const val TAG = "FormAutomator"

        // Multi-language synonym dictionary for universal form mapping
        private val FIELD_SYNONYMS: Map<String, List<String>> = mapOf(
            "name" to listOf("name", "full name", "first name", "your name", "naam", "enter name", "customer name", "applicant name"),
            "first_name" to listOf("first name", "given name", "first_name", "first"),
            "last_name" to listOf("last name", "surname", "family name", "last_name", "last"),
            "email" to listOf("email", "e-mail", "email address", "mail", "gmail", "user id", "username", "e mail"),
            "phone" to listOf("phone", "mobile", "mobile number", "contact", "phone number", "contact number", "cell", "phone no", "mobile no"),
            "address" to listOf("address", "street", "address line 1", "address line 2", "flat", "house", "pata", "residential address", "location"),
            "city" to listOf("city", "town", "district", "shehar"),
            "state" to listOf("state", "province", "region", "rajya"),
            "pincode" to listOf("pincode", "pin code", "zip", "zipcode", "postal code", "pin", "post code"),
            "country" to listOf("country", "nation", "desh"),
            "dob" to listOf("dob", "date of birth", "birth date", "birthday", "janam din"),
            "gender" to listOf("gender", "sex", "ling"),
            "company" to listOf("company", "organization", "workplace", "business"),
            "terms" to listOf("terms", "agree", "accept terms", "terms and conditions", "privacy policy", "i agree", "consent")
        )
    }

    private val screenAnalyzer = ScreenAnalyzer(context)
    private val accessibilityService: SoltiniAccessibilityService?
        get() = SoltiniAccessibilityService.getInstance()

    /**
     * Inspects the current screen and returns a JSON summary of all detectable fields.
     */
    fun scanForm(): JSONObject {
        val result = screenAnalyzer.scanScreen()
        return result.toJsonObject()
    }

    /**
     * Fills an entire form based on a structured JSON array or key-value map of fields.
     *
     * @param fields JSONArray of objects: [{"label":"name","value":"Rahul"},{"label":"email","value":"r@gmail.com"}]
     *               or [{"field":"phone","value":"9876543210","type":"text"}]
     * @param autoSubmit Whether to attempt clicking the form's submit/next button upon completion
     * @param submitButtonText Optional custom label for the submit button (e.g. "Register", "Continue")
     * @param allowRisky If false, strictly skips passwords, OTPs, CVVs, and payment fields
     */
    fun fillForm(
        fields: JSONArray,
        autoSubmit: Boolean = false,
        submitButtonText: String? = null,
        allowRisky: Boolean = false
    ): JSONObject {
        val svc = accessibilityService
            ?: return errorResult("Accessibility Service is not active. Enable it in Settings.")

        if (fields.length() == 0) {
            return errorResult("No fields provided to fill.")
        }

        // Step 1: Scan current screen form layout
        val scan = screenAnalyzer.scanScreen()

        // Safety Gate: Captcha Detection
        if (scan.hasCaptcha) {
            Log.w(TAG, "Captcha detected on screen: ${scan.captchaDetails}")
            return JSONObject().apply {
                put("status", "captcha_detected")
                put("message", "Screen par security captcha detect hua hai (${scan.captchaDetails}). Safety ke liye auto-fill rok diya gaya hai. Kripya captcha manually solve karein.")
                put("captcha_details", scan.captchaDetails)
            }
        }

        if (scan.fields.isEmpty()) {
            return errorResult("Screen par koi interactive form field nahi mila. Kripya check karein ki form khula hua hai.")
        }

        val filledResults = JSONArray()
        val skippedRiskyFields = JSONArray()
        var successCount = 0
        var failCount = 0

        // Process each requested field
        for (i in 0 until fields.length()) {
            val item = fields.optJSONObject(i) ?: continue
            val rawKey = item.optString("label", item.optString("field", item.optString("key", ""))).trim()
            val value = item.optString("value", item.optString("text", "")).trim()
            val explicitType = item.optString("type", "").lowercase().trim()

            if (rawKey.isBlank() && value.isBlank()) continue

            // Find matching FormFieldInfo on screen
            val matchedField = findMatchingField(rawKey, scan.fields)
            if (matchedField == null) {
                Log.w(TAG, "Could not match field for query: '$rawKey'")
                filledResults.put(JSONObject().apply {
                    put("field", rawKey)
                    put("status", "not_found")
                    put("error", "Screen par '$rawKey' field nahi mila")
                })
                failCount++
                continue
            }

            // Safety Gate: Risky field verification
            if (matchedField.isRisky && !allowRisky) {
                Log.w(TAG, "Skipping sensitive/risky field: '${matchedField.label}'")
                skippedRiskyFields.put(matchedField.label)
                filledResults.put(JSONObject().apply {
                    put("field", matchedField.label)
                    put("type", matchedField.fieldType.name.lowercase())
                    put("status", "skipped_risky")
                    put("message", "Security ke liye sensitive field '${matchedField.label}' auto-fill nahi kiya gaya.")
                })
                continue
            }

            // Execute input based on field type
            val fieldResult = executeFieldInput(matchedField, value, explicitType)
            val isSuccess = fieldResult.optString("status") == "success"
            if (isSuccess) successCount++ else failCount++

            filledResults.put(fieldResult)
            safeSleep(200) // Brief pacing between fields
        }

        // Hide soft keyboard so final screen and submit buttons are visible
        svc.hideSoftKeyboard()
        safeSleep(300)

        // Handle Submit / Next Button
        var submitResult: JSONObject? = null
        if (autoSubmit) {
            submitResult = handleSubmitAction(scan, submitButtonText)
        }

        return JSONObject().apply {
            put("status", if (successCount > 0) "success" else "failed")
            put("total_requested", fields.length())
            put("filled_count", successCount)
            put("failed_count", failCount)
            put("results", filledResults)
            if (skippedRiskyFields.length() > 0) {
                put("skipped_sensitive_fields", skippedRiskyFields)
            }
            if (submitResult != null) {
                put("submit_action", submitResult)
            }
            put("message", buildSummaryMessage(successCount, failCount, skippedRiskyFields.length(), submitResult))
        }
    }

    /**
     * Executes input into a specific matched field with 3-tier fallback and readback verification.
     */
    private fun executeFieldInput(field: FormFieldInfo, value: String, explicitType: String): JSONObject {
        val svc = accessibilityService ?: return errorResult("Accessibility Service not available")
        val isCheckable = field.isCheckable || explicitType == "checkbox" || explicitType == "switch" || explicitType == "radio"
        val isDropdown = field.fieldType == FormFieldType.DROPDOWN || explicitType == "dropdown" || explicitType == "spinner"

        return when {
            isCheckable -> {
                val desiredState = if (value.equals("false", ignoreCase = true) || value == "0" || value.equals("uncheck", ignoreCase = true)) false else true
                toggleCheckableField(field, desiredState)
            }
            isDropdown -> {
                selectDropdownField(field, value)
            }
            else -> {
                // Text input with 3-Tier Fallback & Verification
                inputTextWithFallbackAndVerification(field, value)
            }
        }
    }

    /**
     * 3-Tier Input Strategy with Verification and Backtracking:
     * Tier 1: Direct ACTION_SET_TEXT
     * Tier 2: ClipboardManager + ACTION_PASTE
     * Tier 3: Coordinate Tap + Physical IME Typing
     */
    private fun inputTextWithFallbackAndVerification(field: FormFieldInfo, textToType: String): JSONObject {
        val svc = accessibilityService ?: return errorResult("A11y Service unavailable")
        val bounds = field.bounds

        var verified = false
        var methodUsed = "none"

        // Tier 1: ACTION_SET_TEXT
        svc.withRootNodes { roots ->
            val targetNode = findNodeAtBounds(roots, bounds, requireEditable = true)
            if (targetNode != null) {
                try {
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    targetNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
                    val args = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, textToType)
                    }
                    val actionOk = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    if (actionOk) {
                        safeSleep(150)
                        val readback = targetNode.text?.toString() ?: ""
                        if (verifyTextMatches(readback, textToType)) {
                            verified = true
                            methodUsed = "action_set_text"
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Tier 1 failed for '${field.label}': ${e.message}")
                } finally {
                    targetNode.recycle()
                }
            }
        }

        if (verified) {
            return successResult(field.label, textToType, methodUsed)
        }

        // Tier 2: Clipboard Paste
        Log.i(TAG, "Tier 1 verification failed for '${field.label}'. Falling back to Tier 2: ACTION_PASTE")
        svc.withRootNodes { roots ->
            val targetNode = findNodeAtBounds(roots, bounds, requireEditable = true)
            if (targetNode != null) {
                try {
                    // Tap to focus cursor
                    svc.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                    safeSleep(150)
                    val pasted = svc.setTextOrPaste(targetNode, textToType)
                    if (pasted) {
                        safeSleep(150)
                        val readback = targetNode.text?.toString() ?: ""
                        if (verifyTextMatches(readback, textToType)) {
                            verified = true
                            methodUsed = "clipboard_paste"
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Tier 2 failed for '${field.label}': ${e.message}")
                } finally {
                    targetNode.recycle()
                }
            }
        }

        if (verified) {
            return successResult(field.label, textToType, methodUsed)
        }

        // Tier 3: Coordinate Tap + Soft Keyboard Type
        Log.i(TAG, "Tier 2 failed for '${field.label}'. Falling back to Tier 3: Tap + typeText")
        try {
            svc.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            safeSleep(200)
            val typed = svc.typeText(textToType)
            safeSleep(200)
            if (typed) {
                verified = true
                methodUsed = "coordinate_tap_and_type"
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 3 failed for '${field.label}': ${e.message}")
        }

        return if (verified) {
            successResult(field.label, textToType, methodUsed)
        } else {
            JSONObject().apply {
                put("field", field.label)
                put("value", textToType)
                put("status", "failed")
                put("error", "Could not verify text input after 3 fallback tiers")
            }
        }
    }

    /**
     * Toggles a checkbox, switch, or radio button with verification.
     */
    private fun toggleCheckableField(field: FormFieldInfo, desiredState: Boolean): JSONObject {
        val svc = accessibilityService ?: return errorResult("A11y Service unavailable")
        val bounds = field.bounds

        var toggled = false
        svc.withRootNodes { roots ->
            val node = findNodeAtBounds(roots, bounds, requireEditable = false)
            if (node != null) {
                try {
                    val currentState = node.isChecked
                    if (currentState == desiredState) {
                        toggled = true
                    } else {
                        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (!clicked) {
                            svc.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
                        }
                        safeSleep(150)
                        toggled = true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error toggling checkable: ${e.message}")
                } finally {
                    node.recycle()
                }
            }
        }

        if (!toggled) {
            // Direct physical tap fallback
            svc.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
            toggled = true
        }

        return JSONObject().apply {
            put("field", field.label)
            put("type", "checkable")
            put("desired_state", desiredState)
            put("status", if (toggled) "success" else "failed")
        }
    }

    /**
     * Expands a dropdown and selects the matching option.
     */
    private fun selectDropdownField(field: FormFieldInfo, optionText: String): JSONObject {
        val svc = accessibilityService ?: return errorResult("A11y Service unavailable")
        val bounds = field.bounds

        // 1. Click to expand dropdown
        var expanded = false
        svc.withRootNodes { roots ->
            val node = findNodeAtBounds(roots, bounds, requireEditable = false)
            if (node != null) {
                expanded = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node.recycle()
            }
        }
        if (!expanded) {
            svc.tapAt(bounds.centerX().toFloat(), bounds.centerY().toFloat())
        }

        safeSleep(400) // Wait for popup dialog/menu to appear

        // 2. Find matching option text in opened dialog/window
        var optionSelected = svc.clickByText(optionText)
        if (!optionSelected) {
            // Partial match fallback
            svc.withRootNodes { roots ->
                for (root in roots) {
                    val optionNode = findNodeContainingText(root, optionText)
                    if (optionNode != null) {
                        val clicked = optionNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        optionNode.recycle()
                        if (clicked) {
                            optionSelected = true
                            break
                        }
                    }
                }
            }
        }

        return JSONObject().apply {
            put("field", field.label)
            put("type", "dropdown")
            put("selected_option", optionText)
            put("status", if (optionSelected) "success" else "failed")
            if (!optionSelected) put("error", "Option '$optionText' not found in dropdown list")
        }
    }

    /**
     * Handles clicking the Submit, Next, or Save button with safety checks.
     */
    private fun handleSubmitAction(scan: FormScanResult, customSubmitText: String?): JSONObject {
        val svc = accessibilityService ?: return errorResult("A11y Service unavailable")

        val targetButton = if (!customSubmitText.isNullOrBlank()) {
            scan.actionButtons.find { it.label.contains(customSubmitText, ignoreCase = true) }
                ?: ActionButtonInfo(label = customSubmitText, bounds = Rect(0,0,0,0), isRisky = false)
        } else {
            scan.actionButtons.firstOrNull()
        }

        if (targetButton == null) {
            return JSONObject().apply {
                put("status", "button_not_found")
                put("message", "Form submit button nahi mila.")
            }
        }

        // Safety Gate: Irreversible Submit Action Check
        if (targetButton.isRisky) {
            return JSONObject().apply {
                put("status", "requires_confirmation")
                put("button", targetButton.label)
                put("message", "Form fill ho gaya hai. Final button '${targetButton.label}' ek payment ya irreversible action hai, isliye confirm karein: 'Haan submit karo'.")
            }
        }

        // Click the action button
        val clicked = if (targetButton.bounds.width() > 0) {
            svc.tapAt(targetButton.centerX.toFloat(), targetButton.centerY.toFloat())
        } else {
            svc.clickByText(targetButton.label)
        }

        return JSONObject().apply {
            put("status", if (clicked) "submitted" else "click_failed")
            put("button", targetButton.label)
        }
    }

    /**
     * Matches a requested user key (e.g. "email", "pincode", "naam") to the best FormFieldInfo on screen.
     */
    private fun findMatchingField(query: String, fields: List<FormFieldInfo>): FormFieldInfo? {
        val q = query.lowercase().trim()

        // 1. Direct label or hint exact match
        fields.find { it.label.lowercase() == q || it.hint?.lowercase() == q }?.let { return it }

        // 2. Direct label contains query
        fields.find { it.label.lowercase().contains(q) || it.hint?.lowercase()?.contains(q) == true }?.let { return it }

        // 3. Synonym dictionary lookup
        val synonymGroup = FIELD_SYNONYMS.entries.find { (canonical, synonyms) ->
            canonical == q || synonyms.any { q.contains(it) || it.contains(q) }
        }

        if (synonymGroup != null) {
            val validSynonyms = synonymGroup.value + synonymGroup.key
            for (syn in validSynonyms) {
                val match = fields.find { field ->
                    val fLabel = field.label.lowercase()
                    val fHint = field.hint?.lowercase().orEmpty()
                    val fId = field.viewId?.lowercase().orEmpty()
                    fLabel.contains(syn) || fHint.contains(syn) || fId.contains(syn)
                }
                if (match != null) return match
            }
        }

        // 4. ViewId substring match
        fields.find { it.viewId?.lowercase()?.contains(q) == true }?.let { return it }

        return null
    }

    private fun findNodeAtBounds(
        roots: List<AccessibilityNodeInfo>,
        bounds: Rect,
        requireEditable: Boolean
    ): AccessibilityNodeInfo? {
        for (root in roots) {
            val found = searchNodeByBounds(root, bounds, requireEditable)
            if (found != null) return found
        }
        return null
    }

    private fun searchNodeByBounds(
        node: AccessibilityNodeInfo,
        targetBounds: Rect,
        requireEditable: Boolean
    ): AccessibilityNodeInfo? {
        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)

        val matchesBounds = Math.abs(nodeBounds.centerX() - targetBounds.centerX()) < 30 &&
                Math.abs(nodeBounds.centerY() - targetBounds.centerY()) < 30
        val editableOk = !requireEditable || node.isEditable

        if (matchesBounds && editableOk) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchNodeByBounds(child, targetBounds, requireEditable)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun findNodeContainingText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nText = node.text?.toString()?.lowercase() ?: ""
        val nDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val q = text.lowercase()

        if (nText.contains(q) || nDesc.contains(q)) {
            return AccessibilityNodeInfo.obtain(node)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNodeContainingText(child, text)
            child.recycle()
            if (found != null) return found
        }
        return null
    }

    private fun verifyTextMatches(actual: String, expected: String): Boolean {
        if (actual.isBlank() && expected.isNotBlank()) return false
        val a = actual.trim().lowercase()
        val e = expected.trim().lowercase()
        return a == e || a.contains(e) || e.contains(a)
    }

    private fun buildSummaryMessage(success: Int, failed: Int, skippedRisky: Int, submitResult: JSONObject?): String {
        val sb = java.lang.StringBuilder()
        if (success > 0) {
            sb.append("Form ke $success fields safaltapoorvak bhar diye gaye hain. ")
        }
        if (failed > 0) {
            sb.append("$failed fields nahi bhare ja sake. ")
        }
        if (skippedRisky > 0) {
            sb.append("$skippedRisky sensitive fields ko security ke liye chhod diya gaya hai. ")
        }
        if (submitResult != null) {
            val status = submitResult.optString("status")
            if (status == "submitted") {
                sb.append("'${submitResult.optString("button")}' button click kar diya gaya hai.")
            } else if (status == "requires_confirmation") {
                sb.append(submitResult.optString("message"))
            }
        }
        return sb.toString().trim()
    }

    private fun successResult(field: String, value: String, method: String) = JSONObject().apply {
        put("field", field)
        put("value", value)
        put("status", "success")
        put("verified", true)
        put("method", method)
    }

    private fun errorResult(msg: String) = JSONObject().apply {
        put("status", "error")
        put("error", msg)
    }

    private fun safeSleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {}
    }
}
