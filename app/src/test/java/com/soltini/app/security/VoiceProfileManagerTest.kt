package com.soltini.app.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoiceProfileManagerTest {

    private lateinit var context: Context
    private lateinit var profileManager: VoiceProfileManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        profileManager = VoiceProfileManager.getInstance(context)
        profileManager.resetPermissionPolicies()
        profileManager.setEmergencyDisabled(false)
        profileManager.setVoiceCommandsActive(true)
    }

    @Test
    fun testDefaultProfilesInitialization() {
        val profiles = profileManager.getAllProfiles()
        assertTrue("Expected at least Harshit and Mummy default profiles", profiles.size >= 2)

        val owner = profileManager.getOwnerProfile()
        assertNotNull("Owner profile must exist", owner)
        assertEquals("Harshit", owner?.name)
        assertEquals(ProfileRole.OWNER, owner?.role)

        val mummy = profileManager.getProfile(VoiceProfileManager.DEFAULT_FAMILY_ID)
        assertNotNull("Mummy profile must exist", mummy)
        assertEquals("Mummy", mummy?.name)
        assertEquals(ProfileRole.FAMILY, mummy?.role)
    }

    @Test
    fun testNewProfileDefaultsToRestrictedFamilyRole() {
        // Even if requested as OWNER, non-primary additions must default to FAMILY
        val created = profileManager.createProfile("Papa", "Father", ProfileRole.OWNER)
        assertEquals("Papa", created.name)
        assertEquals(ProfileRole.FAMILY, created.role)
        assertEquals(RiskLevel.MEDIUM_RISK, created.maxAllowedRisk)
        assertFalse(created.allowedResources.contains(ResourceCategory.PERSONAL_NOTES))
        assertFalse(created.allowedResources.contains(ResourceCategory.CALLS_SMS_READ))
        assertFalse(created.allowedResources.contains(ResourceCategory.PRIVATE_FILES_VAULT))
    }

    @Test
    fun testFamilyProfilePermissionRestrictions() {
        val mummy = profileManager.getProfile(VoiceProfileManager.DEFAULT_FAMILY_ID)
        assertNotNull(mummy)

        // 1. Normal assistant features: Allowed
        val weatherPerm = profileManager.checkPermission(mummy, ResourceCategory.GENERAL_ASSISTANT, RiskLevel.LOW_RISK)
        assertTrue("Mummy should be allowed general queries", weatherPerm is PermissionCheckResult.Allowed)

        val musicPerm = profileManager.checkPermission(mummy, ResourceCategory.MEDIA_PLAYBACK, RiskLevel.LOW_RISK)
        assertTrue("Mummy should be allowed media playback", musicPerm is PermissionCheckResult.Allowed)

        val calcPerm = profileManager.checkPermission(mummy, ResourceCategory.UTILITIES, RiskLevel.LOW_RISK)
        assertTrue("Mummy should be allowed calculator/utilities", calcPerm is PermissionCheckResult.Allowed)

        // 2. Medium risk actions: Require Confirmation
        val alarmPerm = profileManager.checkPermission(mummy, ResourceCategory.ALARMS_REMINDERS, RiskLevel.MEDIUM_RISK)
        assertTrue("Mummy should be allowed with confirmation for medium risk", alarmPerm is PermissionCheckResult.AllowedWithConfirmation)

        // 3. Owner Private Data: Strictly Denied & Privacy Protected
        val notesPerm = profileManager.checkPermission(mummy, ResourceCategory.PERSONAL_NOTES, RiskLevel.OWNER_ONLY)
        assertTrue("Mummy must be denied access to private notes", notesPerm is PermissionCheckResult.Denied)
        val notesDenied = notesPerm as PermissionCheckResult.Denied
        assertTrue(notesDenied.isPrivacyProtected)
        assertEquals("Ye personal information hai aur aapke profile ke liye available nahi hai.", notesDenied.reason)

        val messagesPerm = profileManager.checkPermission(mummy, ResourceCategory.CALLS_SMS_READ, RiskLevel.OWNER_ONLY)
        assertTrue("Mummy must be denied access to private messages", messagesPerm is PermissionCheckResult.Denied)
        val msgDenied = messagesPerm as PermissionCheckResult.Denied
        assertEquals("Ye personal information hai aur aapke profile ke liye available nahi hai.", msgDenied.reason)

        val vaultPerm = profileManager.checkPermission(mummy, ResourceCategory.PRIVATE_FILES_VAULT, RiskLevel.OWNER_ONLY)
        assertTrue("Mummy must be denied access to vault", vaultPerm is PermissionCheckResult.Denied)

        val profileMgmtPerm = profileManager.checkPermission(mummy, ResourceCategory.VOICE_PROFILES_MGMT, RiskLevel.OWNER_ONLY)
        assertTrue("Mummy cannot manage voice profiles", profileMgmtPerm is PermissionCheckResult.Denied)
    }

    @Test
    fun testOwnerPermissionMatrix() {
        val owner = profileManager.getOwnerProfile()
        assertNotNull(owner)

        // Low risk: Allowed
        val weatherPerm = profileManager.checkPermission(owner, ResourceCategory.GENERAL_ASSISTANT, RiskLevel.LOW_RISK)
        assertTrue(weatherPerm is PermissionCheckResult.Allowed)

        // Medium risk: Allowed for owner
        val alarmPerm = profileManager.checkPermission(owner, ResourceCategory.ALARMS_REMINDERS, RiskLevel.MEDIUM_RISK)
        assertTrue(alarmPerm is PermissionCheckResult.Allowed)

        // High Risk / Owner Only (e.g. Vault, Profile Management): Requires BiometricPrompt
        val vaultPerm = profileManager.checkPermission(owner, ResourceCategory.PRIVATE_FILES_VAULT, RiskLevel.OWNER_ONLY)
        assertTrue("Owner high risk/vault requires BiometricPrompt", vaultPerm is PermissionCheckResult.RequiresBiometricPrompt)

        // Financial payments: Always requires biometric prompt
        val paymentPerm = profileManager.checkPermission(owner, ResourceCategory.FINANCIAL_PAYMENTS, RiskLevel.OWNER_ONLY)
        assertTrue(paymentPerm is PermissionCheckResult.RequiresBiometricPrompt)
    }

    @Test
    fun testUnknownSpeakerRejection() {
        val unknownPerm = profileManager.checkPermission(null, ResourceCategory.GENERAL_ASSISTANT, RiskLevel.LOW_RISK)
        assertTrue(unknownPerm is PermissionCheckResult.Denied)
        val denied = unknownPerm as PermissionCheckResult.Denied
        assertEquals("Voice verify nahi hui. Password enter karein.", denied.reason)
    }

    @Test
    fun testEmergencyControls() {
        profileManager.setEmergencyDisabled(true)
        assertTrue(profileManager.isEmergencyDisabled())

        val owner = profileManager.getOwnerProfile()
        val perm = profileManager.checkPermission(owner, ResourceCategory.GENERAL_ASSISTANT, RiskLevel.LOW_RISK)
        assertTrue("When emergency is active, all requests must be denied", perm is PermissionCheckResult.Denied)

        // Restore
        profileManager.setEmergencyDisabled(false)
        assertFalse(profileManager.isEmergencyDisabled())
    }

    @Test
    fun testAuditLogRecording() {
        val logs = profileManager.getAuditLogs()
        assertNotNull(logs)
        // Verify no raw vectors or passwords in log representations
        logs.forEach { entry ->
            val json = entry.toJsonObject().toString()
            assertFalse("Audit log must not contain vector embeddings", json.contains("["))
            assertFalse("Audit log must not contain passwords", json.contains("password"))
        }
    }

    @Test
    fun testEnrollmentPhrasesCount() {
        assertEquals("Must provide at least 10 enrollment phrases", 10, VoiceProfileEnrollmentPhrases.PHRASES.size)
        VoiceProfileEnrollmentPhrases.PHRASES.forEach { phrase ->
            assertTrue(phrase.phrase.isNotBlank())
            assertTrue(phrase.language.isNotBlank())
            assertTrue(phrase.guidance.isNotBlank())
        }
    }
}
