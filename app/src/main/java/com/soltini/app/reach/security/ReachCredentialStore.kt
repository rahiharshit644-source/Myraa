package com.soltini.app.reach.security

import android.content.Context
import android.content.SharedPreferences
import com.soltini.app.reach.model.AuthenticationState

/**
 * ReachCredentialStore
 *
 * Secure Android credential and configuration store for Agent Reach capabilities.
 * Strictly separates public configuration from sensitive secrets.
 * Sensitive values are never logged or leaked to the model.
 */
class ReachCredentialStore private constructor(context: Context) {

    companion object {
        private const val PREFS_SECRETS = "myra_reach_secure_credentials"
        private const val PREFS_CONFIG = "myra_reach_public_config"

        const val KEY_EXA_API_KEY = "exa_api_key"
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_GROQ_API_KEY = "groq_api_key"
        const val KEY_TWITTER_AUTH_TOKEN = "twitter_auth_token"
        const val KEY_TWITTER_CT0 = "twitter_ct0"
        const val KEY_REDDIT_CLIENT_ID = "reddit_client_id"
        const val KEY_REDDIT_CLIENT_SECRET = "reddit_client_secret"
        const val KEY_COMPANION_BRIDGE_URL = "companion_bridge_url"
        const val KEY_COMPANION_AUTH_TOKEN = "companion_auth_token"

        @Volatile
        private var instance: ReachCredentialStore? = null

        fun getInstance(context: Context): ReachCredentialStore =
            instance ?: synchronized(this) {
                instance ?: ReachCredentialStore(context.applicationContext).also { instance = it }
            }
    }

    private val securePrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_SECRETS, Context.MODE_PRIVATE)

    private val publicConfigPrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_CONFIG, Context.MODE_PRIVATE)

    fun saveSecret(key: String, value: String) {
        securePrefs.edit().putString(key, value.trim()).apply()
    }

    fun getSecret(key: String): String? {
        val s = securePrefs.getString(key, null)
        return if (s.isNullOrBlank()) null else s.trim()
    }

    fun hasSecret(key: String): Boolean = !getSecret(key).isNullOrBlank()

    fun removeSecret(key: String) {
        securePrefs.edit().remove(key).apply()
    }

    fun clearAllSecrets() {
        securePrefs.edit().clear().apply()
    }

    fun saveConfig(key: String, value: String) {
        publicConfigPrefs.edit().putString(key, value).apply()
    }

    fun getConfig(key: String, defaultValue: String = ""): String =
        publicConfigPrefs.getString(key, defaultValue) ?: defaultValue

    fun getCompanionBridgeUrl(): String =
        getConfig(KEY_COMPANION_BRIDGE_URL, "http://127.0.0.1:8765")

    fun setCompanionBridgeUrl(url: String) {
        saveConfig(KEY_COMPANION_BRIDGE_URL, url)
    }

    fun getAuthenticationState(capabilityId: String): AuthenticationState {
        return when (capabilityId) {
            "web.search" -> {
                if (hasSecret(KEY_EXA_API_KEY)) AuthenticationState.CONFIGURED
                else AuthenticationState.NOT_REQUIRED // DuckDuckGo / Jina work without key
            }
            "github.inspect" -> {
                if (hasSecret(KEY_GITHUB_TOKEN)) AuthenticationState.CONFIGURED
                else AuthenticationState.NOT_REQUIRED // Public GitHub REST works without token
            }
            "x.search" -> {
                if (hasSecret(KEY_TWITTER_AUTH_TOKEN) && hasSecret(KEY_TWITTER_CT0)) {
                    AuthenticationState.CONFIGURED
                } else {
                    AuthenticationState.NOT_CONFIGURED
                }
            }
            "reddit.discussions" -> {
                if (hasSecret(KEY_REDDIT_CLIENT_ID)) {
                    AuthenticationState.CONFIGURED
                } else {
                    AuthenticationState.NOT_REQUIRED // Public JSON works for public posts
                }
            }
            "youtube.content", "web.read", "rss.feed", "v2ex.topics", "bilibili.video" -> {
                AuthenticationState.NOT_REQUIRED
            }
            else -> AuthenticationState.NOT_CONFIGURED
        }
    }
}
