package com.fzi.acousticscene.model

/**
 * Reason the wizard was opened — set when entering, used by the Summary screen
 * to pick the right CTA and exit behaviour.
 *
 * - [StartRecording] (default) — built a fresh config, exit straight into the
 *   live recording flow.
 * - [QuickStart] — re-running the last config; same exit as [StartRecording]
 *   but the Summary is a read-only single page.
 * - [SaveAsSlot] — Configure-test-mode flow from the Welcome screen; Summary
 *   CTA reads "Save as Quick start" and writes a [QuickstartSlot] instead of
 *   navigating to live recording.
 */
sealed class WizardIntent {
    object StartRecording : WizardIntent()
    object QuickStart : WizardIntent()
    object SaveAsSlot : WizardIntent()
}
