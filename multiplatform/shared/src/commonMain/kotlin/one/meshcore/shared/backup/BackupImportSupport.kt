package one.meshcore.shared.backup

import one.meshcore.shared.model.Uuid
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/** Mints a fresh surrogate id for a locally-inserted row (never the backup's own id; see callers). */
fun newLocalId(): Uuid = Uuid.random()

/** Base64 key for a publicKey-keyed lookup map, since `ByteArray` lacks structural equality. */
@OptIn(ExperimentalEncodingApi::class)
fun ByteArray.toBase64Key(): String = Base64.encode(this)
