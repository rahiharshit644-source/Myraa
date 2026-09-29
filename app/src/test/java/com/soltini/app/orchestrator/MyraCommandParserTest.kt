package com.soltini.app.orchestrator

import org.junit.Assert.*
import org.junit.Test

class MyraCommandParserTest {

    @Test
    fun testIncomingCallDetectionQueries() {
        val q1 = MyraCommandParser.parse("Call aa raha hai Harshit")
        assertTrue("Expected CallInfoQuery but got $q1", q1 is ParsedAction.CallInfoQuery)

        val q2 = MyraCommandParser.parse("Harshit ka call aa raha hai")
        assertTrue("Expected CallInfoQuery but got $q2", q2 is ParsedAction.CallInfoQuery)

        val q3 = MyraCommandParser.parse("Kaun call kar raha hai?")
        assertTrue("Expected CallInfoQuery but got $q3", q3 is ParsedAction.CallInfoQuery)

        val q4 = MyraCommandParser.parse("Kiska call hai")
        assertTrue("Expected CallInfoQuery but got $q4", q4 is ParsedAction.CallInfoQuery)
    }

    @Test
    fun testCallReceiveCommands() {
        val c1 = MyraCommandParser.parse("Call receive karo")
        assertTrue("Expected CallAnswer but got $c1", c1 is ParsedAction.CallAnswer)

        val c2 = MyraCommandParser.parse("Call accept karo")
        assertTrue("Expected CallAnswer but got $c2", c2 is ParsedAction.CallAnswer)

        val c3 = MyraCommandParser.parse("Phone uthao")
        assertTrue("Expected CallAnswer but got $c3", c3 is ParsedAction.CallAnswer)
    }

    @Test
    fun testCallRejectCommands() {
        val r1 = MyraCommandParser.parse("Call reject karo")
        assertTrue("Expected CallReject but got $r1", r1 is ParsedAction.CallReject)

        val r2 = MyraCommandParser.parse("Call cut karo")
        assertTrue("Expected CallReject but got $r2", r2 is ParsedAction.CallReject)

        val r3 = MyraCommandParser.parse("Phone kaat do")
        assertTrue("Expected CallReject but got $r3", r3 is ParsedAction.CallReject)
    }

    @Test
    fun testMessageParsing() {
        val m1 = MyraCommandParser.parse("Rahul ko message bhejo ki main kal nahi aa paunga")
        assertNotNull("Should parse message", m1)
        assertTrue(m1 is ParsedAction.SendMessage)
        val msg = m1 as ParsedAction.SendMessage
        assertEquals("Rahul", msg.recipient)
        assertEquals("main kal nahi aa paunga", msg.messageText)

        val m2 = MyraCommandParser.parse("Priya ko bol do ki meeting postponed hai")
        assertTrue(m2 is ParsedAction.SendMessage)
        val msg2 = m2 as ParsedAction.SendMessage
        assertEquals("Priya", msg2.recipient)
        assertEquals("meeting postponed hai", msg2.messageText)
    }

    @Test
    fun testYouTubePlayAndSearch() {
        val y1 = MyraCommandParser.parse("Arijit Singh ke gaane chalao")
        assertNotNull(y1)
        assertTrue("Expected YouTubePlay but got $y1", y1 is ParsedAction.YouTubePlay)
        val yt = y1 as ParsedAction.YouTubePlay
        assertEquals("Arijit Singh", yt.cleanQuery)

        val y2 = MyraCommandParser.parse("Tum hi ho song bajao")
        assertTrue(y2 is ParsedAction.YouTubePlay)
        val yt2 = y2 as ParsedAction.YouTubePlay
        assertEquals("Tum hi ho", yt2.cleanQuery)

        val s1 = MyraCommandParser.parse("YouTube pe Kotlin tutorials search karo")
        assertTrue("Expected YouTubeSearch but got $s1", s1 is ParsedAction.YouTubeSearch)
        val search = s1 as ParsedAction.YouTubeSearch
        assertEquals("Kotlin tutorials", search.cleanQuery)

        val y3 = MyraCommandParser.parse("YouTube par Arijit Singh ka gaana chalao")
        assertTrue("Expected YouTubePlay for Arijit Singh but got $y3", y3 is ParsedAction.YouTubePlay)
        val yt3 = y3 as ParsedAction.YouTubePlay
        assertEquals("Arijit Singh", yt3.cleanQuery)
    }

    @Test
    fun testGoogleSearchCleaning() {
        val g1 = MyraCommandParser.parse("Google par current weather in Delhi search karo")
        assertNotNull(g1)
        assertTrue("Expected WebSearch but got $g1", g1 is ParsedAction.WebSearch)
        val ws = g1 as ParsedAction.WebSearch
        assertEquals("current weather in Delhi", ws.cleanQuery)

        val g2 = MyraCommandParser.parse("Google par latest AI news search karo")
        assertTrue(g2 is ParsedAction.WebSearch)
        assertEquals("latest AI news", (g2 as ParsedAction.WebSearch).cleanQuery)

        val g3 = MyraCommandParser.parse("Shahrukh Khan ki net worth Google par search karo")
        assertTrue(g3 is ParsedAction.WebSearch)
        assertEquals("Shahrukh Khan ki net worth", (g3 as ParsedAction.WebSearch).cleanQuery)
    }

    @Test
    fun testDirectCleanersPreventCommandLeakage() {
        // Voice command must NEVER become message body
        val cleanedBody1 = MyraCommandParser.cleanMessageBody("Rahul ko message bhejo ki main kal college nahi aaunga")
        assertEquals("main kal college nahi aaunga", cleanedBody1)

        val cleanedBody2 = MyraCommandParser.cleanMessageBody("ki main 10 minute late aaunga")
        assertEquals("main 10 minute late aaunga", cleanedBody2)

        val cleanedRec1 = MyraCommandParser.cleanRecipient("Rahul ko")
        assertEquals("Rahul", cleanedRec1)

        val cleanedRec2 = MyraCommandParser.cleanRecipient("Rahul ko message bhejo ki hello")
        assertEquals("Rahul", cleanedRec2)

        // Web query cleaner strips command tokens
        val cleanedWeb = MyraCommandParser.cleanWebSearchQuery("Google par search karo latest AI news")
        assertEquals("latest AI news", cleanedWeb)

        // YouTube query cleaner strips command tokens
        val cleanedYt = MyraCommandParser.cleanYouTubeQuery("YouTube par Arijit Singh ka gaana chalao")
        assertEquals("Arijit Singh", cleanedYt)
    }

    @Test
    fun testFormFillingCommandParsing() {
        val f1 = MyraCommandParser.parse("Form fill karo")
        assertNotNull("Expected FillForm for 'Form fill karo'", f1)
        assertTrue(f1 is ParsedAction.FillForm)
        val form1 = f1 as ParsedAction.FillForm
        assertEquals("general", form1.formType)
        assertFalse(form1.autoSubmit)

        val f2 = MyraCommandParser.parse("Address form bharo")
        assertTrue(f2 is ParsedAction.FillForm)
        val form2 = f2 as ParsedAction.FillForm
        assertEquals("address", form2.formType)

        val f3 = MyraCommandParser.parse("Login details bharo")
        assertTrue(f3 is ParsedAction.FillForm)
        val form3 = f3 as ParsedAction.FillForm
        assertEquals("login", form3.formType)

        val f4 = MyraCommandParser.parse("Signup form complete karo")
        assertTrue(f4 is ParsedAction.FillForm)
        val form4 = f4 as ParsedAction.FillForm
        assertEquals("signup", form4.formType)

        val f5 = MyraCommandParser.parse("Form bhar ke submit kar dena")
        assertTrue(f5 is ParsedAction.FillForm)
        val form5 = f5 as ParsedAction.FillForm
        assertTrue("Expected autoSubmit to be true", form5.autoSubmit)

        val f6 = MyraCommandParser.parse("Form me naam Rahul aur phone 9876543210 bhar do")
        assertTrue(f6 is ParsedAction.FillForm)
        val form6 = f6 as ParsedAction.FillForm
        assertEquals("Rahul", form6.fields["name"])
        assertEquals("9876543210", form6.fields["phone"])
    }

    @Test
    fun testBusyModeCommandParsing() {
        val b1 = MyraCommandParser.parse("myra me busy hun ab koi mssg aaye to tu handle kr lioo")
        assertNotNull(b1)
        assertTrue("Expected SetBusyMode enable=true but got $b1", b1 is ParsedAction.SetBusyMode)
        val busy1 = b1 as ParsedAction.SetBusyMode
        assertTrue(busy1.enable)

        val b2 = MyraCommandParser.parse("Main meeting me hoon message handle kar lena")
        assertTrue(b2 is ParsedAction.SetBusyMode)
        val busy2 = b2 as ParsedAction.SetBusyMode
        assertTrue(busy2.enable)
        assertEquals("meeting", busy2.reason)

        val b3 = MyraCommandParser.parse("Busy mode chalu kar do")
        assertTrue(b3 is ParsedAction.SetBusyMode)
        assertTrue((b3 as ParsedAction.SetBusyMode).enable)

        val b4 = MyraCommandParser.parse("Main free ho gaya hoon")
        assertTrue(b4 is ParsedAction.SetBusyMode)
        assertFalse((b4 as ParsedAction.SetBusyMode).enable)

        val b5 = MyraCommandParser.parse("Busy mode band karo")
        assertTrue(b5 is ParsedAction.SetBusyMode)
        assertFalse((b5 as ParsedAction.SetBusyMode).enable)

        val b6 = MyraCommandParser.parse("Busy mode summary dikhao")
        assertTrue("Expected BusyModeSummaryQuery but got $b6", b6 is ParsedAction.BusyModeSummaryQuery)
    }
}
