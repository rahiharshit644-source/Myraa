package com.soltini.app.security

import org.json.JSONArray
import org.json.JSONObject

/**
 * ProfileRole
 * Defines the organizational relationship and privilege tier of the speaker.
 */
enum class ProfileRole {
    OWNER,      // Complete control, admin & full assistant capabilities
    FAMILY,     // Explicitly allowed normal assistant features, strictly no personal private data
    GUEST       // Severely restricted sandboxed access (e.g. weather, time only)
}

/**
 * RiskLevel
 * Granular classification of command and action sensitivity.
 */
enum class RiskLevel {
    LOW_RISK,       // Information queries, time, weather, calculator
    MEDIUM_RISK,    // Playing music, setting alarms, general app launch
    HIGH_RISK,      // Sending messages, making phone calls, smart home toggles
    OWNER_ONLY      // Vault, private notes, personal messages, banking, profile management, API keys
}

/**
 * ResourceCategory
 * Granular resource targets for permission evaluation.
 */
enum class ResourceCategory {
    GENERAL_ASSISTANT,      // Time, weather, general QA, translation
    UTILITIES,              // Calculator, timer, torch
    MEDIA_PLAYBACK,         // Music, YouTube, podcasts
    ALARMS_REMINDERS,       // Set alarms, reminders
    PERMITTED_APPS,         // Opening allowed public applications
    CALLS_SMS_SEND,         // Outgoing calls or messages
    CALLS_SMS_READ,         // Reading personal incoming/stored SMS or WhatsApp
    PERSONAL_NOTES,         // Accessing personal notes and documents
    PERSONAL_MEMORIES,      // Accessing profile-scoped semantic and episodic memory
    PRIVATE_FILES_VAULT,    // Secure file vault, local private storage
    SETTINGS_SECURITY,      // Modifying Myra settings, API keys, permissions
    VOICE_PROFILES_MGMT,    // Adding, enrolling, or deleting voice profiles
    FINANCIAL_PAYMENTS      // Banking mode, UPI, payments (always blocked on voice alone)
}

/**
 * VoiceProfile
 * Encapsulates an individual enrolled user identity and their authorized access matrix.
 */
data class VoiceProfile(
    val id: String,
    val name: String,
    val relationship: String,
    val role: ProfileRole,
    val isEnrolled: Boolean = false,
    val enrolledAt: Long = 0L,
    val sampleCount: Int = 0,
    val allowedResources: Set<ResourceCategory> = defaultResourcesForRole(role),
    val maxAllowedRisk: RiskLevel = defaultRiskForRole(role),
    val isLocked: Boolean = false
) {
    companion object {
        fun defaultResourcesForRole(role: ProfileRole): Set<ResourceCategory> = when (role) {
            ProfileRole.OWNER -> ResourceCategory.values().toSet()
            ProfileRole.FAMILY -> setOf(
                ResourceCategory.GENERAL_ASSISTANT,
                ResourceCategory.UTILITIES,
                ResourceCategory.MEDIA_PLAYBACK,
                ResourceCategory.ALARMS_REMINDERS,
                ResourceCategory.PERMITTED_APPS
            )
            ProfileRole.GUEST -> setOf(
                ResourceCategory.GENERAL_ASSISTANT,
                ResourceCategory.UTILITIES
            )
        }

        fun defaultRiskForRole(role: ProfileRole): RiskLevel = when (role) {
            ProfileRole.OWNER -> RiskLevel.OWNER_ONLY
            ProfileRole.FAMILY -> RiskLevel.MEDIUM_RISK
            ProfileRole.GUEST -> RiskLevel.LOW_RISK
        }

        fun fromJsonObject(json: JSONObject): VoiceProfile {
            val id = json.optString("id", "")
            val name = json.optString("name", "User")
            val relationship = json.optString("relationship", "Family")
            val role = try {
                ProfileRole.valueOf(json.optString("role", ProfileRole.FAMILY.name))
            } catch (_: Exception) { ProfileRole.FAMILY }

            val isEnrolled = json.optBoolean("is_enrolled", false)
            val enrolledAt = json.optLong("enrolled_at", 0L)
            val sampleCount = json.optInt("sample_count", 0)
            val isLocked = json.optBoolean("is_locked", false)

            val resources = mutableSetOf<ResourceCategory>()
            val resArr = json.optJSONArray("allowed_resources")
            if (resArr != null) {
                for (i in 0 until resArr.length()) {
                    try {
                        resources.add(ResourceCategory.valueOf(resArr.getString(i)))
                    } catch (_: Exception) {}
                }
            } else {
                resources.addAll(defaultResourcesForRole(role))
            }

            val maxRisk = try {
                RiskLevel.valueOf(json.optString("max_allowed_risk", defaultRiskForRole(role).name))
            } catch (_: Exception) { defaultRiskForRole(role) }

            return VoiceProfile(
                id = id,
                name = name,
                relationship = relationship,
                role = role,
                isEnrolled = isEnrolled,
                enrolledAt = enrolledAt,
                sampleCount = sampleCount,
                allowedResources = resources,
                maxAllowedRisk = maxRisk,
                isLocked = isLocked
            )
        }
    }

    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("relationship", relationship)
        put("role", role.name)
        put("is_enrolled", isEnrolled)
        put("enrolled_at", enrolledAt)
        put("sample_count", sampleCount)
        put("is_locked", isLocked)
        put("max_allowed_risk", maxAllowedRisk.name)
        put("allowed_resources", JSONArray().apply {
            allowedResources.forEach { put(it.name) }
        })
    }
}

/**
 * PermissionCheckResult
 * Outcome of evaluating an action request against a profile's permissions.
 */
sealed class PermissionCheckResult {
    data class Allowed(val profile: VoiceProfile) : PermissionCheckResult()
    data class AllowedWithConfirmation(val profile: VoiceProfile, val prompt: String) : PermissionCheckResult()
    data class RequiresBiometricPrompt(val profile: VoiceProfile, val reason: String) : PermissionCheckResult()
    data class Denied(val profile: VoiceProfile?, val reason: String, val isPrivacyProtected: Boolean = false) : PermissionCheckResult()
}

/**
 * SpeakerIdentificationResult
 * Outcome of comparing a live speech utterance against all enrolled voice profiles.
 */
sealed class SpeakerIdentificationResult {
    data class Identified(
        val profile: VoiceProfile,
        val similarity: Float,
        val threshold: Float
    ) : SpeakerIdentificationResult()

    data class AmbiguousMatch(
        val candidateA: Pair<VoiceProfile, Float>,
        val candidateB: Pair<VoiceProfile, Float>,
        val margin: Float
    ) : SpeakerIdentificationResult()

    data class UnknownSpeaker(
        val topScore: Float,
        val threshold: Float,
        val message: String = "Voice verify nahi hui. Password enter karein."
    ) : SpeakerIdentificationResult()

    data class InsufficientAudio(
        val reason: String = "Acoustic signal too weak for speaker verification."
    ) : SpeakerIdentificationResult()
}

/**
 * VoiceAuditLogEntry
 * Minimal, privacy-preserving audit entry for compliance and security review.
 * NEVER stores raw audio, embeddings, passwords, PINs, or private message texts.
 */
data class VoiceAuditLogEntry(
    val id: String,
    val timestamp: Long,
    val profileId: String?,
    val profileName: String?,
    val actionType: String,
    val resourceCategory: ResourceCategory,
    val riskLevel: RiskLevel,
    val isSuccess: Boolean,
    val reason: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("timestamp", timestamp)
        put("profile_id", profileId ?: "unknown")
        put("profile_name", profileName ?: "Unknown Speaker")
        put("action_type", actionType)
        put("resource", resourceCategory.name)
        put("risk_level", riskLevel.name)
        put("is_success", isSuccess)
        put("reason", reason)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): VoiceAuditLogEntry = VoiceAuditLogEntry(
            id = json.optString("id", ""),
            timestamp = json.optLong("timestamp", System.currentTimeMillis()),
            profileId = json.optString("profile_id", null),
            profileName = json.optString("profile_name", null),
            actionType = json.optString("action_type", "QUERY"),
            resourceCategory = try {
                ResourceCategory.valueOf(json.optString("resource", ResourceCategory.GENERAL_ASSISTANT.name))
            } catch (_: Exception) { ResourceCategory.GENERAL_ASSISTANT },
            riskLevel = try {
                RiskLevel.valueOf(json.optString("risk_level", RiskLevel.LOW_RISK.name))
            } catch (_: Exception) { RiskLevel.LOW_RISK },
            isSuccess = json.optBoolean("is_success", false),
            reason = json.optString("reason", "")
        )
    }
}
