package org.openwebdav.messenger.invite

/**
 * A tiny, strict, dependency-free encoder/decoder for a **flat JSON object of string values** — the only
 * shape the `owdm1:` invite payload needs (`ui-chat-surface` plan → invite codec). Hand-rolled rather than
 * pulling a JSON dependency the project does not already carry, and kept strict so the decode path is
 * reject-don't-guess: [decode] returns `null` for anything that is not a flat `{ "k":"v", ... }` object of
 * string→string pairs (nested objects/arrays/numbers/booleans/nulls, trailing junk, bad escapes — all
 * rejected, never partially parsed). The codec maps that `null` to a typed [InviteCodec.Result.Rejected].
 *
 * Not a general JSON library — it deliberately supports only what the invite uses, so an attacker-supplied
 * QR cannot steer it into a surprising shape.
 */
internal object FlatJson {
    private const val FORM_FEED = '\u000C'

    /** Encode [fields] as a flat JSON object with deterministic key order (insertion order of the map). */
    fun encode(fields: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append('{')
        var first = true
        for ((k, v) in fields) {
            if (!first) sb.append(',')
            first = false
            appendString(sb, k)
            sb.append(':')
            appendString(sb, v)
        }
        sb.append('}')
        return sb.toString()
    }

    /**
     * Parse a flat JSON object of string→string pairs, or `null` if [text] is not exactly that shape.
     * Reject-don't-guess: any structural deviation yields `null`, never a partial map.
     */
    fun decode(text: String): Map<String, String>? {
        val p = Parser(text)
        return try {
            val map = p.parseObject()
            p.skipWhitespace()
            if (!p.atEnd()) null else map
        } catch (_: JsonReject) {
            null
        }
    }

    private fun appendString(
        sb: StringBuilder,
        s: String,
    ) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0')) else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Internal control-flow signal for a malformed token; never escapes [decode]. */
    private class JsonReject : Exception()

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd(): Boolean = i >= s.length

        fun skipWhitespace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun parseObject(): Map<String, String> {
            skipWhitespace()
            expect('{')
            val out = LinkedHashMap<String, String>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                val value = parseString()
                if (out.put(key, value) != null) throw JsonReject() // duplicate key — reject
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw JsonReject()
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonReject()
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> sb.append(parseEscape())
                    c < ' ' -> throw JsonReject() // raw control char in a string — reject
                    else -> sb.append(c)
                }
            }
        }

        private fun parseEscape(): Char {
            if (i >= s.length) throw JsonReject()
            return when (s[i++]) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'b' -> '\b'
                'f' -> FORM_FEED
                'u' -> parseUnicodeEscape()
                else -> throw JsonReject()
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (i + 4 > s.length) throw JsonReject()
            val hex = s.substring(i, i + 4)
            i += 4
            return hex.toIntOrNull(16)?.toChar() ?: throw JsonReject()
        }

        private fun peek(): Char {
            if (i >= s.length) throw JsonReject()
            return s[i]
        }

        private fun next(): Char {
            if (i >= s.length) throw JsonReject()
            return s[i++]
        }

        private fun expect(c: Char) {
            if (next() != c) throw JsonReject()
        }
    }
}
