import Foundation

/// Firmware device-error codes the send-retry classifier treats as transient.
///
/// These are MC1's retry-policy interpretations of `MeshCoreError.deviceError(code:)`
/// values surfaced by the radio. They live here (not in MeshCore) because the
/// transient-vs-terminal taxonomy is an MC1 send-queue policy, not a
/// protocol-level constant.
public enum FirmwareDeviceErrorCode {
    /// `TABLE_FULL` on the direct-message path. The radio's outbound DM pool
    /// is briefly exhausted; the send queue parks and retries.
    public static let directMessageTableFull: UInt8 = 3

    /// `NOT_FOUND` on the channel broadcast path. Firmware emits this for two
    /// distinct failures that share a wire code:
    /// - **Pool exhaustion** (transient): `createGroupDatagram` returned NULL because
    ///   the radio's packet pool is briefly full. The send queue parks and retries.
    /// - **Stale channel index** (terminal): the user deleted the channel between
    ///   enqueue and drain. `ChatSendQueueService` disambiguates by calling
    ///   `ChannelService.fetchChannel(index:)`; if the device confirms the
    ///   channel is gone the envelope is dropped and the message lands in
    ///   `.failed` so the user can resend into a different channel.
    public static let channelMessageNotFound: UInt8 = 2

    /// `RESP_CODE_NO_MORE_MESSAGES` surfaced on the remote-node section path. The
    /// companion radio's offline-message queue is empty, meaning the awaited
    /// repeater/room reply hasn't arrived yet; the section request backs off and
    /// retries within its shared timeout budget.
    public static let remoteNodeNoResponseYet: UInt8 = 10
}
