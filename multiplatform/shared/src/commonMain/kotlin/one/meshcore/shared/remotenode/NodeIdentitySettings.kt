package one.meshcore.shared.remotenode

/** Name and coordinates, plus the commands that load and apply them. */
interface NodeIdentityFields {
    var name: String?
    var latitude: Double?
    var longitude: Double?
    val originalName: String?
    val originalLatitude: Double?
    val originalLongitude: Double?
    val identityError: Boolean
    val identityValidation: NodeSettingsValidation.IdentityValidation?
    val identityLoaded: Boolean
    val identitySettingsModified: Boolean

    suspend fun fetchIdentity()

    suspend fun applyIdentitySettings(): SettingsApplyResult
}

internal class NodeIdentitySettings(private val exchange: NodeSettingsExchange, private val hooks: NodeSettingsHooks) :
    NodeIdentityFields {
    override var name: String? = null
    override var latitude: Double? = null
    override var longitude: Double? = null
    override var originalName: String? = null
        internal set
    override var originalLatitude: Double? = null
        internal set
    override var originalLongitude: Double? = null
        internal set
    override var identityError: Boolean = false
        internal set
    override var identityValidation: NodeSettingsValidation.IdentityValidation? = null
        private set
    override val identityLoaded: Boolean get() = originalLatitude != null || originalLongitude != null
    override val identitySettingsModified: Boolean
        get() = (name != null && name != originalName) ||
            (latitude != null && latitude != originalLatitude) ||
            (longitude != null && longitude != originalLongitude)
    internal val identitySectionComplete: Boolean
        get() = originalName != null && originalLatitude != null && originalLongitude != null

    override suspend fun fetchIdentity() {
        identityError = false
        if (originalName == null) hooks.onPreFetchNodeInfo?.invoke()
        val nameTimedOut = if (originalName == null) {
            runSettingsFetch {
                (CLIResponse.parse(exchange.sendAndWait("get name"), "get name") as? CLIResponse.Name)?.let {
                    adoptSettingsReading(name, originalName, it.text) { reading ->
                        name = reading
                        originalName = reading
                    }
                }
            }
        } else {
            false
        }
        val latitudeTimedOut = runSettingsFetch {
            (CLIResponse.parse(exchange.sendAndWait("get lat"), "get lat") as? CLIResponse.Latitude)?.let {
                adoptSettingsReading(latitude, originalLatitude, it.value) { reading ->
                    latitude = reading
                    originalLatitude = reading
                }
            }
        }
        val longitudeTimedOut = runSettingsFetch {
            (CLIResponse.parse(exchange.sendAndWait("get lon"), "get lon") as? CLIResponse.Longitude)?.let {
                adoptSettingsReading(longitude, originalLongitude, it.value) { reading ->
                    longitude = reading
                    originalLongitude = reading
                }
            }
        }
        if (nameTimedOut || latitudeTimedOut || longitudeTimedOut) identityError = true
    }

    override suspend fun applyIdentitySettings(): SettingsApplyResult {
        val validation = NodeSettingsValidation.validateIdentityFields(name, latitude, longitude)
        identityValidation = validation
        if (validation.hasErrors) return SettingsApplyResult.InvalidFields
        val currentName = name
        val currentLat = latitude
        val currentLon = longitude
        return try {
            var allOk = true
            if (currentName != null && currentName != originalName) {
                if (exchange.commandSucceeded("set name $currentName")) originalName = currentName else allOk = false
            }
            if (currentLat != null && currentLat != originalLatitude) {
                if (exchange.commandSucceeded("set lat $currentLat")) originalLatitude = currentLat else allOk = false
            }
            if (currentLon != null && currentLon != originalLongitude) {
                if (exchange.commandSucceeded("set lon $currentLon")) originalLongitude = currentLon else allOk = false
            }
            if (allOk) SettingsApplyResult.Applied else SettingsApplyResult.PartialFailure
        } catch (e: RemoteNodeError) {
            SettingsApplyResult.TransportError(e)
        }
    }
}
