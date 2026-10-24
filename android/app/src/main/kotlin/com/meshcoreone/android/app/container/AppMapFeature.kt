// PortedFrom: MC1/Views/Map/MapViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.maps.ContactMapType
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.map.MapFeatureContent
import com.meshcoreone.android.feature.map.MapFeatureDataSource
import com.meshcoreone.android.feature.map.MapFeatureDependencies
import com.meshcoreone.android.feature.map.MapFeatureMarker
import com.meshcoreone.android.feature.map.MapSelection
import com.meshcoreone.android.feature.map.MapFeatureActions
import com.meshcoreone.android.feature.map.MapFeatureUpdate
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine

internal fun AppContainer.createMapFeatureDependencies(): MapFeatureDependencies =
    MapFeatureDependencies(
        dataSource = MapFeatureDataSource {
            val radioId = appState.currentRadioId ?: return@MapFeatureDataSource MapFeatureContent()
            val store = appState.offlineDataStore ?: return@MapFeatureDataSource MapFeatureContent()
            val version = appState.servicesVersion.value
            val contacts = store.fetchContacts(radioId)
            val contactKeys = contacts.mapTo(mutableSetOf()) { it.publicKey }
            val contactMarkers = contacts.filter { it.hasLocation }.map { contact ->
                MapFeatureMarker(
                    marker = MapMarker(
                        id = contact.id,
                        position = GeoPoint(contact.latitude, contact.longitude),
                        style = contact.type.pinStyle,
                        label = contact.nickname?.takeIf(String::isNotBlank) ?: contact.name,
                    ),
                    type = contact.type.mapType,
                    isFavorite = contact.isFavorite,
                    isDiscovered = false,
                    selection = MapSelection.Contact(contact),
                )
            }
            val discoveredMarkers = store.fetchDiscoveredNodes(radioId)
                .filter { it.hasLocation && it.publicKey !in contactKeys }
                .map { node ->
                    MapFeatureMarker(
                        marker = MapMarker(
                            id = node.id,
                            position = GeoPoint(node.latitude, node.longitude),
                            style = node.nodeType.pinStyle,
                            label = node.name,
                        ),
                        type = node.nodeType.mapType,
                        isFavorite = false,
                        isDiscovered = true,
                        selection = MapSelection.Discovered(node),
                    )
                }
            if (radioId != appState.currentRadioId || version != appState.servicesVersion.value) {
                throw CancellationException("Map radio generation changed")
            }
            MapFeatureContent(markers = contactMarkers + discoveredMarkers)
        },
        offlineMaps = offlineMaps,
        updates = combine(
            connectionManager.snapshot, appState.servicesVersion, appState.contactsVersion, appState.sessionStateChangeCount,
        ) { connection, services, _, _ ->
            MapFeatureUpdate("${appState.currentRadioId}:${connection.token}:$services")
        },
        actions = object : MapFeatureActions {
            private fun requireRadio(radio: com.meshcoreone.android.core.model.RadioId) {
                if (radio != appState.currentRadioId) throw ContactServiceError.NotConnected()
            }

            override fun openContact(contact: ContactDTO) {
                requireRadio(contact.radioId)
                navigation.navigateToContactDetail(contact)
            }

            override fun openChat(contact: ContactDTO) {
                requireRadio(contact.radioId)
                navigation.navigateToChat(contact)
            }

            override suspend fun addDiscovered(node: DiscoveredNodeDTO) {
                requireRadio(node.radioId)
                val session = sessions.current?.takeIf { it.token.radioId == node.radioId }
                    ?: throw ContactServiceError.NotConnected()
                session.contactService.addOrUpdateContact(
                    node.radioId,
                    ContactFrame(
                        publicKey = node.publicKey, type = node.nodeType, typeRawValue = node.typeRawValue,
                        flags = 0u, outPathLength = node.outPathLength, outPath = node.outPath, name = node.name,
                        lastAdvertTimestamp = node.lastAdvertTimestamp, latitude = node.latitude, longitude = node.longitude,
                        lastModified = java.time.Instant.now().epochSecond.toUInt(),
                    ),
                )
                if (sessions.current !== session) throw ContactServiceError.NotConnected()
                appState.bumpContactsVersion()
            }
        },
    )

private val ContactType.mapType: ContactMapType
    get() = when (this) {
        ContactType.CHAT -> ContactMapType.CHAT
        ContactType.REPEATER -> ContactMapType.REPEATER
        ContactType.ROOM -> ContactMapType.ROOM
    }

private val ContactType.pinStyle: MapPinStyle
    get() = when (this) {
        ContactType.CHAT -> MapPinStyle.CONTACT_CHAT
        ContactType.REPEATER -> MapPinStyle.CONTACT_REPEATER
        ContactType.ROOM -> MapPinStyle.CONTACT_ROOM
    }
