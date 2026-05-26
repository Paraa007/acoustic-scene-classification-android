package com.fzi.acousticscene.model

/**
 * Wizard pages, in display order. The Interval branch has one extra step
 * ([IntervalPause]). Methods per model are derived from each model's
 * training duration — no step asks the user about them. The summary is
 * always the last page before Start.
 */
enum class WizardStep(val headerText: String) {
    Models("Pick one or more models"),
    Category("How should the app record?"),
    IntervalPause("How often should a recording be made?"),
    SessionDuration("How long should the session run overall?"),
    Summary("Ready to start");

    companion object {
        fun continuousSteps(): List<WizardStep> =
            listOf(Models, Category, SessionDuration, Summary)

        fun intervalSteps(): List<WizardStep> =
            listOf(Models, Category, IntervalPause, SessionDuration, Summary)
    }
}
