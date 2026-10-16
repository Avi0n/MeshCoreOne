package one.meshcore.shared.remotenode

/** Owner info and the commands that load and apply it. */
interface NodeContactInfo {
    var ownerInfo: String?
    val originalOwnerInfo: String?
    val contactInfoError: Boolean
    val contactInfoLoaded: Boolean

    /** Gated on loaded so an empty pre-fetch value can't enable Apply and wipe the node's owner info. */
    val contactInfoSettingsModified: Boolean
    val ownerInfoCharCount: Int
    val isOwnerInfoTooLong: Boolean

    suspend fun fetchContactInfo()

    suspend fun applyContactInfoSettings(): SettingsApplyResult
}

internal class NodeContactSettings(private val exchange: NodeSettingsExchange, private val hooks: NodeSettingsHooks) :
    NodeContactInfo {
    override var ownerInfo: String? = null
    override var originalOwnerInfo: String? = null
        internal set
    override var contactInfoError: Boolean = false
        private set
    override val contactInfoLoaded: Boolean get() = originalOwnerInfo != null
    override val contactInfoSettingsModified: Boolean
        get() = contactInfoLoaded && ownerInfo != originalOwnerInfo
    override val ownerInfoCharCount: Int get() = (ownerInfo ?: "").length
    override val isOwnerInfoTooLong: Boolean
        get() = ownerInfoCharCount > NodeSettingsValidation.OWNER_INFO_MAX_LENGTH

    override suspend fun fetchContactInfo() {
        if (originalOwnerInfo == null) hooks.onPreFetchNodeInfo?.invoke()
        if (originalOwnerInfo != null) return
        contactInfoError = false
        val timedOut = runSettingsFetch {
            (CLIResponse.parse(exchange.sendAndWait("get owner.info"), "get owner.info") as? CLIResponse.OwnerInfo)
                ?.let {
                    val display = NodeSettingsResponseParser.displayOwnerInfo(it.text)
                    adoptSettingsReading(ownerInfo, originalOwnerInfo, display) { reading ->
                        ownerInfo = reading
                        originalOwnerInfo = reading
                    }
                }
        }
        if (timedOut) contactInfoError = true
    }

    override suspend fun applyContactInfoSettings(): SettingsApplyResult = try {
        val sent = ownerInfo ?: ""
        val wire = NodeSettingsResponseParser.wireOwnerInfo(sent)
        if (exchange.commandSucceeded("set owner.info $wire")) {
            originalOwnerInfo = sent
            SettingsApplyResult.Applied
        } else {
            SettingsApplyResult.PartialFailure
        }
    } catch (e: RemoteNodeError) {
        SettingsApplyResult.TransportError(e)
    }
}
