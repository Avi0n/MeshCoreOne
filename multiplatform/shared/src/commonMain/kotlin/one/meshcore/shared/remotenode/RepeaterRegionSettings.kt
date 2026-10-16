package one.meshcore.shared.remotenode

import one.meshcore.shared.settings.FirmwareVersion
import kotlin.time.Duration

/** Region tree reads and edits for one repeater. */
interface RepeaterRegionCommands {
    val regions: List<RepeaterRegionEntry>
    val regionsError: Boolean
    val hasUnsavedRegionChanges: Boolean
    val regionsLoaded: Boolean
    val defaultScopeName: String?
    val defaultScopeLoaded: Boolean
    val isLoadingDefaultScope: Boolean

    /** `region default` exists on MeshCore repeater v1.15.0+. Unknown version is unsupported. */
    val supportsRegionDefaultScope: Boolean

    suspend fun fetchRegions()

    suspend fun fetchDefaultScope()

    suspend fun toggleRegionFlood(name: String): RegionOpResult

    suspend fun setDefaultScope(name: String?): RegionOpResult

    suspend fun removeRegion(name: String): RegionOpResult

    suspend fun saveRegions(): RegionOpResult

    /** Routes a late CLI response to the shared arbitration, which now covers behavior, then regions. */
    fun handleLateResponse(response: String): Boolean
}

internal class RepeaterRegionSettings(
    private val helper: NodeSettingsController,
    private val transport: RepeaterCliTransport,
    private val sendCommand: suspend (command: String, timeout: Duration) -> String,
    private val sendRawCommand: suspend (command: String, timeout: Duration) -> String,
) : RepeaterRegionCommands {
    override var regions: List<RepeaterRegionEntry> = emptyList()
        internal set
    private var originalRegions: List<RepeaterRegionEntry>? = null
    override var regionsError: Boolean = false
        private set
    override var hasUnsavedRegionChanges: Boolean = false
        private set
    override val regionsLoaded: Boolean get() = originalRegions != null
    override var defaultScopeName: String? = null
        private set
    override var defaultScopeLoaded: Boolean = false
        private set
    override var isLoadingDefaultScope: Boolean = false
        private set
    override val supportsRegionDefaultScope: Boolean
        get() = FirmwareVersion.supportsRegionDefaultScope(helper.firmwareVersion)

    override suspend fun fetchRegions() {
        regionsError = false
        val timedOut = runSettingsFetch {
            val parsed = RegionNameValidator.parseRegionTree(
                sendRawCommand("region", RepeaterCliTransport.REGION_FETCH_TIMEOUT),
            )
            if (parsed.isEmpty()) {
                originalRegions = null
                regionsError = true
            } else {
                regions = parsed
                originalRegions = parsed
            }
        }
        if (timedOut) regionsError = true
    }

    override suspend fun fetchDefaultScope() {
        if (!supportsRegionDefaultScope || isLoadingDefaultScope) return
        isLoadingDefaultScope = true
        try {
            val reply = sendRawCommand("region default", RepeaterCliTransport.REGION_FETCH_TIMEOUT)
            parseDefaultScopeReply(reply)?.let { parsed ->
                defaultScopeName = when (parsed) {
                    ParsedDefaultScope.Cleared -> null
                    is ParsedDefaultScope.Named -> parsed.name
                }
                defaultScopeLoaded = true
            }
        } catch (_: RemoteNodeError) {
            // Leave defaultScopeLoaded as-is; the Load control can retry.
        } finally {
            isLoadingDefaultScope = false
        }
    }

    override suspend fun toggleRegionFlood(name: String): RegionOpResult {
        if (!regionsLoaded) return RegionOpResult.NotLoaded
        val entry = regions.firstOrNull { it.name == name } ?: return RegionOpResult.UnknownRegion
        val currentlyAllowed = entry.floodAllowed
        val command = if (currentlyAllowed) "region denyf $name" else "region allowf $name"
        return try {
            if (transport.commandSucceeded(command)) {
                val liveIndex = regions.indexOfFirst { it.name == name }
                if (liveIndex >= 0) {
                    regions = regions.mapIndexed { index, row ->
                        if (index == liveIndex) row.copy(floodAllowed = !currentlyAllowed) else row
                    }
                    hasUnsavedRegionChanges = true
                }
                RegionOpResult.Applied
            } else {
                RegionOpResult.UnknownRegion
            }
        } catch (e: RemoteNodeError) {
            RegionOpResult.TransportError(e)
        }
    }

    override suspend fun setDefaultScope(name: String?): RegionOpResult {
        if (!supportsRegionDefaultScope) return RegionOpResult.NotLoaded
        if (!regionsLoaded) return RegionOpResult.NotLoaded
        if (name == WILDCARD_REGION_NAME) return RegionOpResult.Applied
        if (name == defaultScopeName) return RegionOpResult.Applied
        val argument = name ?: FIRMWARE_NULL_TOKEN
        return try {
            val response = sendRawCommand("region default $argument", RepeaterCliTransport.DEFAULT_TIMEOUT)
            if (response.contains(DEFAULT_SCOPE_SET_REPLY_MARKER)) {
                defaultScopeName = name
                defaultScopeLoaded = true
                if (name != null) {
                    regions = regions.map { if (it.name == name) it.copy(floodAllowed = true) else it }
                }
                RegionOpResult.Applied
            } else {
                RegionOpResult.UnknownRegion
            }
        } catch (e: RemoteNodeError) {
            RegionOpResult.TransportError(e)
        }
    }

    internal suspend fun addRegion(name: String, parent: RepeaterRegionEntry.Parent): RegionOpResult {
        if (!regionsLoaded) return RegionOpResult.NotLoaded
        return submitAddedRegion(name.trim(), parent)
    }

    private suspend fun submitAddedRegion(trimmed: String, parent: RepeaterRegionEntry.Parent): RegionOpResult {
        val names = regions.map { it.name }
        val error = RegionNameValidator.validate(trimmed, names)
        val resolved = resolvedAddParent(parent)
        val missingParent = resolved is RepeaterRegionEntry.Parent.Named &&
            regions.none { it.name == resolved.name }
        if (error != null || missingParent) {
            return when {
                error is RegionNameValidator.ValidationError.Empty -> RegionOpResult.EmptyName
                else -> RegionOpResult.AddValidationFailed
            }
        }
        val parentName: String
        val command: String
        when (resolved) {
            RepeaterRegionEntry.Parent.Unscoped -> {
                parentName = WILDCARD_REGION_NAME
                command = "region put $trimmed"
            }
            is RepeaterRegionEntry.Parent.Named -> {
                parentName = resolved.name
                command = "region put $trimmed ${resolved.name}"
            }
        }
        return try {
            if (transport.commandSucceeded(command)) {
                regions = insertAddedRegion(regions, trimmed, parentName)
                hasUnsavedRegionChanges = true
                RegionOpResult.Applied
            } else {
                RegionOpResult.AddFailed
            }
        } catch (e: RemoteNodeError) {
            RegionOpResult.TransportError(e)
        }
    }

    override suspend fun removeRegion(name: String): RegionOpResult {
        if (!regionsLoaded) return RegionOpResult.NotLoaded
        val wasDefault = defaultScopeName == name
        return try {
            val response = sendCommand("region remove $name", RepeaterCliTransport.DEFAULT_TIMEOUT)
            when {
                CLIResponse.parse(response) is CLIResponse.Ok -> applyRemovedRegion(name, wasDefault)
                response.contains("not empty") -> RegionOpResult.NotEmpty
                else -> RegionOpResult.RemoveFailed
            }
        } catch (e: RemoteNodeError) {
            RegionOpResult.TransportError(e)
        }
    }

    private suspend fun applyRemovedRegion(name: String, wasDefault: Boolean): RegionOpResult {
        regions = regions.filterNot { it.name == name }
        hasUnsavedRegionChanges = true
        if (wasDefault && supportsRegionDefaultScope) {
            val clearReply = sendRawCommand(
                "region default $FIRMWARE_NULL_TOKEN",
                RepeaterCliTransport.DEFAULT_TIMEOUT,
            )
            if (!clearReply.contains(DEFAULT_SCOPE_SET_REPLY_MARKER)) {
                return RegionOpResult.UnknownRegion
            }
            defaultScopeName = null
        }
        return RegionOpResult.Applied
    }

    override suspend fun saveRegions(): RegionOpResult {
        if (!regionsLoaded && !hasUnsavedRegionChanges) return RegionOpResult.NotLoaded
        return try {
            if (transport.commandSucceeded("region save")) {
                hasUnsavedRegionChanges = false
                RegionOpResult.Applied
            } else {
                RegionOpResult.SaveFailed
            }
        } catch (e: RemoteNodeError) {
            RegionOpResult.TransportError(e)
        }
    }

    override fun handleLateResponse(response: String): Boolean {
        if (helper.handleCommonLateResponse(response)) return true
        if (regionsError && originalRegions == null) {
            val parsed = RegionNameValidator.parseRegionTree(response)
            if (parsed.isNotEmpty()) {
                regions = parsed
                originalRegions = parsed
                regionsError = false
                return true
            }
        }
        return false
    }

    private companion object {
        const val DEFAULT_SCOPE_REPLY_MARKER: String = "default scope is"
        const val DEFAULT_SCOPE_SET_REPLY_MARKER: String = "default scope is now"

        fun parseDefaultScopeReply(response: String): ParsedDefaultScope? {
            val last = response.split('\n').map { it.trim() }.lastOrNull { it.isNotEmpty() } ?: return null
            var line = last
            if (line.startsWith(">")) line = line.drop(1).trim()
            return scopeFromReplyLine(line)
        }

        fun scopeFromReplyLine(line: String): ParsedDefaultScope? {
            if (!line.contains(DEFAULT_SCOPE_REPLY_MARKER, ignoreCase = true)) return null
            val lastToken = line.split(' ').lastOrNull { it.isNotEmpty() } ?: return null
            return when (lastToken) {
                FIRMWARE_NULL_TOKEN, WILDCARD_REGION_NAME -> ParsedDefaultScope.Cleared
                else -> ParsedDefaultScope.Named(lastToken)
            }
        }
    }
}

private fun insertAddedRegion(
    regions: List<RepeaterRegionEntry>,
    addedName: String,
    parentName: String,
): List<RepeaterRegionEntry> {
    val liveParentIndex = regions.indexOfFirst { it.name == parentName }
    val depth = if (liveParentIndex >= 0) regions[liveParentIndex].depth + 1 else 1
    val newEntry = RepeaterRegionEntry(
        name = addedName,
        parentName = parentName,
        depth = depth,
        floodAllowed = true,
        isHome = false,
    )
    return if (liveParentIndex >= 0) {
        val parentDepth = regions[liveParentIndex].depth
        var insertIndex = liveParentIndex + 1
        while (insertIndex < regions.size && regions[insertIndex].depth > parentDepth) {
            insertIndex += 1
        }
        regions.toMutableList().also { it.add(insertIndex, newEntry) }
    } else {
        regions + newEntry
    }
}
