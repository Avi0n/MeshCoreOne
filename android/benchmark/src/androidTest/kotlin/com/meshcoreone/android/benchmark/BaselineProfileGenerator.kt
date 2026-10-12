// AndroidOnly: WP-504 Collect only actually executed startup/timeline/map paths; never commit an invented profile.
package com.meshcoreone.android.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val profiles = BaselineProfileRule()
    @Before fun prepare() = prepareWorkload(profileGeneration = true)

    @Test fun startup() = profiles.collect(
        packageName = TARGET, includeInStartupProfile = true, strictStability = true,
        filterPredicate = { !it.contains("Lcom/meshcoreone/android/benchmark") },
    ) {
        pressHome()
        startActivityAndWait()
    }

    @Test fun timelineAndMap() = profiles.collect(
        packageName = TARGET, strictStability = true,
        filterPredicate = { !it.contains("Lcom/meshcoreone/android/benchmark") },
    ) {
        pressHome()
        openWorkload("timeline")
        scrollTimeline()
        openWorkload("map")
        panMap()
    }
}
