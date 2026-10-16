package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.update
import one.meshcore.shared.remotenode.RepeaterRegionEntry
import one.meshcore.shared.remotenode.addChildParentFor

/** The add-region draft on the repeater settings screen. */
interface NodeSettingsRegionDraft {
    fun setNewRegionName(value: String)

    fun setNewRegionParent(parent: RepeaterRegionEntry.Parent)

    fun beginAddChild(region: RepeaterRegionEntry)
}

internal class NodeSettingsRegionDraftEditor(private val model: NodeSettingsModel) : NodeSettingsRegionDraft {

    override fun setNewRegionName(value: String) {
        model.stateUpdates.update { it.copy(newRegionName = value) }
    }

    override fun setNewRegionParent(parent: RepeaterRegionEntry.Parent) {
        if (!model.canMutateRegions()) return
        model.stateUpdates.update { it.copy(newRegionParent = parent) }
    }

    override fun beginAddChild(region: RepeaterRegionEntry) {
        if (!model.canMutateRegions()) return
        model.stateUpdates.update { it.copy(newRegionParent = addChildParentFor(region)) }
    }
}
