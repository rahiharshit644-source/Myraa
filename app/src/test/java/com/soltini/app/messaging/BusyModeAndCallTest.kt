package com.soltini.app.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.soltini.app.telephony.CallNotificationManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BusyModeAndCallTest {

    private lateinit var context: Context
    private lateinit var busyManager: BusyModeManager
    private lateinit var callManager: CallNotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        busyManager = BusyModeManager.getInstance(context)
        busyManager.disableBusyMode()
        callManager = CallNotificationManager.getInstance(context)
        callManager.onCallEnded()
    }

    @Test
    fun testBusyModeActivationAndDeactivation() {
        assertFalse(busyManager.isBusyModeActive())

        val reason = busyManager.enableBusyMode("meeting me hoon")
        assertTrue(busyManager.isBusyModeActive())
        assertEquals("meeting", reason)

        val summary = busyManager.disableBusyMode()
        assertFalse(busyManager.isBusyModeActive())
        assertNotNull(summary)
        assertEquals(0, summary.totalRepliedCount)
    }

    @Test
    fun testOutgoingCallSuppression() {
        var incomingAnnounced = false
        callManager.onIncomingCallDetected = { _, _ ->
            incomingAnnounced = true
        }

        // Simulate user making an outgoing call
        callManager.markOutgoingCallInitiated("9876543210")
        assertTrue("Outgoing call must be recorded as active", callManager.isOutgoingCallActive)
        assertEquals("OFFHOOK", callManager.currentCallState)

        // When a network ringback / spurious ringing alert occurs during dialing:
        callManager.onIncomingCall("Mummy", "9876543210")

        // Crucial verification: incoming call announcement must be completely suppressed!
        assertFalse("Incoming call alert MUST NOT fire while making an outgoing call", incomingAnnounced)

        // End the call
        callManager.onCallEnded()
        assertFalse(callManager.isOutgoingCallActive)
        assertEquals("IDLE", callManager.currentCallState)
    }
}
