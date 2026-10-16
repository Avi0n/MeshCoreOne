package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.meshcore.shared.remotenode.RegionOpResult
import one.meshcore.shared.remotenode.RepeaterRegionEntry
import one.meshcore.shared.remotenode.RepeaterSettingsController
import one.meshcore.shared.remotenode.regionDeleteBlockedByChildren

/** Region load and edit commands the settings screen exposes. */
interface NodeRegionEditing {
    fun loadRegions()

    fun toggleRegionFlood(name: String)

    fun setDefaultScope(name: String?)

    fun loadDefaultScope()

    fun addRegion()

    fun removeRegion(name: String)

    fun dismissBlockedDelete()

    fun saveRegions()
}

/** Region load and edit commands that write the repeater settings state. */
internal class NodeRegionCommands(
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<NodeSettingsState>,
    private val dependencies: NodeSettingsViewModel.Dependencies,
    private val settings: () -> RepeaterSettingsController,
    private val reportedMissingService: () -> Boolean,
    private val canMutateRegions: () -> Boolean,
) : NodeRegionEditing {
    private val regionsRequest = RemoteSectionRequest(scope)
    private val defaultScopeRequest = RemoteSectionRequest(scope)
    val regionsJob: Job? get() = regionsRequest.job

    override fun loadRegions() {
        if (dependencies.repeaterAdminService() == null) return
        state.replaceSectionLoad(
            request = regionsRequest,
            begin = { it.copy(isLoadingRegions = true, regionsSectionError = false) },
            cancelled = { it.copy(isLoadingRegions = false, isLoadingDefaultScope = false) },
        ) {
            val controller = settings()
            if (controller.helper.firmwareVersion == null) controller.helper.onPreFetchNodeInfo?.invoke()
            controller.fetchRegions()
            if (settings().regionsLoaded && settings().supportsRegionDefaultScope) {
                settings().fetchDefaultScope()
            }
            syncRegionsState(isLoading = false)
        }
    }

    override fun toggleRegionFlood(name: String) {
        if (reportedMissingService()) return
        if (!canMutateRegions()) return
        scope.publishRegionEdit({ settings().toggleRegionFlood(name) }) { syncRegionsState(applyBanner = it) }
    }

    override fun setDefaultScope(name: String?) {
        if (reportedMissingService()) return
        if (!settings().supportsRegionDefaultScope) return
        if (!canMutateRegions()) return
        scope.publishRegionEdit({ settings().setDefaultScope(name) }) { syncRegionsState(applyBanner = it) }
    }

    override fun loadDefaultScope() {
        if (reportedMissingService()) return
        if (!settings().supportsRegionDefaultScope) return
        if (!canMutateRegions()) return
        state.replaceSectionLoad(
            request = defaultScopeRequest,
            begin = { it.copy(isLoadingDefaultScope = true) },
            cancelled = { it.copy(isLoadingDefaultScope = false) },
        ) {
            settings().fetchDefaultScope()
            currentCoroutineContext().ensureActive()
            syncRegionsState(isLoading = false)
        }
    }

    override fun addRegion() {
        if (reportedMissingService()) return
        if (!canMutateRegions()) return
        val name = state.value.newRegionName
        val parent = state.value.newRegionParent
        scope.launch {
            val result = settings().addRegion(name, parent)
            currentCoroutineContext().ensureActive()
            val banner = result.toBanner()
            val clearName = result is RegionOpResult.Applied
            state.update {
                it.copy(
                    regions = settings().regions,
                    hasUnsavedRegionChanges = settings().hasUnsavedRegionChanges,
                    newRegionName = if (clearName &&
                        it.newRegionName == name &&
                        it.newRegionParent == parent
                    ) {
                        ""
                    } else {
                        it.newRegionName
                    },
                    newRegionParent = if (clearName && it.newRegionName == name && it.newRegionParent == parent) {
                        RepeaterRegionEntry.Parent.Unscoped
                    } else {
                        it.newRegionParent
                    },
                    applyBanner = banner,
                )
            }
        }
    }

    override fun removeRegion(name: String) {
        if (reportedMissingService()) return
        if (!canMutateRegions()) return
        val region = state.value.regions.firstOrNull { it.name == name } ?: return
        if (region.isUnscoped) return
        if (regionDeleteBlockedByChildren(name, state.value.regions)) {
            state.update { it.copy(blockedDeleteRegionName = name) }
            return
        }
        scope.publishRegionEdit({ settings().removeRegion(name) }) { syncRegionsState(applyBanner = it) }
    }

    override fun dismissBlockedDelete() {
        state.update { it.copy(blockedDeleteRegionName = null) }
    }

    override fun saveRegions() {
        if (reportedMissingService()) return
        val feedback = scope.coroutineContext[SettingsApplyFeedback]
        val token = feedback?.begin(SettingsApplyFeedback.Kind.REGIONS)
        state.update { it.copy(isSavingRegions = true, applyBanner = null, regionsSaveSuccess = false) }
        scope.launch {
            try {
                val result = settings().saveRegions()
                currentCoroutineContext().ensureActive()
                if (token != null &&
                    feedback?.isCurrent(SettingsApplyFeedback.Kind.REGIONS, token) != true
                ) {
                    return@launch
                }
                when (result) {
                    RegionOpResult.Applied -> {
                        state.update {
                            it.copy(
                                isSavingRegions = false,
                                hasUnsavedRegionChanges = settings().hasUnsavedRegionChanges,
                                applyBanner = null,
                            )
                        }
                        flashApplySuccess(SettingsApplyFeedback.Kind.REGIONS, token) { success ->
                            state.update { it.copy(regionsSaveSuccess = success) }
                        }
                    }
                    RegionOpResult.SaveFailed -> finishRegionSave(ApplyBanner.RegionsSaveFailed)
                    is RegionOpResult.TransportError -> finishRegionSave(result.error.toTransportBanner())
                    else -> finishRegionSave(ApplyBanner.RegionsSaveFailed)
                }
            } catch (cancelled: CancellationException) {
                if (scope.isActive &&
                    (token == null || feedback?.isCurrent(SettingsApplyFeedback.Kind.REGIONS, token) == true)
                ) {
                    state.update {
                        it.copy(isSavingRegions = false, hasUnsavedRegionChanges = settings().hasUnsavedRegionChanges)
                    }
                }
                throw cancelled
            }
        }
    }

    private fun finishRegionSave(banner: ApplyBanner) {
        state.update {
            it.copy(
                isSavingRegions = false,
                hasUnsavedRegionChanges = settings().hasUnsavedRegionChanges,
                applyBanner = banner,
            )
        }
    }

    private fun syncRegionsState(isLoading: Boolean? = null, applyBanner: ApplyBanner? = null) {
        val controller = settings()
        state.update {
            it.copy(
                isLoadingRegions = isLoading ?: it.isLoadingRegions,
                regionsLoaded = controller.regionsLoaded,
                regionsSectionError = controller.regionsError,
                regions = controller.regions,
                defaultScopeName = controller.defaultScopeName,
                defaultScopeLoaded = controller.defaultScopeLoaded,
                isLoadingDefaultScope = controller.isLoadingDefaultScope,
                supportsRegionDefaultScope = controller.supportsRegionDefaultScope,
                hasUnsavedRegionChanges = controller.hasUnsavedRegionChanges,
                applyBanner = applyBanner ?: it.applyBanner,
            )
        }
    }
}

/** Runs a region edit, then hands its banner to [publish]. */
private fun CoroutineScope.publishRegionEdit(edit: suspend () -> RegionOpResult, publish: (ApplyBanner?) -> Unit) {
    launch {
        try {
            val result = edit()
            currentCoroutineContext().ensureActive()
            publish(result.toBanner())
        } catch (cancelled: CancellationException) {
            if (this@publishRegionEdit.isActive) publish(null)
            throw cancelled
        }
    }
}
