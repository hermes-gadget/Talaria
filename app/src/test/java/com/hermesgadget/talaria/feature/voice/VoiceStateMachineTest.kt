/* Copyright 2026 Talaria contributors; Licensed under the Apache License, Version 2.0. */
package com.hermesgadget.talaria.feature.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceStateMachineTest {
    @Test
    fun serverVoiceLifecycleMovesThroughRecordingTranscribingAndPlaying() {
        var phase = VoicePhase.IDLE
        phase = VoiceStateMachine.reduce(phase, VoiceEvent.StartRecording)
        assertEquals(VoicePhase.RECORDING, phase)
        phase = VoiceStateMachine.reduce(phase, VoiceEvent.RecordingFinished)
        assertEquals(VoicePhase.TRANSCRIBING, phase)
        phase = VoiceStateMachine.reduce(phase, VoiceEvent.TranscriptionFinished)
        assertEquals(VoicePhase.IDLE, phase)
        phase = VoiceStateMachine.reduce(phase, VoiceEvent.StartPlayback)
        assertEquals(VoicePhase.PLAYING, phase)
        phase = VoiceStateMachine.reduce(phase, VoiceEvent.PlaybackFinished)
        assertEquals(VoicePhase.IDLE, phase)
    }

    @Test
    fun unavailableStateIsExplicitAndRecoversAfterCapabilityRefresh() {
        var phase = VoiceStateMachine.reduce(VoicePhase.IDLE, VoiceEvent.ServerUnavailable)
        assertEquals(VoicePhase.UNAVAILABLE, phase)
        phase = VoiceStateMachine.reduce(phase, VoiceEvent.ServerAvailable)
        assertEquals(VoicePhase.IDLE, phase)
    }
}
