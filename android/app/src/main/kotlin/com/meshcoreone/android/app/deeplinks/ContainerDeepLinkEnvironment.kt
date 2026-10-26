// AndroidOnly: WP-405 Binds URI routing to the WP-302 navigation shell and WP-303 process graph.
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.navigation.ChannelLinkRequest
import com.meshcoreone.android.app.navigation.ContactLinkRequest
import com.meshcoreone.android.app.navigation.HashtagJoinRequest
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes

class ContainerDeepLinkEnvironment(
    private val container: AppContainer,
    private val navigation: NavigationCoordinator,
) : AppDeepLinkEnvironment {
    override var selectedTab: AppTab
        get() = navigation.state.value.selectedTab
        set(value) = navigation.selectTab(value)

    override val currentRadioId: RadioId? get() = container.appState.currentRadioId
    override val connectedDevicePublicKey: Bytes? get() = container.appState.connectedDevice?.publicKey

    override fun navigateToMap(latitude: Double, longitude: Double) = navigation.navigateToMap(latitude, longitude)
    override fun navigateToContactDetail(contact: ContactDTO) = navigation.navigateToContactDetail(contact)
    override fun navigateToChannel(channel: ChannelDTO) = navigation.navigateToChannel(channel)

    override fun stageContactConfirmation(link: MeshCoreDeepLink.Contact) =
        navigation.stageContactLink(ContactLinkRequest(link.name, link.publicKey, link.contactType))

    override fun stageChannelConfirmation(link: MeshCoreDeepLink.Channel) =
        navigation.stageChannelLink(
            ChannelLinkRequest(link.name, link.secret, link.regionScope, link.hasHashtagSecretMismatch),
        )

    override fun stageHashtagConfirmation(link: MeshCoreDeepLink.Hashtag) =
        navigation.stageHashtag(HashtagJoinRequest(link.fullName))

    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? =
        container.appState.offlineDataStore?.fetchContact(radioId, publicKey)

    override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> =
        container.appState.offlineDataStore?.fetchChannels(radioId).orEmpty()
}
