package one.meshcore.shared.channel

import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.MeshCoreDeepLink
import one.meshcore.shared.navigation.MeshCoreUrlParser
import one.meshcore.shared.navigation.percentEncodeQueryValue

/**
 * The `meshcore://channel/add?name=&secret=` deep-link / QR URI — the string a channel QR encodes.
 * [export] is the producer side; [parse] delegates to the shared [MeshCoreUrlParser] (the decode side
 * used by decode so emit and decode can never fork. Mirrors the reuse pattern of `ContactCardUri`.
 *
 * The `name` value is percent-encoded with the URL query-allowed set **minus `&` and `=`**: those two
 * are escaped (`%26` / `%3D`) so a crafted name cannot inject its own `secret` and downgrade the
 * channel to an attacker-chosen key. `?` and `:` stay literal; a name's literal `+` is emitted as
 * `%2B`, because stock MeshCore QRs use raw `+` for spaces.
 *
 * This escaping of `&`/`=` follows the same discipline as the contact URI and is the safe, injection-
 * proof behavior. It is byte-identical to the current iOS channel producer for every name that does
 * not contain `&` or `=`; the two diverge only on those adversarial names, where the iOS producer
 * (`CreatePrivateChannelView` / `ChannelInfoSheet`, both raw `.urlQueryAllowed`) leaves them literal.
 */
object ChannelUri {
    private const val PREFIX = "${MeshCoreUrlParser.SCHEME}://channel/add"
    private const val NAME_KEY = "name"
    private const val SECRET_KEY = "secret"
    private const val REGION_SCOPE_KEY = "region_scope"

    /**
     * Builds the channel URI. Only the `name` value can contain non-hex characters, so only it is
     * percent-encoded; the 16-byte secret is always uppercase hex (32 chars, no separators). A
     * [ChannelFloodScope.Region] with a non-empty name appends a percent-encoded `region_scope` item so a
     * region-scoped channel keeps its region when shared, matching the iOS `exportChannelURI` producer;
     * `Inherit` / `AllRegions` emit no region item.
     */
    fun export(name: String, secret: ByteArray, floodScope: ChannelFloodScope = ChannelFloodScope.Inherit): String {
        val base = "$PREFIX?$NAME_KEY=${percentEncodeQueryValue(name)}&$SECRET_KEY=${secret.toUppercaseHex()}"
        val region = (floodScope as? ChannelFloodScope.Region)?.name?.takeIf { it.isNotEmpty() } ?: return base
        return "$base&$REGION_SCOPE_KEY=${percentEncodeQueryValue(region)}"
    }

    /** Decodes a channel URI, reusing the shared parser. Returns `null` on any validation failure. */
    fun parse(string: String): MeshCoreDeepLink.Channel? = MeshCoreUrlParser.parseChannelUrl(string)
}
