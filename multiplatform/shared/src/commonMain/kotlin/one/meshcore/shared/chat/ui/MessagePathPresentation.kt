package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.pathdetail.MessagePathArrival
import one.meshcore.shared.chat.pathdetail.MessagePathDisplay
import one.meshcore.shared.model.Uuid

/** Resolved path views for the canonical arrival and any distinct incoming routes. */
data class MessagePathPresentation(
    val initialDisplay: MessagePathDisplay,
    val arrivals: List<ArrivalPathDisplay>,
    val initialSelectionID: Uuid? = null,
) {
    fun displayFor(arrivalID: Uuid?): MessagePathDisplay =
        arrivals.firstOrNull { it.arrival.id == arrivalID }?.display ?: initialDisplay
}

data class ArrivalPathDisplay(val arrival: MessagePathArrival, val display: MessagePathDisplay)
