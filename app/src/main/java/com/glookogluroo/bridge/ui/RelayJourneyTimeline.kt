package com.glookogluroo.bridge.ui

import com.glookogluroo.bridge.cloud.SyncRunSummary

enum class RelayJourneyStepState {
    Pending,
    Active,
    Complete,
    Failed,
}

data class RelayJourneyStep(
    val label: String,
    val detail: String? = null,
    val state: RelayJourneyStepState,
)

/**
 * User-facing four-step relay journey (Relay Design Language v1.0).
 *
 * 1. Start relay
 * 2. Connect Glooko
 * 3. Fetch events
 * 4. Deliver to Gluroo (sync) or Verify Gluroo (test)
 */
fun relayJourneySteps(run: SyncRunSummary): List<RelayJourneyStep> {
    val status = run.status.uppercase()
    val isTest = run.mode.equals("test", ignoreCase = true)
    val step4Label = if (isTest) "Verify Gluroo" else "Deliver to Gluroo"

    return when (status) {
        "QUEUED" -> listOf(
            step("Start relay", "Waiting to begin", RelayJourneyStepState.Active),
            step("Connect Glooko", state = RelayJourneyStepState.Pending),
            step("Fetch events", state = RelayJourneyStepState.Pending),
            step(step4Label, state = RelayJourneyStepState.Pending),
        )
        "RUNNING" -> runningSteps(run, step4Label)
        "SUCCEEDED" -> succeededSteps(run, step4Label, isTest)
        "FAILED" -> failedSteps(run, step4Label, isTest)
        else -> listOf(
            step("Start relay", run.startedAt.ifBlank { null }, RelayJourneyStepState.Complete),
            step("Connect Glooko", state = RelayJourneyStepState.Pending),
            step("Fetch events", state = RelayJourneyStepState.Pending),
            step(step4Label, state = RelayJourneyStepState.Pending),
        )
    }
}

private fun runningSteps(run: SyncRunSummary, step4Label: String): List<RelayJourneyStep> {
    val current = run.currentStep
    val activeIndex = when {
        current.equals("LoadConfig", ignoreCase = true) ||
            current.equals("Queued", ignoreCase = true) -> 0
        current.equals("RecordRun", ignoreCase = true) -> 3
        else -> 1 // RunSync covers connect + fetch + deliver
    }
    return listOf(
        step("Start relay", "In progress", indexState(0, activeIndex)),
        step("Connect Glooko", indexState(1, activeIndex)),
        step("Fetch events", indexState(2, activeIndex)),
        step(step4Label, indexState(3, activeIndex)),
    )
}

private fun succeededSteps(
    run: SyncRunSummary,
    step4Label: String,
    isTest: Boolean,
): List<RelayJourneyStep> {
    val fetchDetail = when {
        isTest -> "Preview ready"
        run.bolusesUploaded == 0 && !run.pumpNoteUploaded -> "No new events"
        run.bolusesUploaded > 0 -> {
            val noun = if (run.bolusesUploaded == 1) "event" else "events"
            "${run.bolusesUploaded} $noun fetched"
        }
        run.pumpNoteUploaded -> "Pump note fetched"
        else -> null
    }
    val deliverDetail = when {
        isTest -> if (run.nightscoutOk == true) "Connection verified" else null
        run.pumpNoteUploaded && run.bolusesUploaded > 0 ->
            "${run.bolusesUploaded} events and pump note delivered"
        run.pumpNoteUploaded -> "Pump note delivered"
        run.bolusesUploaded > 0 -> "${run.bolusesUploaded} events delivered"
        else -> "Nothing new to deliver"
    }
    return listOf(
        step("Start relay", "Relay began", RelayJourneyStepState.Complete),
        step(
            "Connect Glooko",
            if (run.glookoOk == true) "Connected" else null,
            RelayJourneyStepState.Complete,
        ),
        step("Fetch events", fetchDetail, RelayJourneyStepState.Complete),
        step(step4Label, deliverDetail, RelayJourneyStepState.Complete),
    )
}

private fun failedSteps(
    run: SyncRunSummary,
    step4Label: String,
    isTest: Boolean,
): List<RelayJourneyStep> {
    val error = run.error?.takeIf { it.isNotBlank() }
    return when {
        run.glookoOk == false -> listOf(
            step("Start relay", "Relay began", RelayJourneyStepState.Complete),
            step("Connect Glooko", error ?: "Could not connect", RelayJourneyStepState.Failed),
            step("Fetch events", state = RelayJourneyStepState.Pending),
            step(step4Label, state = RelayJourneyStepState.Pending),
        )
        run.nightscoutOk == false -> listOf(
            step("Start relay", "Relay began", RelayJourneyStepState.Complete),
            step("Connect Glooko", "Connected", RelayJourneyStepState.Complete),
            step("Fetch events", fetchDetailFor(run), RelayJourneyStepState.Complete),
            step(step4Label, error ?: "Could not deliver", RelayJourneyStepState.Failed),
        )
        else -> listOf(
            step("Start relay", "Relay began", RelayJourneyStepState.Complete),
            step("Connect Glooko", "Connected", RelayJourneyStepState.Complete),
            step(
                "Fetch events",
                error ?: if (isTest) "Preview failed" else "Could not fetch events",
                RelayJourneyStepState.Failed,
            ),
            step(step4Label, state = RelayJourneyStepState.Pending),
        )
    }
}

private fun fetchDetailFor(run: SyncRunSummary): String? = when {
    run.bolusesUploaded > 0 -> "${run.bolusesUploaded} events ready"
    run.pumpNoteUploaded -> "Pump note ready"
    else -> "Events prepared"
}

private fun indexState(stepIndex: Int, activeIndex: Int): RelayJourneyStepState = when {
    stepIndex < activeIndex -> RelayJourneyStepState.Complete
    stepIndex == activeIndex -> RelayJourneyStepState.Active
    else -> RelayJourneyStepState.Pending
}

private fun step(
    label: String,
    detail: String? = null,
    state: RelayJourneyStepState,
): RelayJourneyStep = RelayJourneyStep(label = label, detail = detail, state = state)
