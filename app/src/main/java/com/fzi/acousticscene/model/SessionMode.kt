package com.fzi.acousticscene.model

/**
 * Which app entry point a session was started from. Test-mode sessions are the
 * ones a non-developer tester runs from a quickstart slot; config-mode sessions
 * are ones a developer started from the regular wizard flow.
 *
 * Captured on the [SessionConfig] when the wizard exits, then mirrored onto
 * each [PredictionRecord] so the History screen can filter by entry point.
 */
enum class SessionMode { TEST, CONFIG }
