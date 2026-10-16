package one.meshcore.shared

import one.meshcore.shared.model.Uuid

private const val TEST_UUID_TAIL_WIDTH = 12

/** A zero-prefix UUID whose last group is [counter], padded to 12 digits. */
internal fun sequentialTestUuid(counter: Int): Uuid =
    Uuid.normalized("00000000-0000-0000-0000-" + counter.toString().padStart(TEST_UUID_TAIL_WIDTH, '0'))
