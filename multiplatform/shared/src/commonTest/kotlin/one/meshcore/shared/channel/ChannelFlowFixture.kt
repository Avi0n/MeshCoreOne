package one.meshcore.shared.channel

import one.meshcore.shared.model.Uuid

/**
 * Builds a create/join controller's provider bundle around a live [ChannelService] over the shared fakes. A
 * null [service] / [radioId] models the disconnected state the controllers guard on.
 */
internal class ChannelFlowFixture(
    val session: FakeChannelSession = FakeChannelSession(),
    val store: InMemoryChannelStore = InMemoryChannelStore(),
    val radioId: Uuid? = ChannelTestIds.RADIO,
    val maxChannels: Int? = 8,
    availableSlots: List<Int> = listOf(1, 2, 3),
) {
    val service: ChannelService = ChannelService(session = session, store = store)
    private val slots = availableSlots

    val serviceProvider: () -> ChannelService? = { serviceOrNull }
    val radioProvider: () -> Uuid? = { radioId }
    val maxChannelsProvider: () -> Int? = { maxChannels }
    val slotsProvider: () -> List<Int> = { slots }

    /** Flip to model a disconnected graph mid-test. */
    var serviceOrNull: ChannelService? = service

    /** Seeds an occupied slot in the store so slot/existing-channel computations see it. */
    suspend fun seedChannel(index: Int, name: String, secretByte: Int = 0xCD) = store.seedChannel(
        channelDto(
            id = ChannelTestIds.fresh(),
            radioID = ChannelTestIds.RADIO,
            index = index,
            name = name,
            secretByte = secretByte,
        ),
    )
}
