package com.soltini.app.agent

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * FormFieldType
 * Identifies the semantic purpose and input mechanism for a UI form element.
 */
enum class FormFieldType {
    TEXT,
    EMAIL,
    PHONE,
    NUMBER,
    PASSWORD,
    CHECKBOX,
    RADIO,
    SWITCH,
    DROPDOWN,
    DATE_PICKER,
    OTP,
    CAPTCHA,
    UNKNOWN
}

/**
 * FormFieldInfo
 * Represents an inspectable, interactive form field discovered on the active screen.
 */
data class FormFieldInfo(
    val index: Int,
    val label: String,
    val hint: String?,
    val currentValue: String,
    val fieldType: FormFieldType,
    val bounds: Rect,
    val className: String,
    val viewId: String?,
    val isEditable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isPassword: Boolean,
    val isRisky: Boolean,
    val isRequired: Boolean = false
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("index", index)
        put("label", label)
        if (!hint.isNullOrBlank()) put("hint", hint)
        put("current_value", currentValue)
        put("type", fieldType.name.lowercase())
        put("class", className.substringAfterLast('.'))
        if (!viewId.isNullOrBlank()) put("view_id", viewId.substringAfterLast('/'))
        put("bounds", "[${bounds.left},${bounds.top} to ${bounds.right},${bounds.bottom}]")
        put("centerX", centerX)
        put("centerY", centerY)
        put("editable", isEditable)
        if (isCheckable) put("checked", isChecked)
        put("password", isPassword)
        put("risky", isRisky)
        put("required", isRequired)
    }
}

/**
 * ActionButtonInfo
 * Represents action buttons that progress or submit the form (e.g. Next, Submit, Continue).
 */
data class ActionButtonInfo(
    val label: String,
    val bounds: Rect,
    val isRisky: Boolean = false
) {
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("label", label)
        put("centerX", centerX)
        put("centerY", centerY)
        put("bounds", "[${bounds.left},${bounds.top} to ${bounds.right},${bounds.bottom}]")
        put("risky", isRisky)
    }
}

/**
 * FormScanResult
 * Comprehensive structural representation of the current screen's form state.
 */
data class FormScanResult(
    val fields: List<FormFieldInfo>,
    val actionButtons: List<ActionButtonInfo>,
    val hasCaptcha: Boolean,
    val captchaDetails: String?,
    val hasRiskyFields: Boolean,
    val visibleSummary: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("status", "success")
        put("field_count", fields.size)
        put("has_captcha", hasCaptcha)
        if (hasCaptcha && captchaDetails != null) put("captcha_details", captchaDetails)
        put("has_risky_fields", hasRiskyFields)
        put("fields", JSONArray().apply { fields.forEach { put(it.toJsonObject()) } })
        put("action_buttons", JSONArray().apply { actionButtons.forEach { put(it.toJsonObject()) } })
        put("summary", visibleSummary)
    }
}

/**
 * ScreenAnalyzer
 *
 * High-precision accessibility tree inspector designed specifically for Universal Form Filling.
 *
 * Responsibilities:
 * 1. Deep tree walk across all active root nodes.
 * 2. Multi-strategy Label Association:
 *    - Explicit accessibility hint / contentDescription
 *    - Resource ID inference (e.g. "et_user_email", "input_first_name")
 *    - Spatial Proximity: associates closest preceding TextView positioned directly above or left
 *    - Container grouping (TextInputLayout, FormLayout)
 * 3. Element classification: Text, Email, Phone, Dropdowns, Checkboxes, Switches, Radios, DatePickers.
 * 4. Security & Safety screening: Captcha detection and Risky Field flagging (passwords, OTPs, CVV, Card numbers).
 */
class ScreenAnalyzer(private val context: Context) {

    companion object {
        private const val TAG = "ScreenAnalyzer"

        private val RISKY_KEYWORDS = listOf(
            "password", "passwd", "pin", "otp", "cvv", "card number", "card_number",
            "credit card", "debit card", "upi pin", "bank", "ssn", "aadhaar", "secret"
        )

        private val CAPTCHA_KEYWORDS = listOf(
            "captcha", "recaptcha", "enter code", "type the characters",
            "security check", "verify you are human", "not a robot", "bot detection"
        )

        private val RISKY_BUTTON_KEYWORDS = listOf(
            "pay", "payment", "delete account", "confirm order", "checkout", "transfer", "send money"
        )

        private val ACTION_BUTTON_KEYWORDS = listOf(
            "submit", "next", "continue", "proceed", "save", "register", "sign up",
            "sign in", "login", "log in", "apply", "confirm", "done", "next step",
            "pay", "checkout", "finish"
        )
    }

    private val accessibilityService: SoltiniAccessibilityService?
        get() = SoltiniAccessibilityService.getInstance()

    /**
     * Inspects the current screen and returns a structured [FormScanResult].
     */
    fun scanScreen(): FormScanResult {
        val svc = accessibilityService ?: return FormScanResult(
            fields = emptyList(),
            actionButtons = emptyList(),
            hasCaptcha = false,
            captchaDetails = null,
            hasRiskyFields = false,
            visibleSummary = "Accessibility Service is not active"
        )

        val rawLabels = mutableListOf<LabelNode>()
        val rawInputs = mutableListOf<RawInputNode>()
        val actionButtons = mutableListOf<ActionButtonInfo>()
        val allVisibleTexts = mutableListOf<String>()
        var captchaDetected = false
        var captchaReason: String? = null

        try {
            svc.withRootNodes { roots ->
                for (root in roots) {
                    traverseForForm(root, rawLabels, rawInputs, actionButtons, allVisibleTexts)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning screen tree: ${e.message}", e)
        }

        // Check visible text for Captcha triggers
        val combinedText = allVisibleTexts.joinToString(" ").lowercase()
        for (kw in CAPTCHA_KEYWORDS) {
            if (combinedText.contains(kw)) {
                captchaDetected = true
                captchaReason = "Detected security check or captcha containing '$kw'"
                break
            }
        }

        // Correlate raw input fields with nearest labels & classify
        val formFields = mutableListOf<FormFieldInfo>()
        var fieldIndex = 0

        for (input in rawInputs) {
            val associatedLabel = resolveLabelForInput(input, rawLabels)
            val fieldType = inferFieldType(input, associatedLabel)
            val isRisky = input.isPassword || isRiskyField(associatedLabel, input.viewId, fieldType)

            val info = FormFieldInfo(
                index = fieldIndex++,
                label = associatedLabel,
                hint = input.hint,
                currentValue = input.currentText,
                fieldType = fieldType,
                bounds = input.bounds,
                className = input.className,
                viewId = input.viewId,
                isEditable = input.isEditable,
                isCheckable = input.isCheckable,
                isChecked = input.isChecked,
                isPassword = input.isPassword || fieldType == FormFieldType.PASSWORD,
                isRisky = isRisky,
                isRequired = associatedLabel.contains("*") || input.hint?.contains("*") == true
            )
            formFields.add(info)
        }

        val hasRiskyFields = formFields.any { it.isRisky }
        val summary = allVisibleTexts.distinct().take(30).joinToString(" • ")

        return FormScanResult(
            fields = formFields,
            actionButtons = actionButtons.distinctBy { it.label.lowercase() },
            hasCaptcha = captchaDetected,
            captchaDetails = captchaReason,
            hasRiskyFields = hasRiskyFields,
            visibleSummary = summary
        )
    }

    private fun traverseForForm(
        node: AccessibilityNodeInfo,
        labels: MutableList<LabelNode>,
        inputs: MutableList<RawInputNode>,
        actionButtons: MutableList<ActionButtonInfo>,
        allTexts: MutableList<String>
    ) {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()?.trim()
        } else null
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val displayText = if (text.isNotBlank()) text else desc
        if (displayText.isNotBlank()) {
            allTexts.add(displayText)
        }

        val isEditable = node.isEditable || className.contains("EditText", ignoreCase = true)
        val isCheckable = node.isCheckable || className.contains("CheckBox", ignoreCase = true) ||
                className.contains("RadioButton", ignoreCase = true) || className.contains("Switch", ignoreCase = true)
        val isSpinner = className.contains("Spinner", ignoreCase = true) ||
                className.contains("AutoCompleteTextView", ignoreCase = true) ||
                (node.isClickable && (displayText.contains("select", ignoreCase = true) || displayText.contains("choose", ignoreCase = true)))
        val isDatePicker = className.contains("DatePicker", ignoreCase = true) ||
                className.contains("CalendarView", ignoreCase = true)

        // Action Button Detection (Buttons, Clickable text like Submit, Next, Save)
        if (node.isClickable && !isEditable && displayText.isNotBlank()) {
            val lower = displayText.lowercase()
            val isAction = ACTION_BUTTON_KEYWORDS.any { kw ->
                lower == kw || lower.startsWith("$kw ") || lower.endsWith(" $kw")
            } || className.contains("Button", ignoreCase = true)

            if (isAction && bounds.width() > 0 && bounds.height() > 0) {
                val isRisky = RISKY_BUTTON_KEYWORDS.any { lower.contains(it) }
                actionButtons.add(ActionButtonInfo(label = displayText, bounds = bounds, isRisky = isRisky))
            }
        }

        // Collect Form Inputs
        if (isEditable || isCheckable || isSpinner || isDatePicker) {
            inputs.add(
                RawInputNode(
                    currentText = text,
                    desc = desc,
                    hint = hint,
                    viewId = viewId,
                    className = className,
                    bounds = bounds,
                    isEditable = isEditable,
                    isCheckable = isCheckable,
                    isChecked = node.isChecked,
                    isPassword = node.isPassword,
                    isSpinner = isSpinner,
                    isDatePicker = isDatePicker
                )
            )
        } else if (displayText.isNotBlank()) {
            // Label Candidate
            labels.add(
                LabelNode(
                    text = displayText,
                    bounds = bounds,
                    viewId = viewId
                )
            )
        }

        // Recurse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseForForm(child, labels, inputs, actionButtons, allTexts)
            child.recycle()
        }
    }

    /**
     * Resolves the most accurate label for an input node using hint, contentDescription,
     * viewId resource name, or nearest spatial preceding label.
     */
    private fun resolveLabelForInput(input: RawInputNode, labels: List<LabelNode>): String {
        // Strategy 1: Explicit Hint or Description
        if (!input.hint.isNullOrBlank()) return input.hint
        if (input.desc.isNotBlank()) return input.desc

        // Strategy 2: Spatial Proximity (find label directly above or to the left)
        var closestLabel: String? = null
        var minDistance = Double.MAX_VALUE

        for (lbl in labels) {
            val lb = lbl.bounds
            val ib = input.bounds

            // Label must be above the input (ib.top >= lb.bottom) or to the left on the same horizontal line
            val isAbove = lb.bottom <= ib.top + 20 && Math.abs(lb.left - ib.left) < 300
            val isLeft = lb.right <= ib.left + 20 && Math.abs(lb.centerY() - ib.centerY()) < 60

            if (isAbove || isLeft) {
                val dx = (ib.centerX() - lb.centerX()).toDouble()
                val dy = (ib.centerY() - lb.centerY()).toDouble()
                val dist = Math.hypot(dx, dy)
                if (dist < minDistance && dist < 500) { // within reasonable distance
                    minDistance = dist
                    closestLabel = lbl.text
                }
            }
        }

        if (!closestLabel.isNullOrBlank()) return closestLabel

        // Strategy 3: Deduce from Resource ID (e.g. "et_user_name" -> "User Name")
        if (!input.viewId.isNullOrBlank()) {
            val rawId = input.viewId.substringAfterLast('/')
                .replace("et_", "")
                .replace("input_", "")
                .replace("txt_", "")
                .replace("edit_", "")
                .replace("_", " ")
                .trim()
            if (rawId.isNotBlank()) return rawId.capitalizeWords()
        }

        // Strategy 4: Fallback to existing text or generic placeholder
        return if (input.currentText.isNotBlank()) input.currentText else "Input Field"
    }

    /**
     * Infers semantic FormFieldType from label, viewId, and class properties.
     */
    private fun inferFieldType(input: RawInputNode, label: String): FormFieldType {
        val key = "$label ${input.viewId.orEmpty()} ${input.desc}".lowercase()

        return when {
            input.isPassword || key.contains("password") || key.contains("passwd") || key.contains("pin") -> FormFieldType.PASSWORD
            key.contains("otp") || key.contains("verification code") -> FormFieldType.OTP
            key.contains("email") || key.contains("e-mail") -> FormFieldType.EMAIL
            key.contains("phone") || key.contains("mobile") || key.contains("contact") -> FormFieldType.PHONE
            input.isCheckable && (input.className.contains("Radio") || key.contains("gender") || key.contains("option")) -> FormFieldType.RADIO
            input.isCheckable && (input.className.contains("Switch") || input.className.contains("Toggle")) -> FormFieldType.SWITCH
            input.isCheckable -> FormFieldType.CHECKBOX
            input.isSpinner || key.contains("dropdown") || key.contains("select") -> FormFieldType.DROPDOWN
            input.isDatePicker || key.contains("date") || key.contains("dob") || key.contains("birthday") -> FormFieldType.DATE_PICKER
            key.contains("zip") || key.contains("pincode") || key.contains("pin code") || key.contains("postal") || key.contains("age") || key.contains("amount") -> FormFieldType.NUMBER
            else -> FormFieldType.TEXT
        }
    }

    private fun isRiskyField(label: String, viewId: String?, type: FormFieldType): Boolean {
        if (type == FormFieldType.PASSWORD || type == FormFieldType.OTP) return true
        val combined = "$label ${viewId.orEmpty()}".lowercase()
        return RISKY_KEYWORDS.any { combined.contains(it) }
    }

    private fun String.capitalizeWords(): String = split(" ").joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    private data class LabelNode(
        val text: String,
        val bounds: Rect,
        val viewId: String?
    )

    private data class RawInputNode(
        val currentText: String,
        val desc: String,
        val hint: String?,
        val viewId: String?,
        val className: String,
        val bounds: Rect,
        val isEditable: Boolean,
        val isCheckable: Boolean,
        val isChecked: Boolean,
        val isPassword: Boolean,
        val isSpinner: Boolean,
        val isDatePicker: Boolean
    )
}
