package com.wynh.appapp.workspace

import org.junit.Assert.*
import org.junit.Test

class PublicationGateTest {
    @Test fun cancelBeforePublicationStopsIt() {
        val gate = PublicationGate()
        assertTrue(gate.cancel()); assertFalse(gate.beginPublication())
    }
    @Test fun cancelAfterPublicationBeganIsRefusedSoNothingReportsCancellingWhilePublishing() {
        val gate = PublicationGate()
        assertTrue(gate.beginPublication()); assertFalse(gate.cancel())
        assertTrue("a refused cancel must not poison a later check", gate.beginPublication())
    }
    @Test fun resetStartsANewImport() {
        val gate = PublicationGate()
        gate.cancel(); gate.reset(); assertTrue(gate.beginPublication())
    }
}
