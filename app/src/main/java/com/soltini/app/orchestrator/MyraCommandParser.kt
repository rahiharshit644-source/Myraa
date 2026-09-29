package com.soltini.app.orchestrator

import android.util.Log
import com.soltini.app.scheduler.NaturalTimeParser
import com.soltini.app.scheduler.TimeParseResult

/**
 * Supported Messaging Platforms.
 */
enum class MessagePlatform {
    SMS,
    WHATSAPP,
    AUTO
}

/**
 * Parsed High-Level User Commands.
 */
sealed class ParsedAction {
    object CallInfoQuery : ParsedAction()
    object CallAnswer : ParsedAction()
    object CallReject : ParsedAction()

    data class MakeCall(
        val recipient: String
    ) : ParsedAction()

    data class ScheduledAction(
        val rawCommand: String,
        val targetTimeMs: Long? = null,
        val humanTimeDescription: String? = null,
        val taskType: String,
        val recipient: String? = null,
        val message: String? = null,
        val ambiguityQuestion: String? = null
    ) : ParsedAction()

    data class TaskManagement(
        val actionType: TaskActionType,
        val query: String = ""
    ) : ParsedAction()

    data class WebSearch(
        val cleanQuery: String,
        val originalQuery: String,
        val forceChrome: Boolean = false
    ) : ParsedAction()

    object OpenLastResultInChrome : ParsedAction()

    data class YouTubePlay(
        val cleanQuery: String,
        val isSong: Boolean
    ) : ParsedAction()

    data class YouTubeSearch(
        val cleanQuery: String
    ) : ParsedAction()

    data class SendMessage(
        val recipient: String,
        val messageText: String,
        val platform: MessagePlatform
    ) : ParsedAction()

    data class FillForm(
        val formType: String,
        val rawCommand: String,
        val fields: Map<String, String> = emptyMap(),
        val autoSubmit: Boolean = false
    ) : ParsedAction()

    data class SetBusyMode(
        val enable: Boolean,
        val reason: String? = null
    ) : ParsedAction()

    object BusyModeSummaryQuery : ParsedAction()

    data class InternetResearch(
        val rawCommand: String,
        val cleanQuery: String,
        val targetUrl: String? = null,
        val isDeepResearch: Boolean = false,
        val isDoctorCheck: Boolean = false,
        val isBackendQuery: Boolean = false,
        val targetPlatform: String? = null
    ) : ParsedAction()
}

enum class TaskActionType {
    LIST,
    CANCEL,
    RESCHEDULE
}

/**
 * MyraCommandParser
 *
 * Robust, deterministic natural language command parser for:
 * 1. Phone Call Handling ("Call receive karo", "Call reject karo", "Kaun call kar raha hai?")
 * 2. YouTube Playback vs Search ("YouTube par Arijit Singh ka gaana chalao" vs "YouTube par Python tutorial search karo")
 * 3. Web / Google Search ("Google par latest Android phone search karo")
 * 4. Messaging & SMS ("Rahul ko message bhejo ki main kal college nahi aaunga")
 */
object MyraCommandParser {

    private const val TAG = "MyraCommandParser"

    private fun logD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {}
    }

    /**
     * Attempts to parse [input] into a known [ParsedAction].
     * Returns null if input is general conversation or unrelated.
     */
    fun parse(input: String): ParsedAction? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // 1. Task Management ("Kaunse tasks scheduled hain?", "Cancel task", "Reschedule task")
        parseTaskManagement(trimmed)?.let { return it }

        // 2. Future / Scheduled Actions ("Shaam 7 baje Rahul ko call karna", "In 30 minutes...")
        parseScheduledAction(trimmed)?.let { return it }

        // 3. Phone Call Actions (Incoming answer/reject/query + Outgoing call)
        parseCallAction(trimmed)?.let { return it }

        // 4. Messaging & SMS
        parseSendMessage(trimmed)?.let { return it }

        // 5. Explicit Visual Chrome requests (e.g. "Jo result mila use Chrome me kholkar dikhao", "Chrome me search karo")
        parseChromeAction(trimmed)?.let { return it }

        // 6. Internet Reach & Research (Agent Reach Gateway: Web, GitHub, YouTube, Reddit, Doctor)
        parseInternetResearch(trimmed)?.let { return it }

        // 7. YouTube Control
        parseYouTube(trimmed)?.let { return it }

        // 8. Web / Google Search (Defaults to Agent Reach unless Chrome explicitly requested)
        parseWebSearch(trimmed)?.let { return it }

        // 7. Universal Form Filling ("Form fill karo", "Address form bharo", "Login details enter karo")
        parseFormAction(trimmed)?.let { return it }

        // 8. Autonomous Messaging / Busy Mode ("Myra me busy hun ab koi mssg aaye to tu handle kr lioo")
        parseBusyMode(trimmed)?.let { return it }

        return null
    }

    /**
     * Parses queries about scheduled tasks or task management actions.
     */
    fun parseTaskManagement(input: String): ParsedAction.TaskManagement? {
        val lower = input.lowercase().trim()
            .replace(Regex("""^(hey\s+myra|myra|soltini|please|kripya)\s*,?\s*"""), "")
            .trim()

        // List queries
        if (lower.contains("kaunse task") ||
            lower.contains("kaun se task") ||
            lower.contains("pending task") ||
            lower.contains("scheduled task") ||
            lower.contains("task list") ||
            lower.contains("show my tasks") ||
            lower.contains("what tasks are scheduled") ||
            lower.contains("next task") ||
            lower.contains("kya schedule hai") ||
            lower.contains("tasks dikhao") ||
            lower == "tasks" ||
            lower == "scheduled tasks"
        ) {
            return ParsedAction.TaskManagement(TaskActionType.LIST, query = lower)
        }

        // Cancel queries
        if (lower.contains("task cancel") ||
            lower.contains("cancel task") ||
            lower.contains("cancel scheduled") ||
            lower.contains("task hatao") ||
            lower.contains("task delete")
        ) {
            return ParsedAction.TaskManagement(TaskActionType.CANCEL, query = lower)
        }

        // Reschedule queries
        if (lower.contains("task reschedule") ||
            lower.contains("reschedule task") ||
            lower.contains("task ka time") ||
            lower.contains("task time change")
        ) {
            return ParsedAction.TaskManagement(TaskActionType.RESCHEDULE, query = lower)
        }

        return null
    }

    /**
     * Parses commands containing future time or scheduled execution requests.
     */
    fun parseScheduledAction(input: String): ParsedAction.ScheduledAction? {
        val lower = input.lowercase().trim()

        // Check if there are time markers or scheduling words
        val hasTimeKeywords = lower.contains("baje") ||
                lower.contains("minute baad") || lower.contains("min baad") ||
                lower.contains("ghante baad") || lower.contains("ghanta baad") ||
                lower.contains("hours later") || lower.contains("in ") ||
                lower.contains("kal ") || lower.contains("parso ") ||
                lower.contains("tomorrow") || lower.contains("aaj shaam") ||
                lower.contains("aaj raat") || lower.contains("schedule") ||
                lower.contains("remind me") || lower.contains("yaad dilana") ||
                lower.contains("yaad dilao")

        if (!hasTimeKeywords) return null

        when (val timeResult = NaturalTimeParser.parse(input)) {
            is TimeParseResult.Ambiguous -> {
                return ParsedAction.ScheduledAction(
                    rawCommand = input,
                    taskType = determineTaskType(input),
                    ambiguityQuestion = timeResult.clarificationQuestion
                )
            }
            is TimeParseResult.Success -> {
                val determinedType = determineTaskType(input)
                val recipient = extractCallRecipient(timeResult.cleanCommandWithoutTime)
                return ParsedAction.ScheduledAction(
                    rawCommand = input,
                    targetTimeMs = timeResult.timestampMs,
                    humanTimeDescription = timeResult.humanDescription,
                    taskType = determinedType,
                    recipient = recipient
                )
            }
            is TimeParseResult.NotScheduled -> return null
        }
    }

    private fun determineTaskType(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.contains("call") || lower.contains("phone lagao") || lower.contains("phone karo") -> "CALL"
            lower.contains("message") || lower.contains("msg") || lower.contains("sms") -> "SEND_MESSAGE"
            lower.contains("remind") || lower.contains("yaad") -> "REMINDER"
            lower.contains("chalao") || lower.contains("play") -> "PLAY_MEDIA"
            else -> "GENERIC_ACTION"
        }
    }

    private fun extractCallRecipient(input: String): String {
        // FIX: capture full multi-word names (letters + Devanagari + spaces only — no digits,
        // since digits here are almost always speech-to-text noise, not part of a real name).
        // Also strip trailing filler words (karo/kar do/lagao/etc.) that used to get swallowed
        // into a single-word capture before.
        val regex = Regex("""(?i)(?:call\s+([A-Za-z\u0900-\u097F][A-Za-z\u0900-\u097F ]*?)(?:\s+(?:ko|karo|kar\s*do|lagao|please)\b.*)?$|([A-Za-z\u0900-\u097F][A-Za-z\u0900-\u097F ]*?)\s*ko\s*(?:call|phone)(?:\s+karo|\s+kar\s*do|\s+lagao)?)""")
        val match = regex.find(input.trim())
        if (match != null) {
            var rec = match.groupValues[1].ifBlank { match.groupValues[2] }.trim()
            // Strip stray digits that leaked in from noisy STT (e.g. "Rahul7" -> "Rahul")
            rec = rec.replace(Regex("""\d+"""), "").trim()
            // Collapse repeated whitespace left behind after stripping digits
            rec = rec.replace(Regex("""\s+"""), " ")
            if (rec.isNotBlank() && rec.lowercase() !in listOf("shaam", "subah", "raat", "kal", "parso", "baje", "ko")) {
                return rec
            }
        }
        return ""
    }

    /**
     * Parses incoming call voice controls and caller queries.
     */
    fun parseCallAction(input: String): ParsedAction? {
        val lower = input.lowercase().trim()
            .replace(Regex("""^(hey\s+myra|myra|soltini|please|kripya)\s*,?\s*"""), "")
            .trim()

        // 1.A: Query Caller Identity
        if (lower.contains("kaun call kar raha hai") ||
            lower.contains("kiska phone aa raha hai") ||
            lower.contains("kiska call aa raha hai") ||
            lower.contains("kiska call hai") ||
            lower.contains("call kiska hai") ||
            lower.contains("call kiski hai") ||
            lower.contains("who is calling") ||
            lower.contains("who's calling") ||
            lower.contains("caller identity") ||
            lower.contains("call aa raha hai") ||
            lower.contains("phone aa raha hai") ||
            lower.contains("ka call aa raha") ||
            lower == "call status"
        ) {
            return ParsedAction.CallInfoQuery
        }

        // 1.B: Answer / Accept Call
        val isAnswer = lower.contains("call receive") ||
                lower.contains("call accept") ||
                lower.contains("call uthao") ||
                lower.contains("call utha lo") ||
                lower.contains("call pick") ||
                lower.contains("phone uthao") ||
                lower.contains("phone utha lo") ||
                lower.contains("phone receive") ||
                lower.contains("receive call") ||
                lower.contains("accept call") ||
                lower.contains("answer call") ||
                lower.contains("pick up the call") ||
                lower == "utha lo" ||
                lower == "pick up" ||
                lower == "answer"

        if (isAnswer) {
            return ParsedAction.CallAnswer
        }

        // 1.C: Reject / Cut Call
        val isReject = lower.contains("call reject") ||
                lower.contains("call cut") ||
                lower.contains("call kaat") ||
                lower.contains("call kat") ||
                lower.contains("phone cut") ||
                lower.contains("phone kaat") ||
                lower.contains("phone kat") ||
                lower.contains("reject call") ||
                lower.contains("cut call") ||
                lower.contains("decline call") ||
                lower.contains("end call") ||
                lower.contains("mat uthao") ||
                lower.contains("kaat do") ||
                lower.contains("cut kar do") ||
                lower == "reject" ||
                lower == "cut"

        if (isReject) {
            return ParsedAction.CallReject
        }

        // 1.D: Outgoing Call ("Rahul ko call karo", "Call Rahul", "Rahul ko phone lagao")
        val isOutgoingCall = (lower.contains("call karo") ||
                lower.contains("phone lagao") ||
                lower.contains("phone karo") ||
                lower.startsWith("call ")) &&
                !isAnswer && !isReject

        if (isOutgoingCall) {
            val recipient = extractCallRecipient(lower)
            if (recipient.isNotBlank()) {
                return ParsedAction.MakeCall(recipient)
            }
        }

        return null
    }

    /**
     * Parses message sending commands extracting clean recipient and message text.
     */
    fun parseSendMessage(input: String): ParsedAction.SendMessage? {
        val text = input.trim()
        val lower = text.lowercase()

        // Fast check if this is a messaging command
        val hasMessageKeywords = lower.contains("message") || lower.contains("msg") ||
                lower.contains("sms") || lower.contains("whatsapp") ||
                lower.contains("bol dena") || lower.contains("bol do") ||
                lower.contains("likho")

        if (!hasMessageKeywords) return null

        val isSms = lower.contains("sms") || lower.contains("text message")
        val isWhatsApp = lower.contains("whatsapp")
        val platform = when {
            isSms -> MessagePlatform.SMS
            isWhatsApp -> MessagePlatform.WHATSAPP
            else -> MessagePlatform.AUTO
        }

        // Pattern 1: [Prefix]? [Recipient] ko [optional platform/message] [bhejo/karo/bol do] [ki / :] [Message]
        // Examples:
        // "Rahul ko message bhejo ki main kal college nahi aaunga"
        // "Mummy ko message bhejo ki main 10 minute late aaunga"
        // "Rahul ko SMS karo: kal milte hain"
        // "Papa ko message bhejo main ghar aa raha hoon"
        // "Rohit ko WhatsApp par bol dena ki main 5 baje aaunga"
        val pat1 = Regex(
            """^(?:hey\s+myra|myra|soltini|please|kripya)?\s*(.+?)\s+ko\s+(?:whatsapp\s*(?:par|pe|me|mein)?\s*)?(?:message|msg|sms|text)?\s*(?:bhejo|bhej do|karo|kar do|send karo|likho|bolo|bol dena|bol do)\s*(?:ki|:|,|-|\s+ki\s+)?\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        pat1.matchEntire(text)?.let { m ->
            val rec = cleanRecipient(m.groupValues[1])
            val msg = cleanMessageBody(m.groupValues[2])
            if (rec.isNotBlank() && msg.isNotBlank()) {
                logD(TAG, "Parsed SendMessage (pat1): Recipient='$rec', Message='$msg', Platform=$platform")
                return ParsedAction.SendMessage(rec, msg, platform)
            }
        }

        // Pattern 2: [Prefix]? [ek] [message/sms/whatsapp] bhejo [recipient] ko [ki / :] [Message]
        val pat2 = Regex(
            """^(?:hey\s+myra|myra|soltini|please|kripya)?\s*(?:ek\s+)?(?:message|msg|sms|whatsapp)\s*(?:bhejo|bhej do|karo|kar do|send karo)?\s*(?:to|ko)?\s*(.+?)\s+ko\s*(?:ki|:|,|-)?\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        pat2.matchEntire(text)?.let { m ->
            val rec = cleanRecipient(m.groupValues[1])
            val msg = cleanMessageBody(m.groupValues[2])
            if (rec.isNotBlank() && msg.isNotBlank()) {
                logD(TAG, "Parsed SendMessage (pat2): Recipient='$rec', Message='$msg', Platform=$platform")
                return ParsedAction.SendMessage(rec, msg, platform)
            }
        }

        // Pattern 3: English: send [a] [message/sms/text] to [recipient] saying/that/: [message]
        val pat3 = Regex(
            """^(?:hey\s+myra|myra|soltini|please)?\s*send\s+(?:a\s+)?(?:message|sms|text|whatsapp)\s+to\s+(.+?)\s+(?:saying|that|:|,)\s+(.+)$""",
            RegexOption.IGNORE_CASE
        )
        pat3.matchEntire(text)?.let { m ->
            val rec = cleanRecipient(m.groupValues[1])
            val msg = cleanMessageBody(m.groupValues[2])
            if (rec.isNotBlank() && msg.isNotBlank()) {
                logD(TAG, "Parsed SendMessage (pat3): Recipient='$rec', Message='$msg', Platform=$platform")
                return ParsedAction.SendMessage(rec, msg, platform)
            }
        }

        // Pattern 4: Direct: [Recipient] ko bolo ki [Message]
        val pat4 = Regex(
            """^(?:hey\s+myra|myra|soltini|please)?\s*(.+?)\s+ko\s+(?:bolo|bolna|bol dena|bol do|kaho)\s*(?:ki|:|,)?\s*(.+)$""",
            RegexOption.IGNORE_CASE
        )
        pat4.matchEntire(text)?.let { m ->
            val rec = cleanRecipient(m.groupValues[1])
            val msg = cleanMessageBody(m.groupValues[2])
            if (rec.isNotBlank() && msg.isNotBlank()) {
                logD(TAG, "Parsed SendMessage (pat4): Recipient='$rec', Message='$msg', Platform=$platform")
                return ParsedAction.SendMessage(rec, msg, platform)
            }
        }

        return null
    }

    /**
     * Parses YouTube commands into either playback or search with clean queries.
     */
    fun parseYouTube(input: String): ParsedAction? {
        val text = input.trim()
        val lower = text.lowercase()

        val mentionsYouTube = lower.contains("youtube")
        val mentionsPlay = lower.contains("chalao") || lower.contains("chala do") ||
                lower.contains("bajao") || lower.contains("baja do") ||
                lower.contains("play") || lower.contains("sunao") || lower.contains("suna do")
        val mentionsSearch = lower.contains("search") || lower.contains("dhoondo") ||
                lower.contains("khojo") || lower.contains("dhoondho")

        val hasMusicKeyword = lower.contains("gaana") || lower.contains("gaane") ||
                lower.contains("geet") || lower.contains("song") || lower.contains("songs") ||
                lower.contains("video") || lower.contains("music") || lower.contains("track") ||
                lower.contains("bhajan") || lower.contains("chalisa") || lower.contains("aarti")

        if (!mentionsYouTube && !(mentionsPlay && hasMusicKeyword)) {
            return null
        }

        // Distinguish play vs search:
        val isSearch = mentionsSearch && !lower.contains("chalao") && !lower.contains("bajao") && !lower.contains("play")
        val isSong = hasMusicKeyword && !lower.contains("video")

        val cleanQuery = cleanYouTubeQuery(text)
        if (cleanQuery.isBlank()) return null

        return if (isSearch) {
            logD(TAG, "Parsed YouTubeSearch: Query='$cleanQuery'")
            ParsedAction.YouTubeSearch(cleanQuery)
        } else {
            logD(TAG, "Parsed YouTubePlay: Query='$cleanQuery', isSong=$isSong")
            ParsedAction.YouTubePlay(cleanQuery, isSong)
        }
    }

    /**
     * Parses Google / Web search commands and extracts purely the clean search query.
     */
    fun parseWebSearch(input: String): ParsedAction.WebSearch? {
        val text = input.trim()
        val lower = text.lowercase()

        // Exclude file, document, or storage search requests unless explicitly targeting the web/google
        val isFileQuery = lower.contains("file") || lower.contains("files") ||
                lower.contains("folder") || lower.contains("storage") ||
                lower.contains("saf") || lower.contains("document") ||
                lower.contains("pdf") || lower.contains("download")

        val hasGoogle = lower.contains("google") || lower.contains("chrome") ||
                lower.contains("web") || lower.contains("internet") || lower.contains("online")

        if (isFileQuery && !hasGoogle) return null

        val isSearchCommand = lower.contains("search") || lower.contains("dhoondo") ||
                lower.contains("dhoondho") || lower.contains("khojo") ||
                hasGoogle

        if (!isSearchCommand) return null

        val hasAction = lower.contains("search") || lower.contains("dhoondo") || lower.contains("dhoondho") || lower.contains("khojo")

        if (!hasGoogle && !hasAction) return null

        val cleanQuery = cleanWebSearchQuery(text)
        if (cleanQuery.isBlank()) return null

        val forceChrome = lower.contains("chrome") ||
                lower.contains("google chrome") ||
                lower.contains("browser me") ||
                lower.contains("browser mein")

        logD(TAG, "Parsed WebSearch: Query='$cleanQuery' (forceChrome=$forceChrome) from '$text'")
        return ParsedAction.WebSearch(cleanQuery = cleanQuery, originalQuery = text, forceChrome = forceChrome)
    }

    /**
     * Parses explicit Chrome browser actions:
     * 1. Open last research result in Chrome ("Jo result mila use Chrome me dikhao")
     * 2. Direct Chrome searches ("Chrome me search karo...")
     */
    fun parseChromeAction(input: String): ParsedAction? {
        val text = input.trim()
        val lower = text.lowercase()

        val isChromeExplicit = lower.contains("chrome") || lower.contains("browser")

        // 1. Show last research result in Chrome
        val isShowLastResult = isChromeExplicit &&
                (lower.contains("result") || lower.contains("jo mila") || lower.contains("pichla") ||
                 lower.contains("kholkar dikhao") || lower.contains("khol ke dikhao") ||
                 (lower.contains("dikhao") && !lower.contains("search")))

        if (isShowLastResult) {
            logD(TAG, "Parsed OpenLastResultInChrome from '$text'")
            return ParsedAction.OpenLastResultInChrome
        }

        // 2. Explicit Chrome search
        val isExplicitChromeSearch = lower.contains("chrome par search") ||
                lower.contains("chrome me search") ||
                lower.contains("chrome mein search") ||
                lower.contains("google chrome me") ||
                lower.contains("google chrome mein") ||
                lower.contains("browser me search") ||
                lower.contains("browser mein search")

        if (isExplicitChromeSearch) {
            val q = cleanWebSearchQuery(text)
            if (q.isNotBlank()) {
                logD(TAG, "Parsed WebSearch (forceChrome=true): '$q'")
                return ParsedAction.WebSearch(cleanQuery = q, originalQuery = text, forceChrome = true)
            }
        }

        return null
    }

    // ─── Query & Entity Cleaners ──────────────────────────────────────────────

    fun cleanRecipient(raw: String): String {
        var rec = raw.trim()
        val m = Regex("""^(?:hey\s+myra|myra|soltini|please|kripya)?\s*(?:ek\s+message\s+(?:bhejo\s+)?to\s+|send\s+(?:a\s+)?(?:message|sms|whatsapp)\s+to\s+)?(.+?)\s+ko\b""", RegexOption.IGNORE_CASE).find(rec)
        if (m != null) {
            rec = m.groupValues[1]
        }
        return rec
            .replace(Regex("""^(?:hey\s+myra|myra|soltini|please|kripya)\s*,?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""^(?:send\s+(?:a\s+)?(?:message|sms|whatsapp)\s+to)\s+""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:ko|se|par|pe)$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    fun cleanMessageBody(raw: String): String {
        var msg = raw.trim()

        // If the entire command was passed as message body, extract just the message text after ki/:
        val m = Regex("""^(?:hey\s+myra|myra|soltini|please|kripya)?\s*.+?\s+ko\s+.*?(?:ki|:|,|-|\bthat\b|\bsaying\b)\s*(.+)$""", RegexOption.IGNORE_CASE).find(msg)
        if (m != null) {
            msg = m.groupValues[1]
        }

        var prev: String
        do {
            prev = msg
            msg = msg
                .replace(Regex("""^(?:ki|ke|that|saying|:|,|-)\s*""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^(?:ye|yeh)\s+message\s+(?:ki\s+)?""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""^(?:ki|ke|that)\s+""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+(?:bhejo|bhej do|send karo|send kar do|likho|kar dena)$""", RegexOption.IGNORE_CASE), "")
                .trim()
        } while (msg != prev && msg.isNotBlank())

        return msg.trim()
    }

    fun cleanYouTubeQuery(raw: String): String {
        var query = raw.trim()

        var prev: String
        do {
            prev = query
            query = query.replace(Regex("""^(?:hey\s+myra|myra|soltini|please|kripya)\s*,?\s*""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""^(?:youtube\s*(?:par|pe|me|mein|pr)?|on\s+youtube|in\s+youtube)\s*""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""^(?:play|search|chalao|bajao|sunao|open|kholo|dhoondo|khojo)\s*""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""^(?:youtube\s*(?:par|pe|me|mein|pr)?|on\s+youtube|in\s+youtube)\s*""", RegexOption.IGNORE_CASE), "")

            query = query.replace(Regex("""\s+(?:chalao|chala\s+do|bajao|baja\s+do|sunao|suna\s+do|play\s+karo|play|search\s+karo|search|dhoondo|dhoondho|khojo|dikhao|kholo)$""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""\s+(?:youtube\s*(?:par|pe|me|mein|pr)?|on\s+youtube|in\s+youtube)$""", RegexOption.IGNORE_CASE), "")
            query = query.trim()
        } while (query != prev && query.isNotBlank())

        query = query.replace(Regex("""\s+(?:ka\s+gaana|ke\s+gaane|ka\s+song|ke\s+songs|ka\s+video|ke\s+video|song|songs|video|tracks)$""", RegexOption.IGNORE_CASE), "")

        return query.trim()
    }

    fun cleanWebSearchQuery(raw: String): String {
        var query = raw.trim()

        var prev: String
        do {
            prev = query
            query = query.replace(Regex("""^(?:hey\s+myra|myra|soltini|please|kripya)\s*,?\s*""", RegexOption.IGNORE_CASE), "")

            query = query.replace(Regex("""^(?:google\s*(?:par|pe|me|mein|pr)?|chrome\s*(?:par|pe|me|mein)?|web\s*(?:par|pe)?|internet\s*(?:par|pe)?)\s*""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""^(?:search|dhoondo|dhoondho|khojo|find|look\s+up)\s*(?:karo|kar\s+do|kijiye)?\s*(?:ki|for|about)?\s*""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""^(?:google\s*(?:par|pe|me|mein|pr)?|on\s+google|in\s+google)\s*""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""^(?:search|dhoondo|dhoondho|khojo|find|look\s+up)\s*(?:karo|kar\s+do|kijiye)?\s*(?:ki|for|about)?\s*""", RegexOption.IGNORE_CASE), "")

            query = query.replace(Regex("""\s+(?:google\s*(?:par|pe|me|mein|pr)?\s*)?(?:search\s*(?:karo|kar\s+do|kijiye)|dhoondo|dhoondho|khojo|find\s*karo|dekho)$""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""\s+(?:on\s+google|in\s+google|via\s+google)$""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""\s+(?:google\s*(?:par|pe|me|mein|pr)?)$""", RegexOption.IGNORE_CASE), "")
            query = query.replace(Regex("""\s+(?:search|dhoondo|dhoondho|khojo)$""", RegexOption.IGNORE_CASE), "")
            query = query.trim()
        } while (query != prev && query.isNotBlank())

        return query.trim()
    }

    /**
     * Parses Universal Form Filling commands in Hindi & English:
     * Examples:
     * - "Form fill karo" / "Form bharo" / "Fill the form"
     * - "Address form bharo" / "Address enter karo" / "Enter my address"
     * - "Login details bharo" / "Fill login details"
     * - "Signup form complete karo" / "Register form bharo"
     * - "Form me naam Rahul aur phone 9876543210 bhar do"
     */
    fun parseFormAction(input: String): ParsedAction.FillForm? {
        val lower = input.lowercase().trim()
            .replace(Regex("""^(?:hey\s+myra|myra|soltini|please|kripya)\s*,?\s*"""), "")
            .trim()

        val isFormKeyword = lower.contains("form") ||
                lower.contains("bharo") ||
                lower.contains("bhar do") ||
                lower.contains("bhar ke") ||
                lower.contains("fill") ||
                lower.contains("details enter") ||
                lower.contains("details bhar") ||
                lower.contains("details fill")

        if (!isFormKeyword) return null

        val matchesFormIntent = (lower.contains("form") && (lower.contains("bhar") || lower.contains("fill") || lower.contains("submit") || lower.contains("complete"))) ||
                (lower.contains("address") && (lower.contains("bhar") || lower.contains("fill") || lower.contains("enter"))) ||
                (lower.contains("login") && (lower.contains("details") || lower.contains("bhar") || lower.contains("fill") || lower.contains("enter"))) ||
                (lower.contains("signup") && (lower.contains("form") || lower.contains("details") || lower.contains("complete") || lower.contains("bhar") || lower.contains("fill"))) ||
                (lower.contains("register") && (lower.contains("form") || lower.contains("details") || lower.contains("complete") || lower.contains("bhar"))) ||
                (lower.contains("profile") && (lower.contains("bhar") || lower.contains("fill") || lower.contains("details"))) ||
                Regex(
                    """\b(?:form\s*(?:fill|bharo|bhar|complete)|(?:fill|bharo|bhar|complete)\s*(?:the|this|ye|yeh)?\s*form|address\s*(?:bharo|enter|fill)|login\s*(?:details|form)|signup\s*(?:form|details)|register\s*(?:form|details)|details\s*(?:bharo|fill|enter))\b""",
                    RegexOption.IGNORE_CASE
                ).containsMatchIn(lower)

        if (!matchesFormIntent) return null

        val formType = when {
            lower.contains("address") || lower.contains("pata") -> "address"
            lower.contains("login") || lower.contains("sign in") -> "login"
            lower.contains("signup") || lower.contains("sign up") || lower.contains("register") || lower.contains("registration") -> "signup"
            lower.contains("profile") -> "profile"
            else -> "general"
        }

        val autoSubmit = lower.contains("submit") || lower.contains("jama kar")

        val extractedFields = mutableMapOf<String, String>()

        // Name
        val nameMatch = Regex("""(?:naam|name)\s+(?:hai|is\s+)?([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE).find(input)
        if (nameMatch != null) extractedFields["name"] = nameMatch.groupValues[1]

        // Email
        val emailMatch = Regex("""\b([A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,})\b""").find(input)
        if (emailMatch != null) extractedFields["email"] = emailMatch.groupValues[1]

        // Phone
        val phoneMatch = Regex("""\b(\d{10})\b""").find(input)
        if (phoneMatch != null) extractedFields["phone"] = phoneMatch.groupValues[1]

        // City
        val cityMatch = Regex("""(?:city|shehar)\s+(?:hai|is\s+)?([A-Za-z]+)""", RegexOption.IGNORE_CASE).find(input)
        if (cityMatch != null) extractedFields["city"] = cityMatch.groupValues[1]

        // Pincode / Zip
        val pinMatch = Regex("""(?:pincode|pin\s*code|pin|zip)\s+(?:hai|is\s+)?(\d{6})""", RegexOption.IGNORE_CASE).find(input)
        if (pinMatch != null) extractedFields["pincode"] = pinMatch.groupValues[1]

        return ParsedAction.FillForm(
            formType = formType,
            rawCommand = input,
            fields = extractedFields,
            autoSubmit = autoSubmit
        )
    }

    /**
     * Parses commands related to Busy Mode / Autonomous message handling.
     */
    fun parseBusyMode(input: String): ParsedAction? {
        val lower = input.lowercase().trim()
            .replace(Regex("""^(hey\s+myra|myra|soltini|please|kripya)\s*,?\s*"""), "")
            .trim()

        // 1. Summary Query
        if (lower.contains("busy mode summary") ||
            lower.contains("busy mode history") ||
            lower.contains("kya reply kiya") ||
            lower.contains("kya mssg reply kiya") ||
            lower.contains("auto reply summary") ||
            lower.contains("kisko reply kiya") ||
            lower.contains("auto reply log")
        ) {
            return ParsedAction.BusyModeSummaryQuery
        }

        // 2. Deactivation / "Free ho gaya"
        val isDeactivation = lower.contains("free ho gaya") ||
                lower.contains("free ho gyi") ||
                lower.contains("free hoon") ||
                lower.contains("free hu") ||
                lower.contains("free hun") ||
                lower.contains("ab free") ||
                lower.contains("i am free") ||
                lower.contains("busy mode off") ||
                lower.contains("busy mode band") ||
                lower.contains("busy mode deactivate") ||
                lower.contains("auto reply band") ||
                lower.contains("auto reply off") ||
                lower.contains("stop auto reply")

        if (isDeactivation) {
            return ParsedAction.SetBusyMode(enable = false)
        }

        // 3. Activation / "Busy hoon"
        val hasBusyPhrase = lower.contains("busy hun") ||
                lower.contains("busy hu") ||
                lower.contains("busy hoon") ||
                lower.contains("busy mode") ||
                lower.contains("auto reply") ||
                lower.contains("tu handle kr lio") ||
                lower.contains("tu handle kar lena") ||
                lower.contains("tu handle kar lio") ||
                lower.contains("tu handle kar") ||
                lower.contains("messages handle") ||
                lower.contains("message handle") ||
                lower.contains("mssg handle") ||
                lower.contains("mssg aaye to tu") ||
                lower.contains("message aaye to tu") ||
                lower.contains("har ek mssg ka reply") ||
                lower.contains("har ek message ka reply") ||
                lower.contains("har message ka reply") ||
                lower.contains("har mssg ka reply") ||
                lower.contains("messages ka reply kar dena") ||
                lower.contains("koi message aaye to reply") ||
                (lower.contains("meeting me") && (lower.contains("reply") || lower.contains("message") || lower.contains("handle"))) ||
                (lower.contains("driving") && (lower.contains("reply") || lower.contains("message") || lower.contains("handle")))

        if (hasBusyPhrase) {
            val reason = extractBusyReason(lower)
            return ParsedAction.SetBusyMode(enable = true, reason = reason)
        }

        return null
    }

    private fun extractBusyReason(lower: String): String {
        return when {
            lower.contains("drive") || lower.contains("driving") -> "driving"
            lower.contains("meeting") -> "meeting"
            lower.contains("study") || lower.contains("padh") -> "padhai"
            lower.contains("gym") || lower.contains("workout") -> "gym"
            lower.contains("so raha") || lower.contains("sleep") || lower.contains("aaram") -> "aaram"
            lower.contains("office") -> "office work"
            else -> "busy"
        }
    }

    /**
     * Parses Agent Reach Internet commands:
     * 1. Doctor / Capabilities status ("Internet capabilities ka status batao", "Doctor check")
     * 2. Active backend query ("Kaunsa backend currently use ho raha hai?")
     * 3. Web URL Reading ("Is URL ko read karo", "Is article ka summary batao")
     * 4. YouTube Video Summarization ("Is YouTube video ka summary batao")
     * 5. GitHub Repository Analysis ("Is GitHub repo ka architecture explain karo")
     * 6. Reddit Discussion Search ("Reddit par is topic ke public discussions dekho")
     * 7. Multi-Source Deep Research ("Deep research karo AI agents par")
     * 8. Direct Web Search ("Search the web for...", "Web par search karo...")
     */
    fun parseInternetResearch(input: String): ParsedAction.InternetResearch? {
        val text = input.trim()
        val lower = text.lowercase()

        // 1. Doctor / Capability status check
        if (lower.contains("internet capabilities") ||
            lower.contains("internet capability") ||
            lower.contains("reach doctor") ||
            lower.contains("internet status") ||
            lower.contains("capabilities ka status") ||
            lower.contains("doctor status") ||
            (lower.contains("doctor") && lower.contains("check")) ||
            (lower.contains("reach") && lower.contains("status"))
        ) {
            logD(TAG, "Parsed InternetResearch (DoctorCheck)")
            return ParsedAction.InternetResearch(
                rawCommand = text,
                cleanQuery = "",
                isDoctorCheck = true
            )
        }

        // 2. Active backend query
        if (lower.contains("kaunsa backend") ||
            lower.contains("kaun sa backend") ||
            lower.contains("which backend") ||
            lower.contains("current backend") ||
            lower.contains("active backend")
        ) {
            logD(TAG, "Parsed InternetResearch (BackendQuery)")
            return ParsedAction.InternetResearch(
                rawCommand = text,
                cleanQuery = "",
                isBackendQuery = true
            )
        }

        // 3. Check for embedded URL
        val urlRegex = Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE)
        val urlMatch = urlRegex.find(text)
        if (urlMatch != null) {
            val url = urlMatch.value
            val isYt = url.contains("youtube.com") || url.contains("youtu.be")
            val isGh = url.contains("github.com")
            val isReddit = url.contains("reddit.com")

            val platform = when {
                isYt -> "YOUTUBE"
                isGh -> "GITHUB"
                isReddit -> "REDDIT"
                else -> "WEB_READER"
            }

            logD(TAG, "Parsed InternetResearch (URL Read): url=$url, platform=$platform")
            return ParsedAction.InternetResearch(
                rawCommand = text,
                cleanQuery = text,
                targetUrl = url,
                targetPlatform = platform
            )
        }

        // 4. YouTube Video Summarization (Must be summary/explain, NOT song playback)
        val isYtSummary = (lower.contains("youtube") || lower.contains("video")) &&
                (lower.contains("summary") || lower.contains("summarize") || lower.contains("explain") ||
                        lower.contains("transcript") || lower.contains("samjhao") || lower.contains("points") ||
                        lower.contains("kya bol raha") || lower.contains("kya hai is video")) &&
                !lower.contains("chalao") && !lower.contains("bajao") && !lower.contains("play")

        if (isYtSummary) {
            val q = cleanResearchQuery(text)
            logD(TAG, "Parsed InternetResearch (YouTube Summary): $q")
            return ParsedAction.InternetResearch(
                rawCommand = text,
                cleanQuery = q,
                targetPlatform = "YOUTUBE"
            )
        }

        // 5. GitHub Repository Analysis
        val hasGhWord = lower.contains("github") ||
                Regex("""\b(git\s+repo|repository|repositories|repo)\b""").containsMatchIn(lower)
        val isFileQuery = lower.contains("in files") || lower.contains("in storage") ||
                lower.contains("in downloads") || lower.contains("in device") || lower.contains("in my files")
        val isGhAnalysis = hasGhWord && !isFileQuery &&
                (lower.contains("analyze") || lower.contains("explain") || lower.contains("samjhao") ||
                        lower.contains("architecture") || lower.contains("inspect") || lower.contains("code") ||
                        lower.contains("batao") || lower.contains("search"))

        if (isGhAnalysis) {
            val q = cleanResearchQuery(text)
            logD(TAG, "Parsed InternetResearch (GitHub Analysis): $q")
            return ParsedAction.InternetResearch(
                rawCommand = text,
                cleanQuery = q,
                targetPlatform = "GITHUB"
            )
        }

        // 6. Reddit Discussions Search
        val isRedditSearch = lower.contains("reddit") &&
                (lower.contains("search") || lower.contains("discussion") || lower.contains("public") ||
                        lower.contains("dekho") || lower.contains("kya bol") || lower.contains("opinions") ||
                        lower.contains("summary") || lower.contains("batao"))

        if (isRedditSearch) {
            val q = cleanResearchQuery(text)
            logD(TAG, "Parsed InternetResearch (Reddit Search): $q")
            return ParsedAction.InternetResearch(
                rawCommand = text,
                cleanQuery = q,
                targetPlatform = "REDDIT"
            )
        }

        // 7. Explicit Deep Research / Multi-Source Investigation
        val isDeep = lower.contains("deep research") ||
                lower.contains("multiple sources") ||
                lower.contains("cross check") ||
                lower.contains("cross-check") ||
                lower.contains("compare sources") ||
                lower.contains("comprehensive research") ||
                (lower.contains("research") && (lower.contains("across") || lower.contains("deep") || lower.contains("detailed")))

        if (isDeep) {
            val q = cleanResearchQuery(text)
            logD(TAG, "Parsed InternetResearch (Deep Research): $q")
            return ParsedAction.InternetResearch(
                rawCommand = text,
                cleanQuery = q,
                isDeepResearch = true
            )
        }

        // 8. Explicit Web Search / Research ("Search the web for...", "Web par search karo...", "Research on...")
        val isExplicitWebSearch = (lower.contains("search the web") ||
                lower.contains("web par search") ||
                lower.contains("internet par search") ||
                lower.contains("internet se search") ||
                lower.contains("online search") ||
                lower.contains("online research") ||
                lower.contains("research karo") ||
                lower.contains("research on") ||
                lower.contains("research kar do") ||
                lower.contains("search this on the internet") ||
                lower.contains("search on the internet") ||
                lower.contains("search on internet") ||
                (lower.contains("search") && lower.contains("internet")))

        if (isExplicitWebSearch) {
            val q = cleanResearchQuery(text)
            if (q.isNotBlank()) {
                logD(TAG, "Parsed InternetResearch (Web Search): $q")
                return ParsedAction.InternetResearch(
                    rawCommand = text,
                    cleanQuery = q,
                    targetPlatform = "WEB"
                )
            }
        }

        // 9. Verification & Fact-checking ("Web se verify karo", "Internet se check karo", "Online check karo")
        val isVerification = lower.contains("verify karo") || lower.contains("check karo") ||
                lower.contains("verify kar") || lower.contains("fact check") ||
                (lower.contains("internet se") && (lower.contains("check") || lower.contains("dekh") || lower.contains("batao") || lower.contains("verify"))) ||
                (lower.contains("web se") && (lower.contains("verify") || lower.contains("check") || lower.contains("dekh")))

        if (isVerification) {
            val q = cleanResearchQuery(text)
            if (q.isNotBlank()) {
                logD(TAG, "Parsed InternetResearch (Verification): $q")
                return ParsedAction.InternetResearch(
                    rawCommand = text,
                    cleanQuery = q,
                    targetPlatform = "WEB"
                )
            }
        }

        // 10. Information discovery & Latest information ("Latest information nikaalo", "AI tools dhoondo", "Robotics projects dhoondo")
        val isInfoDiscovery = (lower.contains("latest information") || lower.contains("latest info") ||
                lower.contains("taaza jankari") || lower.contains("interesting") ||
                lower.contains("tools dhoondo") || lower.contains("projects dhoondo") ||
                lower.contains("agents ke baare") || lower.contains("information dhoondo") ||
                lower.contains("information nikaalo") || lower.contains("info nikaalo") ||
                lower.contains("resources dhoondo") || lower.contains("content dhoondo")) &&
                !lower.contains("chrome") && !lower.contains("file") && !lower.contains("storage")

        if (isInfoDiscovery) {
            val q = cleanResearchQuery(text)
            if (q.isNotBlank()) {
                logD(TAG, "Parsed InternetResearch (Info Discovery): $q")
                return ParsedAction.InternetResearch(
                    rawCommand = text,
                    cleanQuery = q,
                    targetPlatform = "WEB"
                )
            }
        }

        return null
    }

    private fun cleanResearchQuery(input: String): String {
        return input
            .replace(Regex("""(?i)^(hey\s+)?(myra|soltini)\s*,?\s*"""), "")
            .replace(Regex("""(?i)^(search\s+(this\s+on\s+the\s+internet|on\s+the\s+internet|the\s+web\s+for|search\s+reddit\s+for|search\s+for|web\s+par\s+search\s+karo|internet\s+se\s+check\s+karo|deep\s+research\s+karo|online\s+research\s+karo|research\s+on|research\s+karo|analyze|summarize|explain))\s*"""), "")
            .replace(Regex("""(?i)^(internet\s+se\s+|web\s+se\s+|online\s+se\s+|kuch\s+)"""), "")
            .replace(Regex("""(?i)(ke\s+baare\s+mein\s+latest\s+information\s+dhoondo|ke\s+baare\s+mein\s+batao|par\s+public\s+discussions\s+dekho|par\s+search\s+karo|ka\s+summary\s+batao|ka\s+summary\s+do|ko\s+analyze\s+karo|ko\s+summarize\s+karo|explain\s+karo|samjhao|dekho|dhoondo|nikaalo|check\s+karo|verify\s+karo)\s*$"""), "")
            .trim()
    }
}
