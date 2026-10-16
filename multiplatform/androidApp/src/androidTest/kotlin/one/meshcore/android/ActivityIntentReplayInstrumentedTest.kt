package one.meshcore.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActivityIntentReplayInstrumentedTest {
    @Test
    fun coldContactIntentIsNotReplayedAfterRecreate() = case("coldContactIntentIsNotReplayedAfterRecreate") {
        launch()
        awaitLink()
        clearLink()
        recreate(expectPending = false)
    }

    @Test
    fun warmContactIntentIsNotReplayedAfterRecreate() = case("warmContactIntentIsNotReplayedAfterRecreate") {
        launch(withLink = false)
        deliverWarm()
        clearLink()
        recreate(expectPending = false)
    }

    @Test
    fun pendingContactReviewSurvivesRecreate() = case("pendingContactReviewSurvivesRecreate") {
        launch()
        awaitLink()
        recreate(expectPending = true)
    }

    @Test
    fun newExplicitDeliveryOfSameContactStillStagesReview() =
        case("newExplicitDeliveryOfSameContactStillStagesReview") {
            launch()
            awaitLink()
            clearLink()
            deliverWarm()
        }

    @Test
    fun nonViewDataIsNotRouted() = case("nonViewDataIsNotRouted") {
        launch()
        awaitLink()
        clearLink()
        deliverIgnoredNonView()
    }

    @Test
    fun freshActivityOwnerAcceptsSameInitialContact() = case("freshActivityOwnerAcceptsSameInitialContact") {
        launch()
        awaitLink()
        clearLink()
        finishAndRelaunch()
    }

    private fun case(method: String, action: ActivityIntentReplayFixture.() -> Unit) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Requires the dedicated disposable intent driver",
            args.containsKey(ActivityIntentReplayFixture.ARGUMENT),
        )
        val fixture =
            ActivityIntentReplayFixture(checkNotNull(args.getString(ActivityIntentReplayFixture.ARGUMENT)), method)
        try {
            fixture.qualify()
            fixture.action()
            fixture.record()
        } finally {
            fixture.close()
        }
    }
}
