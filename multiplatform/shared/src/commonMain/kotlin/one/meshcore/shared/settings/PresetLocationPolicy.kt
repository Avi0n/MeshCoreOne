package one.meshcore.shared.settings

import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.map.LocationAuthorizationStatus
import one.meshcore.shared.map.RegionalAreas

/**
 * Pure decisions for Settings → Radio → Location. GPS resolve, authorization wait, and the
 * expand-vs-push layout all go through here so the Compose session stays a thin host.
 */
object PresetLocationPolicy {
    enum class ResolveKind {
        APPEAR,
        USER_INITIATED,
    }

    enum class UseMyLocationAction {
        RESOLVE,
        WAIT_FOR_AUTHORIZATION,
        OPEN_SETTINGS,
    }

    enum class AfterAuthorizationWait {
        RESOLVE,
        OPEN_SETTINGS,
        NONE,
    }

    fun isIncomplete(selection: RegionSelection?): Boolean {
        if (selection == null) return true
        return RegionalAreas.showsSubdivisionPicker(selection.countryCode) &&
            selection.administrativeAreaCode == null
    }

    fun shouldExpandOnRadio(authorized: Boolean, selection: RegionSelection?): Boolean =
        !authorized && isIncomplete(selection)

    fun shouldResolveOnAppear(authorized: Boolean, source: RegionSource?): Boolean =
        authorized && source != RegionSource.MANUAL

    fun shouldCommitAppearResult(currentSource: RegionSource?): Boolean = currentSource != RegionSource.MANUAL

    fun committedSelection(current: RegionSelection?, result: RegionSelection?, kind: ResolveKind): RegionSelection? {
        if (result == null) return current
        return when (kind) {
            ResolveKind.APPEAR -> if (shouldCommitAppearResult(current?.source)) result else current
            ResolveKind.USER_INITIATED -> result
        }
    }

    fun useMyLocationAction(status: LocationAuthorizationStatus): UseMyLocationAction = when (status) {
        LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE,
        LocationAuthorizationStatus.AUTHORIZED_ALWAYS,
        -> UseMyLocationAction.RESOLVE
        LocationAuthorizationStatus.NOT_DETERMINED -> UseMyLocationAction.WAIT_FOR_AUTHORIZATION
        LocationAuthorizationStatus.DENIED,
        LocationAuthorizationStatus.RESTRICTED,
        -> UseMyLocationAction.OPEN_SETTINGS
    }

    fun actionAfterAuthorizationWait(status: LocationAuthorizationStatus): AfterAuthorizationWait = when (status) {
        LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE,
        LocationAuthorizationStatus.AUTHORIZED_ALWAYS,
        -> AfterAuthorizationWait.RESOLVE
        LocationAuthorizationStatus.DENIED,
        LocationAuthorizationStatus.RESTRICTED,
        -> AfterAuthorizationWait.OPEN_SETTINGS
        LocationAuthorizationStatus.NOT_DETERMINED -> AfterAuthorizationWait.NONE
    }

    fun shouldPresentLookupMiss(kind: ResolveKind, requestInProgress: Boolean): Boolean =
        kind == ResolveKind.USER_INITIATED && !requestInProgress
}
