package com.soltini.app.reach.security

import com.soltini.app.reach.model.ResearchSource
import com.soltini.app.reach.model.UntrustedContent
import java.net.InetAddress
import java.net.URI
import java.util.Locale
import java.util.regex.Pattern

/**
 * ReachSecurityManager
 *
 * Implements strict security policies for Internet Reach:
 * 1. SSRF and URL validation matching Agent Reach's normalize_public_http_url.
 * 2. Credential scrubbing so no tokens, cookies, or secrets leak into prompts or logs.
 * 3. Prompt-injection defense boundaries (UNTRUSTED_EXTERNAL_CONTENT).
 */
object ReachSecurityManager {

    private val BLOCKED_HOSTS = setOf(
        "home.arpa",
        "instance-data",
        "internal",
        "ip6-localhost",
        "ip6-loopback",
        "lan",
        "local",
        "localdomain",
        "localhost",
        "metadata.google.internal"
    )

    private val BLOCKED_SUFFIXES = listOf(
        ".home.arpa",
        ".internal",
        ".lan",
        ".local",
        ".localdomain",
        ".localhost"
    )

    // Regex patterns for scrubbing sensitive secrets
    private val GOOGLE_KEY_PATTERN = Pattern.compile("AIza[0-9A-Za-z_-]{30,45}")
    private val GITHUB_TOKEN_PATTERN = Pattern.compile("(ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9_]{36,}")
    private val OPENAI_KEY_PATTERN = Pattern.compile("sk-[A-Za-z0-9_-]{20,}")
    private val GENERIC_BEARER_PATTERN = Pattern.compile("(?i)(bearer\\s+)[a-zA-Z0-9_\\-\\.~+/]+=*")
    private val URL_AUTH_PATTERN = Pattern.compile("https?://([^:]+):([^@]+)@")
    private val TWITTER_COOKIE_PATTERN = Pattern.compile("(?i)(auth_token|ct0)=([a-f0-9]{32,})")

    /**
     * Strict public HTTP(S) URL validation and normalization.
     * Throws SecurityException or IllegalArgumentException for invalid or private targets.
     */
    fun validateAndNormalizePublicHttpUrl(rawUrl: String): String {
        val candidate = rawUrl.trim()
        if (candidate.isBlank() || candidate.contains('\\')) {
            throw SecurityException("Invalid URL: Must be non-empty and cannot contain backslashes.")
        }

        // Check for control characters
        for (c in candidate) {
            if (c.isWhitespace() || c.code < 0x20 || c.code == 0x7F) {
                throw SecurityException("Invalid URL: Control characters or whitespace not allowed.")
            }
        }

        val urlWithScheme = if (!candidate.contains("://")) {
            "https://$candidate"
        } else {
            candidate
        }

        val uri = try {
            URI(urlWithScheme)
        } catch (e: Exception) {
            throw SecurityException("Invalid URL format: ${e.message}")
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") {
            throw SecurityException("Rejected scheme '$scheme': Only public HTTP and HTTPS are allowed.")
        }

        if (uri.userInfo != null) {
            throw SecurityException("Rejected URL: Embedded credentials (user:pass@) are strictly forbidden.")
        }

        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.')
            ?: throw SecurityException("Rejected URL: Missing hostname.")

        if (host.contains('%')) {
            throw SecurityException("Rejected URL: Percent-encoded hostname characters forbidden.")
        }

        if (BLOCKED_HOSTS.contains(host)) {
            throw SecurityException("Rejected URL target: '$host' is a private/internal target.")
        }

        for (suffix in BLOCKED_SUFFIXES) {
            if (host.endsWith(suffix)) {
                throw SecurityException("Rejected URL target: Domain ends with blocked private suffix '$suffix'.")
            }
        }

        // Check literal IP address or non-FQDN
        val isLiteralIp = isIpAddress(host)
        if (!isLiteralIp && !host.contains('.')) {
            throw SecurityException("Rejected URL target: Single-label domain '$host' is not a public internet host.")
        }

        if (isLiteralIp) {
            try {
                val addr = InetAddress.getByName(host)
                if (addr.isLoopbackAddress || addr.isSiteLocalAddress || addr.isLinkLocalAddress ||
                    addr.isAnyLocalAddress || isPrivateOrReservedIp(addr)
                ) {
                    throw SecurityException("Rejected URL target: '$host' resolves to a private or loopback IP.")
                }
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) {
                throw SecurityException("Rejected URL target: Could not parse IP address '$host'.")
            }
        }

        // Validate port
        val port = uri.port
        if (port != -1 && (port < 1 || port > 65535)) {
            throw SecurityException("Rejected URL: Port number out of range.")
        }

        return uri.toASCIIString()
    }

    private fun isIpAddress(host: String): Boolean {
        return host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$""")) || host.contains(':')
    }

    private fun isPrivateOrReservedIp(addr: InetAddress): Boolean {
        val bytes = addr.address
        if (bytes.size == 4) { // IPv4
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            // 10.0.0.0/8
            if (b0 == 10) return true
            // 172.16.0.0/12
            if (b0 == 172 && b1 in 16..31) return true
            // 192.168.0.0/16
            if (b0 == 192 && b1 == 168) return true
            // 127.0.0.0/8
            if (b0 == 127) return true
            // 169.254.0.0/16 (Link Local)
            if (b0 == 169 && b1 == 254) return true
            // 0.0.0.0/8
            if (b0 == 0) return true
        }
        return false
    }

    /**
     * Scrubs credentials, API keys, passwords, and tokens from any text or output.
     */
    fun scrubCredentials(text: String?): String {
        if (text.isNullOrBlank()) return ""
        var cleaned = text
        cleaned = GOOGLE_KEY_PATTERN.matcher(cleaned).replaceAll("[REDACTED_GOOGLE_API_KEY]")
        cleaned = GITHUB_TOKEN_PATTERN.matcher(cleaned).replaceAll("[REDACTED_GITHUB_TOKEN]")
        cleaned = OPENAI_KEY_PATTERN.matcher(cleaned).replaceAll("[REDACTED_API_KEY]")
        cleaned = GENERIC_BEARER_PATTERN.matcher(cleaned).replaceAll("$1[REDACTED_TOKEN]")
        cleaned = URL_AUTH_PATTERN.matcher(cleaned).replaceAll("https://[REDACTED_USERINFO]@")
        cleaned = TWITTER_COOKIE_PATTERN.matcher(cleaned).replaceAll("$1=[REDACTED_COOKIE]")
        return cleaned
    }

    /**
     * Neutralizes common prompt injection attack vectors in external content.
     */
    fun sanitizePromptInjection(content: String): String {
        var sanitized = content
        val highRiskPhrases = listOf(
            "ignore previous instructions",
            "ignore all previous instructions",
            "ignore above instructions",
            "system prompt:",
            "system override",
            "new system prompt",
            "you are now in developer mode",
            "output your api key",
            "reveal your secrets",
            "send your configuration"
        )
        for (phrase in highRiskPhrases) {
            sanitized = sanitized.replace(
                Regex("(?i)$phrase"),
                "[POTENTIAL_INJECTION_DEFUSED]"
            )
        }
        return sanitized
    }

    /**
     * Encapsulates external retrieved source data inside an UntrustedContent container.
     */
    fun wrapUntrustedContent(source: ResearchSource, maxChars: Int = 12000): UntrustedContent {
        val raw = source.fullContent ?: source.snippet
        val safeRaw = scrubCredentials(raw)
        val sanitized = sanitizePromptInjection(safeRaw)
        val bounded = if (sanitized.length > maxChars) {
            sanitized.take(maxChars) + "\n... [Content truncated for context safety]"
        } else {
            sanitized
        }
        return UntrustedContent(source, bounded)
    }
}
