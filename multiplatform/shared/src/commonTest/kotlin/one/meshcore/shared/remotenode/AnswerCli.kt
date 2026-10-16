package one.meshcore.shared.remotenode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.test.assertTrue

/** Reply to the latest CLI command and keep the wire prefix the radio is waiting on. */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun TestScope.answerCli(
    session: FakeNodeSession,
    publicKey: ByteArray,
    commandSuffix: String,
    body: String,
) {
    val command = session.sendCommandCalls.last().second
    assertTrue(command.endsWith(commandSuffix))
    val wirePrefix = command.removeSuffix(commandSuffix)
    session.emitCliResponse("$wirePrefix$body", publicKey.copyOf(6))
    runCurrent()
}
