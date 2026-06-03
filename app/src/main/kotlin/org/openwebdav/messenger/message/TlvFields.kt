package org.openwebdav.messenger.message

/**
 * The decoded TLV field region of a §8 message (`docs/protocol/webdav-layout.md` §8.2.1): an ordered
 * read of exactly `field-count` triples into a tag→value map, with duplicate-tag rejection. A `null`
 * decode result means a malformed field region (overrun, duplicate tag, or count mismatch) — the
 * caller maps that to a typed [ParseResult.Rejected], never a throw.
 */
internal class TlvFields private constructor(private val values: Map<Byte, ByteArray>) {
    /** The value for [tag], or `null` if the (optional) field was absent. */
    fun get(tag: Byte): ByteArray? = values[tag]

    /** `true` iff every tag present is in [allowed] — an unknown tag within a known kind = reject (§8.2.1). */
    fun onlyHas(allowed: Set<Byte>): Boolean = values.keys.all { it in allowed }

    companion object {
        /**
         * The minimum on-wire size of one §8.2.1 TLV triple: `tag(1) ‖ length(2) ‖ value(≥0)` — i.e. the
         * 3-byte header with an empty value. Used to cap an untrusted `field-count` against the bytes
         * actually available before allocating (see [read]).
         */
        private const val MIN_TLV_TRIPLE_BYTES = MessageFormat.TLV_HEADER_BYTES

        /**
         * Small fixed initial map capacity — the closed §8.4 (text ≤ 4) / §8.5 (reaction = 3) field sets
         * never exceed 4. The map is NEVER sized from the untrusted [fieldCount] (allocation-amplifier
         * guard, see [read]); it grows naturally on the rare path where a count survives the cap.
         */
        private const val DEFAULT_FIELD_CAPACITY = 4

        /**
         * Read exactly [fieldCount] TLV triples from [cursor], then require the cursor to sit exactly at
         * [signatureStart] (no underrun/overrun of the field region — §8.2). Returns `null` on any
         * malformation: a length prefix that overruns the buffer, a duplicate tag, a value that crosses
         * into the signature, or a field region that does not end exactly at the signature.
         */
        fun read(
            cursor: ByteCursor,
            fieldCount: Int,
            signatureStart: Int,
        ): TlvFields? {
            // SECURITY: `fieldCount` is the untrusted §8.2 uint16 (0..65535), read BEFORE any field bytes
            // and BEFORE the §8.3 signature is checked. Do NOT pre-size the map from it — a ~100-byte
            // plaintext could otherwise force a ~0.5 MB allocation. The field region runs from the cursor
            // to `signatureStart`; each TLV triple is at least MIN_TLV_TRIPLE_BYTES (tag + 2-byte length +
            // 0-byte value), so a region of `available` bytes can hold at most `available / 3` triples. A
            // count larger than that cannot fit → typed reject, no large allocation, no guess. (A lying
            // count too-low is still caught below by the exact end-at-signature check.)
            val available = signatureStart - cursor.pos
            if (available < 0) return null
            val maxFittableTriples = available / MIN_TLV_TRIPLE_BYTES
            if (fieldCount > maxFittableTriples) return null
            // Size from a small constant (the closed §8.4/§8.5 kinds carry ≤ 4 fields), never the count.
            val values = HashMap<Byte, ByteArray>(DEFAULT_FIELD_CAPACITY)
            repeat(fieldCount) {
                val tag = cursor.u8()?.toByte() ?: return null
                val len = cursor.u16() ?: return null
                // A field value must not cross into the trailing signature (§8.2/§8.3 signed range).
                if (cursor.pos + len > signatureStart) return null
                val value = cursor.take(len) ?: return null
                if (values.put(tag, value) != null) return null // duplicate tag (§8.2.1) = reject
            }
            // The field region must consume exactly up to the signature — no trailing bytes (§8.2).
            if (cursor.pos != signatureStart) return null
            return TlvFields(values)
        }
    }
}
