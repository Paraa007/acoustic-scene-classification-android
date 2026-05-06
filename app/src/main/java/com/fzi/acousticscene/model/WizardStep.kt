package com.fzi.acousticscene.model

/**
 * Wizard pages, in display order. The Interval branch has one extra step
 * ([IntervalPause] + [IntervalMethods]); the Continuous branch only has
 * [ClipDuration]. The summary is always the last page before Start.
 */
enum class WizardStep(val headerText: String) {
    Models("Pick one or more models."),
    Category("How should the app record?"),
    ClipDuration("How long should each recording be?"),
    IntervalPause("How often should a recording be made?"),
    IntervalMethods("Which evaluation methods per model?"),
    SessionDuration("How long should the session run overall?"),
    Summary("Ready to start.");

    companion object {
        fun continuousSteps(): List<WizardStep> =
            listOf(Models, Category, ClipDuration, SessionDuration, Summary)

        fun intervalSteps(): List<WizardStep> =
            listOf(Models, Category, IntervalPause, IntervalMethods, SessionDuration, Summary)
    }
}
