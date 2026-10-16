# Reactions Interoperability Guide

How to implement emoji reactions compatible with MeshCore One.

## Message Hashing

Reactions target messages by a hash of their content. Every node that received the original message can compute the same hash independently.

1. Concatenate the message's UTF-8 text bytes with the sender's original timestamp as a little-endian `UInt32`
2. SHA-256 the result
3. Take the first 5 bytes (40 bits)
4. Encode as 8 characters of [Crockford Base32](https://www.crockford.com/base32.html)

```
SHA-256( UTF-8(text) + LE(UInt32(senderTimestamp)) )
  → first 5 bytes → Crockford Base32 → "b45pc4ek"
```

Use the **sender's original timestamp**, not your local receive time in order for all nodes to agree on the hash.

### Why Crockford Base32?

Crockford Base32 maps 5 bytes to exactly 8 characters using only alphanumerics — no special characters that could conflict with the wire format delimiters (`@`, `[`, `]`, `\n`). Hex would need 10 characters for the same data. Base64 fits in 8 but uses `+`, `/`, `=`. The parser is case-insensitive and normalizes common substitutions (O → 0, I/L → 1).

## Wire Format

Reactions are sent as regular mesh messages with a specific text format.

**Channel** (includes target sender to disambiguate identical messages from different users):
```
@[{targetSenderName}]{emoji}\n{hash}
```
Example: `@[AlphaNode]👍\nb45pc4ek`

The `{emoji}` field is a single extended grapheme cluster. A remainder that merely starts with an emoji is ordinary chat, not a reaction. Queue unmatched bodies that do match this format; do not persist them as chat.

Until 1.4.1, channel reactions put the emoji first: `{emoji}@[{targetSenderName}]\n{hash}`. Accept both orders for now so older clients still match. We'll drop the old one once enough apps have been updated.

**DM** (two-party, sender is unambiguous):
```
{emoji}\n{hash}
```
Example: `👍\nb45pc4ek`

## Receiving Reactions

When you receive a message, check if it matches the reaction format before treating it as a regular message.

**If it's a reaction:** look up the target message by hash. If the target hasn't arrived yet (out-of-order delivery is common on mesh), queue the reaction and match it when the target appears.

**If it's a regular message:** compute its hash and index it so future reactions can find it. Also check your pending queue for reactions already waiting on this hash.

Deduplicate by `(targetHash, senderName, emoji)` — a node may relay the same reaction more than once.
