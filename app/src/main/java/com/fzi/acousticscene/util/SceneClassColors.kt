package com.fzi.acousticscene.util

import android.content.Context
import androidx.core.content.ContextCompat
import com.fzi.acousticscene.R
import com.fzi.acousticscene.model.SceneClass

/**
 * Single source of truth for scene-class colours across the UI. Wraps the
 * `colors.xml` entries with light/night variants so widgets don't hard-code
 * hex values and the per-slice circles in the AVERAGE card stay in sync with
 * the distribution bars.
 */
object SceneClassColors {

    fun color(context: Context, sc: SceneClass): Int =
        ContextCompat.getColor(context, colorRes(sc))

    fun colorRes(sc: SceneClass): Int = when (sc) {
        SceneClass.TRANSIT_VEHICLES -> R.color.transit_vehicles
        SceneClass.URBAN_WAITING -> R.color.urban_waiting
        SceneClass.NATURE -> R.color.nature
        SceneClass.SOCIAL -> R.color.social
        SceneClass.WORK -> R.color.work
        SceneClass.COMMERCIAL -> R.color.commercial
        SceneClass.LEISURE_SPORT -> R.color.leisure_sport
        SceneClass.CULTURE_QUIET -> R.color.culture_quiet
        SceneClass.LIVING_ROOM -> R.color.living_room
    }
}
