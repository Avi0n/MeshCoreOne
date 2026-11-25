# Reactions Interoperability Guide

How to implement emoji reactions compatible with MeshCore One.

## Message Hashing

Reactions target messages by a hash of their content. Every node that received the original message can compute the same hash independently.

1. Concatenate the message's UTF-8 text bytes with the sender timestamp as a 4-byte little-endian unix time
2. SHA-256 the result
3. Take the first 5 bytes (40 bits)
4. Encode as 8 characters of [Crockford Base32](https://www.crockford.com/base32.html)

```
SHA-256( UTF-8(text) + LE32(timestamp) )
  → first 5 bytes → Crockford Base32 → "b45pc4ek"
```

Use the **sender timestamp from the packet**, not the local receive time, so all nodes agree on the hash. Group text messages and plain text messages (direct messages) use this.

Room posts and group datagrams have no sender timestamp. Use a `0x01` byte instead:

```
SHA-256( 0x01 + bytes )
  → first 5 bytes → Crockford Base32
```

- **Room post.** `bytes` are the post text after the 4-byte sender pubkey prefix (`txt_type` `0x02`).
- **Group datagram.** `bytes` are the `data` field.

Two copies with the same hash and sender: attach to the newest stored copy. A reaction that arrives before any copy attaches to the first copy that arrives; later copies don't take it.

### Why Crockford Base32?

Crockford Base32 maps 5 bytes to exactly 8 characters using only alphanumerics — no special characters that could conflict with the wire format delimiters (`@`, `[`, `]`, `\n`). Hex would need 10 characters for the same data. Base64 fits in 8 but uses `+`, `/`, `=`. The parser is case-insensitive and normalizes common substitutions (O → 0, I/L → 1).

## Wire Format

Reactions are sent as regular mesh messages with a specific text format.

**Group text** (includes the sender name to disambiguate identical messages from different users):
```
@[{sender name}]{emoji}\n{hash}
```
Example: `@[AlphaNode]👍\nb45pc4ek`

`{sender name}` is the name from the group text body, `<sender name>: <message body>`. The `{emoji}` field is a single extended grapheme cluster. A remainder that merely starts with an emoji is ordinary chat, not a reaction. Queue unmatched bodies that do match this format; do not persist them as chat.

Until MC1 v1.4.1, group text reactions put the emoji first: `{emoji}@[{sender name}]\n{hash}`. Accept both orders for now so older clients still match. We'll drop the old one once enough apps have been updated.

**Plain text message** (direct message, two-party, sender is unambiguous):
```
{emoji}\n{hash}
```
Example: `👍\nb45pc4ek`

**Room post** (sender field is the sender pubkey prefix):
```
@[{sender pubkey prefix}]{emoji}\n{hash}
```
Example: `@[A1B2C3D4]👍\nb45pc4ek`

Sent as a room post. The post text is the line above. `{hash}` is the no-timestamp hash of the target post. Match posts in that room, including the local node's own posts. Ignore the post timestamp the room server wrote onto the reaction. No emoji-first form.

## Receiving Reactions

On receive, check whether the message matches the reaction format before treating it as a regular message.

**If it's a reaction:** look up the target message by hash. If the target hasn't arrived yet (out-of-order delivery is common on mesh), queue the reaction and match it when the target appears. Use the no-timestamp hash for a room post or a group datagram, and the normal hash for everything else.

**If it's a regular message:** compute its hash and index it so future reactions can find it. Also check the pending queue for reactions already waiting on this hash. Index room posts and group datagrams with the no-timestamp hash, or a reaction that got there first sits in the queue until it falls off.

Group text lookup can scan about five minutes around the reaction. Room posts and group datagrams match the saved messages for that room or channel.

Deduplicate by `(target hash, sender, emoji)`. A node may relay the same reaction more than once. For a room reaction, `sender` is the reacting author's pubkey prefix.
