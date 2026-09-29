package com.soltini.app.reach

import com.soltini.app.orchestrator.MyraCommandParser
import com.soltini.app.orchestrator.ParsedAction
import org.junit.Assert.*
import org.junit.Test

class MyraCommandParserReachTest {

    @Test
    fun testDoctorAndCapabilityStatusCommand() {
        val cmd1 = "MYRA, internet capabilities ka status batao"
        val parsed1 = MyraCommandParser.parse(cmd1)
        assertTrue(parsed1 is ParsedAction.InternetResearch)
        assertTrue((parsed1 as ParsedAction.InternetResearch).isDoctorCheck)

        val cmd2 = "check internet capabilities"
        val parsed2 = MyraCommandParser.parse(cmd2)
        assertTrue(parsed2 is ParsedAction.InternetResearch)
        assertTrue((parsed2 as ParsedAction.InternetResearch).isDoctorCheck)
    }

    @Test
    fun testBackendQueryCommand() {
        val cmd = "MYRA, kaunsa backend currently use ho raha hai?"
        val parsed = MyraCommandParser.parse(cmd)
        assertTrue(parsed is ParsedAction.InternetResearch)
        assertTrue((parsed as ParsedAction.InternetResearch).isBackendQuery)
    }

    @Test
    fun testWebUrlReadingCommand() {
        val cmd = "MYRA, is URL ko read karo: https://example.com/ai-report"
        val parsed = MyraCommandParser.parse(cmd)
        assertTrue(parsed is ParsedAction.InternetResearch)
        val act = parsed as ParsedAction.InternetResearch
        assertEquals("https://example.com/ai-report", act.targetUrl)
        assertEquals("WEB_READER", act.targetPlatform)
    }

    @Test
    fun testYouTubeVideoSummaryCommand() {
        val cmd = "MYRA, is YouTube video ka 5-point summary batao"
        val parsed = MyraCommandParser.parse(cmd)
        assertTrue(parsed is ParsedAction.InternetResearch)
        val act = parsed as ParsedAction.InternetResearch
        assertEquals("YOUTUBE", act.targetPlatform)

        // Make sure a music play request still goes to YouTubePlay
        val playCmd = "YouTube par Arijit Singh ka gaana chalao"
        val parsedPlay = MyraCommandParser.parse(playCmd)
        assertTrue(parsedPlay is ParsedAction.YouTubePlay)
    }

    @Test
    fun testGitHubRepoAnalysisCommand() {
        val cmd = "MYRA, Panniantong/Agent-Reach GitHub repo ko analyze karo aur architecture samjhao"
        val parsed = MyraCommandParser.parse(cmd)
        assertTrue(parsed is ParsedAction.InternetResearch)
        val act = parsed as ParsedAction.InternetResearch
        assertEquals("GITHUB", act.targetPlatform)
    }

    @Test
    fun testRedditDiscussionSearchCommand() {
        val cmd = "MYRA, Reddit par LocalLLaMA public discussions search karo"
        val parsed = MyraCommandParser.parse(cmd)
        assertTrue(parsed is ParsedAction.InternetResearch)
        val act = parsed as ParsedAction.InternetResearch
        assertEquals("REDDIT", act.targetPlatform)
    }

    @Test
    fun testDeepResearchCommand() {
        val cmd = "MYRA, deep research karo AI agents ke latest tools par multiple sources cross-check karke"
        val parsed = MyraCommandParser.parse(cmd)
        assertTrue(parsed is ParsedAction.InternetResearch)
        val act = parsed as ParsedAction.InternetResearch
        assertTrue(act.isDeepResearch)
    }

    @Test
    fun testExplicitWebSearchCommand() {
        val cmd = "MYRA, search the web for latest space discoveries"
        val parsed = MyraCommandParser.parse(cmd)
        assertTrue(parsed is ParsedAction.InternetResearch)
        val act = parsed as ParsedAction.InternetResearch
        assertEquals("WEB", act.targetPlatform)
    }
}
