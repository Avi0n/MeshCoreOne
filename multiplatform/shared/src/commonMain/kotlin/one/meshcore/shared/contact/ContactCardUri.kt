package one.meshcore.shared.contact

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.navigation.MeshCoreUrlParser
import one.meshcore.shared.navigation.percentEncodeQueryValue

/**
 * The `meshcore://contact/add?name=&public_key=&type=` deep-link / QR URI — the string a contact QR
 * encodes. [export] is byte-identical to the iOS `ContactService.exportContactURI`; [parse] delegates
 * to the shared [MeshCoreUrlParser] so emit and decode can
 * never fork.
 *
 * The `name` value is percent-encoded by [percentEncodeQueryValue]: uppercase `%XX` for every UTF-8
 * byte outside the URL query-allowed set minus `&` and `=`. Those two are escaped so a crafted name
 * cannot inject its own `public_key`/`type` and spoof the parsed identity. `?` and `:` stay literal.
 * A literal `+` is emitted as `%2B`: stock MeshCore QRs use raw `+` for spaces.
 */
object ContactCardUri {
    private const val PREFIX = "${MeshCoreUrlParser.SCHEME}://contact/add"
    private const val NAME_KEY = "name"
    private const val PUBLIC_KEY_KEY = "public_key"
    private const val TYPE_KEY = "type"

    /**
     * Builds the contact URI. Only the `name` value can contain non-hex, non-digit characters, so only
     * it is percent-encoded; the hex public key and decimal type are always literal.
     */
    fun export(name: String, publicKey: ByteArray, type: ContactType): String =
        "$PREFIX?$NAME_KEY=${percentEncodeQueryValue(name)}" +
            "&$PUBLIC_KEY_KEY=${publicKey.toUppercaseHex()}" +
            "&$TYPE_KEY=${type.raw}"

    /** Decodes a contact URI, reusing the shared parser. Returns `null` on any validation failure. */
    fun parse(string: String): ContactCard? = MeshCoreUrlParser.parseContactUrl(string)?.toContactCard()
}

/** Adapts the shared deep-link parse result into the contact-package card model. */
fun MeshCoreDeepLink.Contact.toContactCard(): ContactCard =
    ContactCard(name = name, publicKey = publicKey, contactType = type)
