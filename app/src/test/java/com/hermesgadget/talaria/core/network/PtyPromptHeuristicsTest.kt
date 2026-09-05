/*
 * Copyright 2026 Talaria contributors
 * Licensed under the Apache License, Version 2.0 (the "License").
 */

package com.hermesgadget.talaria.core.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B17 regression: prompt-delivery must not claim success from generic
 * terminal text ("agent", "running", ...) — buffered startup output or an
 * earlier streaming turn would satisfy those markers and fabricate an
 * acknowledgement. Until a correlated server receipt exists, only an exact
 * echo of the prompt in POST-SEND output counts.
 */
class PtyPromptHeuristicsTest {
    private val prompt = "list the files"

    private fun output(text: String) = PtyEvent.Output(text = text, raw = text)

    @Test
    fun `generic agent markers no longer count as acknowledgement`() {
        val (accepted, _) = heuristicAcceptanceForTest(
            output("the agent is working and processing"),
            prompt,
            "",
        )
        assertFalse("generic markers must not fabricate delivery success", accepted)
    }

    @Test
    fun `exact prompt echo in post-send output is accepted`() {
        val (accepted, _) = heuristicAcceptanceForTest(output("$prompt\n"), prompt, "")
        assertTrue(accepted)
    }

    @Test
    fun `unrelated post-send output stays unaccepted (uncertain delivery)`() {
        val (accepted, _) = heuristicAcceptanceForTest(output("loading plugins... ok"), prompt, "")
        assertFalse(accepted)
    }

    @Test
    fun `blank output is never an acknowledgement`() {
        val (accepted, _) = heuristicAcceptanceForTest(output(""), prompt, "")
        assertFalse(accepted)
    }
}
