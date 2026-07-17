package com.glookogluroo.bridge.ui

import com.glookogluroo.bridge.cloud.SyncRunSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayJourneyTimelineTest {

    @Test
    fun queuedRun_marksStartAsActive() {
        val steps = relayJourneySteps(
            SyncRunSummary(runId = "r1", status = "QUEUED"),
        )
        assertEquals(RelayJourneyStepState.Active, steps[0].state)
        assertEquals(RelayJourneyStepState.Pending, steps[1].state)
    }

    @Test
    fun failedGlooko_marksConnectStepFailed() {
        val steps = relayJourneySteps(
            SyncRunSummary(
                runId = "r1",
                status = "FAILED",
                glookoOk = false,
                error = "Login failed",
            ),
        )
        assertEquals(RelayJourneyStepState.Complete, steps[0].state)
        assertEquals(RelayJourneyStepState.Failed, steps[1].state)
        assertEquals("Login failed", steps[1].detail)
    }

    @Test
    fun succeededSync_marksAllStepsComplete() {
        val steps = relayJourneySteps(
            SyncRunSummary(
                runId = "r1",
                mode = "sync",
                status = "SUCCEEDED",
                glookoOk = true,
                nightscoutOk = true,
                bolusesUploaded = 2,
            ),
        )
        assertEquals(4, steps.size)
        assert(steps.all { it.state == RelayJourneyStepState.Complete })
        assertEquals("2 events fetched", steps[2].detail)
    }
}
