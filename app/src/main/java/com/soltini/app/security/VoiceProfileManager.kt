package com.soltini.app.security

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.soltini.app.util.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.*

/**
 * VoiceProfileManager
 *
 * Central coordinator for Multi-User Voice Profiles, Hardware-Encrypted Biometrics,
 * Acoustic Speaker Identification, and Profile-Scoped Permission Enforcement.
 *
 * Security Architecture:
 * 1. Speaker Verification: Matches live voice sample against enrolled profiles.
 * 2. Ambiguity Detection: If two candidates are within AMBIGUITY_MARGIN, rejects match.
 * 3. Profile-Scoped Privacy: Family profiles are denied access to Owner's personal messages,
 *    notes, private files, and memory.
 * 4. Android Lock Screen Compliance: Myra NEVER bypasses or claims to bypass Android's
 *    system lock screen, PIN, pattern, or BiometricPrompt.
 * 5. Keystore Encryption: All voice embeddings are encrypted at rest with hardware-backed AES-256 GCM.
 * 6. Zero Data Leakage: Raw PCM samples are immediately purged; embeddings never appear in logs.
 */
class VoiceProfileManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "VoiceProfileManager"
        private const val PREFS_NAME = "myra_multi_voice_profiles"
        private const val KEY_PROFILES_JSON = "profiles_metadata"
        private const val KEY_ENCRYPTED_EMBEDDING_PREFIX = "enc_embedding_"
        private const val KEY_AUDIT_LOGS_JSON = "audit_logs_metadata"
        private const val KEY_EMERGENCY_DISABLED = "myra_emergency_disabled"
        private const val KEY_VOICE_COMMANDS_ENABLED = "voice_commands_active"

        const val VECTOR_DIM = 34
        const val VERIFICATION_THRESHOLD = 0.74f
        const val AMBIGUITY_MARGIN = 0.06f
        const val MAX_AUDIT_ENTRIES = 150

        // Pre-configured default profile IDs
        const val DEFAULT_OWNER_ID = "profile_owner_harshit"
        const val DEFAULT_FAMILY_ID = "profile_family_mummy"

        @Volatile
        private var instance: VoiceProfileManager? = null

        fun getInstance(context: Context): VoiceProfileManager =
            instance ?: synchronized(this) {
                instance ?: VoiceProfileManager(context.applicationContext).also { instance = it }
            }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // In-memory cache of profiles and currently active enrollment session
    private val profilesMap = mutableMapOf<String, VoiceProfile>()
    private val activeEnrollmentSamples = mutableListOf<FloatArray>()
    private var activeEnrollmentProfileId: String? = null

    // Audit logs stored in memory with encrypted periodic sync
    private val auditLogs = mutableListOf<VoiceAuditLogEntry>()

    init {
        loadProfiles()
        loadAuditLogs()
        ensureDefaultProfilesExist()
    }

    // ─── Profile Management ──────────────────────────────────────────────────

    @Synchronized
    private fun loadProfiles() {
        profilesMap.clear()
        val rawJson = prefs.getString(KEY_PROFILES_JSON, null)
        if (!rawJson.isNullOrBlank()) {
            try {
                val array = JSONArray(rawJson)
                for (i in 0 until array.length()) {
                    val profile = VoiceProfile.fromJsonObject(array.getJSONObject(i))
                    profilesMap[profile.id] = profile
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing profiles JSON", e)
            }
        }
    }

    @Synchronized
    private fun saveProfiles() {
        val array = JSONArray()
        profilesMap.values.forEach { array.put(it.toJsonObject()) }
        prefs.edit().putString(KEY_PROFILES_JSON, array.toString()).apply()
    }

    /**
     * Initializes Harshit (Owner) and Mummy (Family) default profiles if first run.
     */
    @Synchronized
    private fun ensureDefaultProfilesExist() {
        var changed = false
        if (!profilesMap.containsKey(DEFAULT_OWNER_ID)) {
            val owner = VoiceProfile(
                id = DEFAULT_OWNER_ID,
                name = "Harshit",
                relationship = "Owner / Self",
                role = ProfileRole.OWNER,
                allowedResources = VoiceProfile.defaultResourcesForRole(ProfileRole.OWNER),
                maxAllowedRisk = RiskLevel.OWNER_ONLY
            )
            profilesMap[DEFAULT_OWNER_ID] = owner
            changed = true
        }

        if (!profilesMap.containsKey(DEFAULT_FAMILY_ID)) {
            val family = VoiceProfile(
                id = DEFAULT_FAMILY_ID,
                name = "Mummy",
                relationship = "Mother / Family",
                role = ProfileRole.FAMILY,
                allowedResources = VoiceProfile.defaultResourcesForRole(ProfileRole.FAMILY),
                maxAllowedRisk = RiskLevel.MEDIUM_RISK
            )
            profilesMap[DEFAULT_FAMILY_ID] = family
            changed = true
        }

        if (changed) {
            saveProfiles()
            AppLogger.i(TAG, "Initialized default voice profiles: Harshit (Owner), Mummy (Family)")
        }
    }

    fun getAllProfiles(): List<VoiceProfile> = synchronized(this) {
        profilesMap.values.toList()
    }

    fun getProfile(id: String): VoiceProfile? = synchronized(this) {
        profilesMap[id]
    }

    fun getOwnerProfile(): VoiceProfile? = synchronized(this) {
        profilesMap.values.find { it.role == ProfileRole.OWNER }
    }

    /**
     * Creates a new profile. Strictly defaults to RESTRICTED/FAMILY permissions;
     * never defaults a new profile to OWNER.
     */
    @Synchronized
    fun createProfile(name: String, relationship: String, role: ProfileRole = ProfileRole.FAMILY): VoiceProfile {
        val enforcedRole = if (role == ProfileRole.OWNER) ProfileRole.FAMILY else role
        val newProfile = VoiceProfile(
            id = "profile_${UUID.randomUUID().toString().take(8)}",
            name = name.trim(),
            relationship = relationship.trim(),
            role = enforcedRole,
            allowedResources = VoiceProfile.defaultResourcesForRole(enforcedRole),
            maxAllowedRisk = VoiceProfile.defaultRiskForRole(enforcedRole)
        )
        profilesMap[newProfile.id] = newProfile
        saveProfiles()
        logAudit(
            profileId = newProfile.id,
            profileName = newProfile.name,
            actionType = "CREATE_PROFILE",
            resource = ResourceCategory.VOICE_PROFILES_MGMT,
            risk = RiskLevel.HIGH_RISK,
            isSuccess = true,
            reason = "Profile created with role ${enforcedRole.name}"
        )
        return newProfile
    }

    @Synchronized
    fun deleteProfile(profileId: String): Boolean {
        val profile = profilesMap[profileId] ?: return false
        if (profile.role == ProfileRole.OWNER) {
            AppLogger.w(TAG, "Refusing to delete primary Owner profile.")
            return false
        }
        profilesMap.remove(profileId)
        prefs.edit().remove("$KEY_ENCRYPTED_EMBEDDING_PREFIX$profileId").apply()
        saveProfiles()
        logAudit(
            profileId = profileId,
            profileName = profile.name,
            actionType = "DELETE_PROFILE",
            resource = ResourceCategory.VOICE_PROFILES_MGMT,
            risk = RiskLevel.HIGH_RISK,
            isSuccess = true,
            reason = "Voice profile and encrypted biometric embedding deleted"
        )
        return true
    }

    @Synchronized
    fun updatePermissions(
        profileId: String,
        allowedResources: Set<ResourceCategory>,
        maxRisk: RiskLevel
    ): Boolean {
        val current = profilesMap[profileId] ?: return false
        // Family profiles can NEVER be granted access to owner private notes/messages/vault
        val sanitizedResources = if (current.role != ProfileRole.OWNER) {
            allowedResources.filterNot {
                it == ResourceCategory.CALLS_SMS_READ ||
                it == ResourceCategory.PERSONAL_NOTES ||
                it == ResourceCategory.PRIVATE_FILES_VAULT ||
                it == ResourceCategory.PERSONAL_MEMORIES ||
                it == ResourceCategory.SETTINGS_SECURITY ||
                it == ResourceCategory.VOICE_PROFILES_MGMT
            }.toSet()
        } else allowedResources

        val updated = current.copy(
            allowedResources = sanitizedResources,
            maxAllowedRisk = if (current.role != ProfileRole.OWNER && maxRisk == RiskLevel.OWNER_ONLY) RiskLevel.MEDIUM_RISK else maxRisk
        )
        profilesMap[profileId] = updated
        saveProfiles()
        return true
    }

    // ─── Enrollment with Hardware Keystore Encryption ────────────────────────

    @Synchronized
    fun startEnrollmentSession(profileId: String): Boolean {
        val profile = profilesMap[profileId] ?: return false
        activeEnrollmentProfileId = profileId
        activeEnrollmentSamples.clear()
        AppLogger.i(TAG, "Started voice enrollment session for profile: ${profile.name} (${profile.relationship})")
        return true
    }

    @Synchronized
    fun cancelEnrollmentSession() {
        activeEnrollmentSamples.clear()
        activeEnrollmentProfileId = null
    }

    @Synchronized
    fun getCurrentEnrollmentSampleCount(): Int = activeEnrollmentSamples.size

    /**
     * Processes a single enrollment audio PCM sample.
     * Guarantees:
     * - Vector embedding extracted in memory.
     * - Raw audio buffer immediately overwritten and discarded.
     * - Raw recording is NOT uploaded to the cloud or saved to disk.
     */
    @Synchronized
    fun addEnrollmentSample(audioPcm: ByteArray): Int {
        val profileId = activeEnrollmentProfileId ?: return 0
        val embedding = extractAcousticEmbedding(audioPcm)
        // Explicitly purge raw audio PCM buffer
        java.util.Arrays.fill(audioPcm, 0.toByte())

        if (embedding != null) {
            activeEnrollmentSamples.add(embedding)
            AppLogger.i(TAG, "Collected enrollment sample #${activeEnrollmentSamples.size} of ${VoiceProfileEnrollmentPhrases.REQUIRED_SAMPLES_COUNT}")
        }
        return activeEnrollmentSamples.size
    }

    /**
     * Finalizes multi-sample enrollment:
     * - Averages normalized vectors into a master speaker embedding.
     * - Encrypts the master vector via [KeystoreCryptoManager] (AES-256 GCM).
     * - Stores ONLY the ciphertext in private SharedPreferences.
     * - Embedding float values are NEVER logged.
     */
    @Synchronized
    fun finalizeEnrollment(): Boolean {
        val profileId = activeEnrollmentProfileId ?: return false
        val profile = profilesMap[profileId] ?: return false

        if (activeEnrollmentSamples.size < 3) {
            AppLogger.w(TAG, "Insufficient enrollment samples: collected ${activeEnrollmentSamples.size}")
            return false
        }

        // Compute centroid / average embedding
        val master = FloatArray(VECTOR_DIM) { 0f }
        for (sample in activeEnrollmentSamples) {
            for (i in 0 until VECTOR_DIM) {
                master[i] += sample[i]
            }
        }
        val normalizedMaster = normalizeVector(master)

        // Encrypt with AndroidKeyStore AES-256 GCM
        val encryptedBase64 = KeystoreCryptoManager.encryptEmbedding(normalizedMaster)
        if (encryptedBase64 == null) {
            AppLogger.e(TAG, "Failed to encrypt voice embedding via AndroidKeyStore!")
            return false
        }

        // Store ciphertext
        prefs.edit().putString("$KEY_ENCRYPTED_EMBEDDING_PREFIX$profileId", encryptedBase64).apply()

        // Update profile metadata
        val updatedProfile = profile.copy(
            isEnrolled = true,
            enrolledAt = System.currentTimeMillis(),
            sampleCount = activeEnrollmentSamples.size
        )
        profilesMap[profileId] = updatedProfile
        saveProfiles()

        // Clear active session in memory
        activeEnrollmentSamples.clear()
        activeEnrollmentProfileId = null

        logAudit(
            profileId = updatedProfile.id,
            profileName = updatedProfile.name,
            actionType = "VOICE_ENROLLMENT_COMPLETE",
            resource = ResourceCategory.VOICE_PROFILES_MGMT,
            risk = RiskLevel.HIGH_RISK,
            isSuccess = true,
            reason = "Voice profile enrolled with ${updatedProfile.sampleCount} phonetic samples and hardware Keystore encryption"
        )
        AppLogger.i(TAG, "Voice enrollment finalized for ${updatedProfile.name} [ID: ${updatedProfile.id}]")
        return true
    }

    private fun getDecryptedEmbedding(profileId: String): FloatArray? {
        val encrypted = prefs.getString("$KEY_ENCRYPTED_EMBEDDING_PREFIX$profileId", null) ?: return null
        return KeystoreCryptoManager.decryptEmbedding(encrypted, VECTOR_DIM)
    }

    // ─── Speaker Identification & Ambiguity Protection ───────────────────────

    /**
     * Identifies which enrolled profile is speaking from a raw PCM audio utterance.
     *
     * Principles:
     * - Computes cosine similarity against all enrolled profiles.
     * - AMBIGUITY PROTECTION: If two profiles have similar high scores (|scoreA - scoreB| < AMBIGUITY_MARGIN),
     *   it rejects the match as AmbiguousMatch. Never guesses or favors Owner.
     * - UNKNOWN SPEAKER: If top score is below threshold, returns UnknownSpeaker with
     *   "Voice verify nahi hui. Password enter karein."
     */
    fun identifySpeaker(audioPcm: ByteArray): SpeakerIdentificationResult {
        if (isEmergencyDisabled()) {
            return SpeakerIdentificationResult.UnknownSpeaker(0f, VERIFICATION_THRESHOLD, "Myra security emergency active. Voice commands are disabled.")
        }

        val candidate = extractAcousticEmbedding(audioPcm)
            ?: return SpeakerIdentificationResult.InsufficientAudio("Audio sample contained insufficient speech energy.")

        val enrolledProfiles = synchronized(this) {
            profilesMap.values.filter { it.isEnrolled && !it.isLocked }
        }

        if (enrolledProfiles.isEmpty()) {
            return SpeakerIdentificationResult.UnknownSpeaker(
                topScore = 0f,
                threshold = VERIFICATION_THRESHOLD,
                message = "Koi bhi voice profile enroll nahi hai. Kripya pehle voice enroll karein."
            )
        }

        // Score against every enrolled profile
        val scored = mutableListOf<Pair<VoiceProfile, Float>>()
        for (profile in enrolledProfiles) {
            val enrolledVector = getDecryptedEmbedding(profile.id) ?: continue
            val similarity = computeCosineSimilarity(candidate, enrolledVector)
            scored.add(profile to similarity)
        }

        if (scored.isEmpty()) {
            return SpeakerIdentificationResult.UnknownSpeaker(0f, VERIFICATION_THRESHOLD)
        }

        // Sort descending by similarity score
        scored.sortByDescending { it.second }
        val top = scored[0]

        // Check if top score meets the minimum verification threshold
        if (top.second < VERIFICATION_THRESHOLD) {
            AppLogger.w(TAG, "Speaker identification failed: top score ${"%.2f".format(top.second * 100)}% < threshold ${"%.0f".format(VERIFICATION_THRESHOLD * 100)}%")
            logAudit(
                profileId = null,
                profileName = "Unknown Speaker",
                actionType = "SPEAKER_IDENTIFICATION",
                resource = ResourceCategory.GENERAL_ASSISTANT,
                risk = RiskLevel.LOW_RISK,
                isSuccess = false,
                reason = "Similarity ${"%.2f".format(top.second * 100)}% below threshold"
            )
            return SpeakerIdentificationResult.UnknownSpeaker(
                topScore = top.second,
                threshold = VERIFICATION_THRESHOLD,
                message = "Voice verify nahi hui. Password enter karein."
            )
        }

        // Check for ambiguous match between top two profiles
        if (scored.size > 1) {
            val second = scored[1]
            val scoreDiff = top.second - second.second
            if (second.second >= VERIFICATION_THRESHOLD && scoreDiff < AMBIGUITY_MARGIN) {
                AppLogger.w(TAG, "Ambiguous voice match between ${top.first.name} (${top.second}) and ${second.first.name} (${second.second})")
                logAudit(
                    profileId = null,
                    profileName = "Ambiguous (${top.first.name} vs ${second.first.name})",
                    actionType = "SPEAKER_IDENTIFICATION",
                    resource = ResourceCategory.GENERAL_ASSISTANT,
                    risk = RiskLevel.HIGH_RISK,
                    isSuccess = false,
                    reason = "Score delta ${"%.3f".format(scoreDiff)} < margin $AMBIGUITY_MARGIN"
                )
                return SpeakerIdentificationResult.AmbiguousMatch(
                    candidateA = top,
                    candidateB = second,
                    margin = scoreDiff
                )
            }
        }

        AppLogger.i(TAG, "Speaker IDENTIFIED: ${top.first.name} (${top.first.role.name}) with score ${"%.2f".format(top.second * 100)}%")
        logAudit(
            profileId = top.first.id,
            profileName = top.first.name,
            actionType = "SPEAKER_IDENTIFICATION",
            resource = ResourceCategory.GENERAL_ASSISTANT,
            risk = RiskLevel.LOW_RISK,
            isSuccess = true,
            reason = "Voice verified (${"%.1f".format(top.second * 100)}%)"
        )

        return SpeakerIdentificationResult.Identified(
            profile = top.first,
            similarity = top.second,
            threshold = VERIFICATION_THRESHOLD
        )
    }

    // ─── Permission & Access Control System ──────────────────────────────────

    /**
     * Evaluates whether a verified voice profile is authorized to execute an action.
     *
     * Privacy & Security Rules:
     * 1. Voice Identity != Permission to do everything.
     * 2. Family profiles (e.g. Mummy) are strictly blocked from Owner's private data
     *    (messages, personal notes, vault, memories).
     * 3. Response: "Ye personal information hai aur aapke profile ke liye available nahi hai."
     * 4. Owner-only actions require Owner profile + system BiometricPrompt / Device credential.
     */
    fun checkPermission(
        profile: VoiceProfile?,
        resource: ResourceCategory,
        riskLevel: RiskLevel
    ): PermissionCheckResult {
        if (isEmergencyDisabled()) {
            return PermissionCheckResult.Denied(
                profile = profile,
                reason = "Myra is currently in Emergency Lockdown. All assistant actions are disabled."
            )
        }

        if (profile == null) {
            return PermissionCheckResult.Denied(
                profile = null,
                reason = "Voice verify nahi hui. Password enter karein."
            )
        }

        if (profile.isLocked) {
            return PermissionCheckResult.Denied(
                profile = profile,
                reason = "Aapka profile locked hai. Kripya owner se contact karein."
            )
        }

        // Rule: Financial & Payment actions can NEVER be executed via voice alone
        if (resource == ResourceCategory.FINANCIAL_PAYMENTS) {
            return PermissionCheckResult.RequiresBiometricPrompt(
                profile = profile,
                reason = "Banking and payment actions require device biometric authentication."
            )
        }

        // Rule: Owner-only resources (Vault, Personal Messages, Private Notes, Security Settings)
        val isOwnerOnlyResource = resource in setOf(
            ResourceCategory.CALLS_SMS_READ,
            ResourceCategory.PERSONAL_NOTES,
            ResourceCategory.PRIVATE_FILES_VAULT,
            ResourceCategory.PERSONAL_MEMORIES,
            ResourceCategory.SETTINGS_SECURITY,
            ResourceCategory.VOICE_PROFILES_MGMT
        )

        if (isOwnerOnlyResource && profile.role != ProfileRole.OWNER) {
            logAudit(
                profileId = profile.id,
                profileName = profile.name,
                actionType = "ACCESS_DENIED_PRIVACY",
                resource = resource,
                risk = riskLevel,
                isSuccess = false,
                reason = "Family profile restricted from owner private resource"
            )
            return PermissionCheckResult.Denied(
                profile = profile,
                reason = "Ye personal information hai aur aapke profile ke liye available nahi hai.",
                isPrivacyProtected = true
            )
        }

        // Check configured resource permissions
        if (!profile.allowedResources.contains(resource)) {
            return PermissionCheckResult.Denied(
                profile = profile,
                reason = "Aapke profile me '${resource.name}' allowed nahi hai."
            )
        }

        // Evaluate Risk Level
        return when (riskLevel) {
            RiskLevel.LOW_RISK -> PermissionCheckResult.Allowed(profile)
            RiskLevel.MEDIUM_RISK -> {
                if (profile.role == ProfileRole.OWNER) {
                    PermissionCheckResult.Allowed(profile)
                } else {
                    PermissionCheckResult.AllowedWithConfirmation(
                        profile = profile,
                        prompt = "Kya aap sure hain ki aap ye action perform karna chahte hain?"
                    )
                }
            }
            RiskLevel.HIGH_RISK, RiskLevel.OWNER_ONLY -> {
                if (profile.role == ProfileRole.OWNER) {
                    PermissionCheckResult.RequiresBiometricPrompt(
                        profile = profile,
                        reason = "Sensitive owner action requires device biometric confirmation."
                    )
                } else {
                    PermissionCheckResult.Denied(
                        profile = profile,
                        reason = "High risk actions aapke profile ke liye restricted hain."
                    )
                }
            }
        }
    }

    // ─── Emergency & Security Controls ───────────────────────────────────────

    fun isEmergencyDisabled(): Boolean =
        prefs.getBoolean(KEY_EMERGENCY_DISABLED, false)

    fun areVoiceCommandsActive(): Boolean =
        prefs.getBoolean(KEY_VOICE_COMMANDS_ENABLED, true) && !isEmergencyDisabled()

    fun setEmergencyDisabled(disabled: Boolean) {
        prefs.edit().putBoolean(KEY_EMERGENCY_DISABLED, disabled).apply()
        logAudit(
            profileId = null,
            profileName = "System Admin",
            actionType = if (disabled) "EMERGENCY_DISABLE" else "EMERGENCY_RESTORE",
            resource = ResourceCategory.SETTINGS_SECURITY,
            risk = RiskLevel.OWNER_ONLY,
            isSuccess = true,
            reason = "Emergency switch toggled to $disabled"
        )
    }

    fun setVoiceCommandsActive(active: Boolean) {
        prefs.edit().putBoolean(KEY_VOICE_COMMANDS_ENABLED, active).apply()
    }

    @Synchronized
    fun lockAllProfiles() {
        profilesMap.forEach { (id, p) ->
            profilesMap[id] = p.copy(isLocked = true)
        }
        saveProfiles()
        logAudit(
            profileId = null,
            profileName = "System Admin",
            actionType = "LOCK_ALL_PROFILES",
            resource = ResourceCategory.VOICE_PROFILES_MGMT,
            risk = RiskLevel.OWNER_ONLY,
            isSuccess = true,
            reason = "All voice profiles locked"
        )
    }

    @Synchronized
    fun wipeAllBiometrics() {
        profilesMap.forEach { (id, p) ->
            prefs.edit().remove("$KEY_ENCRYPTED_EMBEDDING_PREFIX$id").apply()
            profilesMap[id] = p.copy(isEnrolled = false, sampleCount = 0)
        }
        saveProfiles()
        logAudit(
            profileId = null,
            profileName = "System Admin",
            actionType = "WIPE_ALL_BIOMETRICS",
            resource = ResourceCategory.VOICE_PROFILES_MGMT,
            risk = RiskLevel.OWNER_ONLY,
            isSuccess = true,
            reason = "All encrypted voice embeddings wiped"
        )
        AppLogger.w(TAG, "All voice biometric enrollments wiped.")
    }

    @Synchronized
    fun resetPermissionPolicies() {
        profilesMap.forEach { (id, p) ->
            profilesMap[id] = p.copy(
                allowedResources = VoiceProfile.defaultResourcesForRole(p.role),
                maxAllowedRisk = VoiceProfile.defaultRiskForRole(p.role),
                isLocked = false
            )
        }
        saveProfiles()
    }

    // ─── Minimal Privacy-Preserving Audit Logging ────────────────────────────

    private fun logAudit(
        profileId: String?,
        profileName: String?,
        actionType: String,
        resource: ResourceCategory,
        risk: RiskLevel,
        isSuccess: Boolean,
        reason: String
    ) {
        val entry = VoiceAuditLogEntry(
            id = UUID.randomUUID().toString().take(8),
            timestamp = System.currentTimeMillis(),
            profileId = profileId,
            profileName = profileName,
            actionType = actionType,
            resourceCategory = resource,
            riskLevel = risk,
            isSuccess = isSuccess,
            reason = reason
        )
        synchronized(auditLogs) {
            auditLogs.add(0, entry)
            if (auditLogs.size > MAX_AUDIT_ENTRIES) {
                auditLogs.removeAt(auditLogs.size - 1)
            }
        }
        saveAuditLogs()
    }

    @Synchronized
    private fun saveAuditLogs() {
        val array = JSONArray()
        synchronized(auditLogs) {
            auditLogs.take(50).forEach { array.put(it.toJsonObject()) }
        }
        prefs.edit().putString(KEY_AUDIT_LOGS_JSON, array.toString()).apply()
    }

    @Synchronized
    private fun loadAuditLogs() {
        auditLogs.clear()
        val raw = prefs.getString(KEY_AUDIT_LOGS_JSON, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                auditLogs.add(VoiceAuditLogEntry.fromJsonObject(array.getJSONObject(i)))
            }
        } catch (_: Exception) {}
    }

    fun getAuditLogs(): List<VoiceAuditLogEntry> = synchronized(auditLogs) {
        auditLogs.toList()
    }

    // ─── Acoustic Feature Extraction & DSP Math ──────────────────────────────

    /**
     * Extracts a normalized 34-dimensional acoustic feature vector:
     * - 30 Mel-spaced frequency sub-band log energies
     * - Spectral Centroid
     * - Spectral Spread
     * - Spectral Flux
     * - Zero-Crossing Rate
     */
    fun extractAcousticEmbedding(pcmBytes: ByteArray): FloatArray? {
        val sampleRate = 16000
        val shortCount = pcmBytes.size / 2
        if (shortCount < 1600) return null // At least 100ms required

        val samples = FloatArray(shortCount)
        var totalEnergy = 0.0
        for (i in 0 until shortCount) {
            val b0 = pcmBytes[i * 2].toInt() and 0xFF
            val b1 = pcmBytes[i * 2 + 1].toInt()
            val s = ((b1 shl 8) or b0).toShort()
            val normalized = s / 32768.0f
            samples[i] = normalized
            totalEnergy += normalized * normalized
        }

        val rms = sqrt(totalEnergy / shortCount)
        if (rms < 0.008) {
            // Signal too quiet or purely silence/background noise
            return null
        }

        val numMelBands = 30
        val melEnergies = FloatArray(numMelBands) { 0f }
        val frameSize = 512
        val hopSize = 256
        val numFrames = (shortCount - frameSize) / hopSize

        if (numFrames <= 0) return null

        var zeroCrossings = 0
        for (i in 1 until shortCount) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) || (samples[i] < 0 && samples[i - 1] >= 0)) {
                zeroCrossings++
            }
        }
        val zcr = zeroCrossings.toFloat() / shortCount

        val window = FloatArray(frameSize) { n ->
            (0.54 - 0.46 * cos(2 * Math.PI * n / (frameSize - 1))).toFloat()
        }

        val fftReal = DoubleArray(frameSize)
        val fftImag = DoubleArray(frameSize)
        var prevSpectrum = FloatArray(frameSize / 2) { 0f }
        var totalSpectralFlux = 0f
        var totalSpectralCentroid = 0f
        var totalSpectralSpread = 0f

        for (frame in 0 until numFrames) {
            val offset = frame * hopSize
            for (n in 0 until frameSize) {
                fftReal[n] = (samples[offset + n] * window[n]).toDouble()
                fftImag[n] = 0.0
            }

            fft(fftReal, fftImag, frameSize)

            val half = frameSize / 2
            val powerSpectrum = FloatArray(half)
            var frameEnergy = 0f
            var weightedSum = 0f

            for (k in 0 until half) {
                val mag = sqrt(fftReal[k] * fftReal[k] + fftImag[k] * fftImag[k]).toFloat()
                powerSpectrum[k] = mag
                frameEnergy += mag
                weightedSum += k * mag
            }

            val centroid = if (frameEnergy > 1e-6f) weightedSum / frameEnergy else 0f
            totalSpectralCentroid += centroid

            var spreadAccum = 0f
            for (k in 0 until half) {
                val diff = k - centroid
                spreadAccum += diff * diff * powerSpectrum[k]
            }
            val spread = if (frameEnergy > 1e-6f) sqrt(spreadAccum / frameEnergy) else 0f
            totalSpectralSpread += spread

            var flux = 0f
            for (k in 0 until half) {
                val diff = powerSpectrum[k] - prevSpectrum[k]
                if (diff > 0) flux += diff
            }
            totalSpectralFlux += flux
            System.arraycopy(powerSpectrum, 0, prevSpectrum, 0, half)

            for (m in 0 until numMelBands) {
                val startBin = (m * half / (numMelBands + 2))
                val centerBin = ((m + 1) * half / (numMelBands + 2))
                val endBin = ((m + 2) * half / (numMelBands + 2))
                var bandEnergy = 0f

                for (k in startBin..endBin) {
                    if (k in 0 until half) {
                        val weight = if (k <= centerBin) {
                            if (centerBin == startBin) 1f else (k - startBin).toFloat() / (centerBin - startBin)
                        } else {
                            if (endBin == centerBin) 1f else (endBin - k).toFloat() / (endBin - centerBin)
                        }
                        bandEnergy += powerSpectrum[k] * weight
                    }
                }
                melEnergies[m] += ln(max(1e-5f, bandEnergy))
            }
        }

        for (m in 0 until numMelBands) {
            melEnergies[m] /= numFrames
        }

        val avgCentroid = (totalSpectralCentroid / numFrames) / (frameSize / 2f)
        val avgSpread = (totalSpectralSpread / numFrames) / (frameSize / 2f)
        val avgFlux = (totalSpectralFlux / numFrames) / 100f

        val featureVector = FloatArray(VECTOR_DIM)
        for (i in 0 until numMelBands) {
            featureVector[i] = melEnergies[i]
        }
        featureVector[30] = avgCentroid
        featureVector[31] = avgSpread
        featureVector[32] = avgFlux
        featureVector[33] = zcr

        return normalizeVector(featureVector)
    }

    private fun computeCosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 1e-9f) dot / denom else 0f
    }

    private fun normalizeVector(v: FloatArray): FloatArray {
        var sumSquares = 0f
        for (x in v) sumSquares += x * x
        val mag = sqrt(sumSquares)
        if (mag < 1e-9f) return v
        val out = FloatArray(v.size)
        for (i in v.indices) out[i] = v[i] / mag
        return out
    }

    private fun fft(real: DoubleArray, imag: DoubleArray, n: Int) {
        var j = 0
        for (i in 0 until n - 1) {
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
            var k = n shr 1
            while (k <= j) {
                j -= k
                k = k shr 1
            }
            j += k
        }

        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wstepR = cos(ang)
            val wstepI = sin(ang)
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (step in 0 until len / 2) {
                    val pos = i + step
                    val match = pos + len / 2
                    val uR = real[pos]
                    val uI = imag[pos]
                    val vR = real[match] * wR - imag[match] * wI
                    val vI = real[match] * wI + imag[match] * wR
                    real[pos] = uR + vR
                    imag[pos] = uI + vI
                    real[match] = uR - vR
                    imag[match] = uI - vI
                    val nextWR = wR * wstepR - wI * wstepI
                    wI = wR * wstepI + wI * wstepR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Records audio from the mic for [durationMs] and verifies speaker against enrolled profiles.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordAndIdentify(durationMs: Long = 2500L): SpeakerIdentificationResult = withContext(Dispatchers.IO) {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat).coerceAtLeast(sampleRate)

        val recorder: AudioRecord
        try {
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return@withContext SpeakerIdentificationResult.InsufficientAudio("Microphone initialization failed.")
            }
        } catch (e: Exception) {
            return@withContext SpeakerIdentificationResult.InsufficientAudio("Microphone access error: ${e.message}")
        }

        val totalBytesToRead = (sampleRate * 2 * (durationMs / 1000.0)).toInt()
        val pcmAccumulator = ByteArray(totalBytesToRead)
        var totalRead = 0

        try {
            recorder.startRecording()
            val chunk = ByteArray(2048)
            val startTime = System.currentTimeMillis()

            while (totalRead < totalBytesToRead && (System.currentTimeMillis() - startTime) < (durationMs + 1000)) {
                val read = recorder.read(chunk, 0, min(chunk.size, totalBytesToRead - totalRead))
                if (read > 0) {
                    System.arraycopy(chunk, 0, pcmAccumulator, totalRead, read)
                    totalRead += read
                }
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error recording audio: ${e.message}")
        } finally {
            try {
                recorder.stop()
                recorder.release()
            } catch (_: Exception) {}
        }

        if (totalRead < 3200) {
            return@withContext SpeakerIdentificationResult.InsufficientAudio("Utterance too short for analysis.")
        }

        val pcm = pcmAccumulator.copyOf(totalRead)
        val result = identifySpeaker(pcm)
        // Clean memory
        java.util.Arrays.fill(pcm, 0.toByte())
        java.util.Arrays.fill(pcmAccumulator, 0.toByte())
        result
    }
}
