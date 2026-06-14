package org.openwebdav.messenger.export

import org.openwebdav.messenger.crypto.ChatKey
import org.openwebdav.messenger.identity.Identity
import org.openwebdav.messenger.transport.ConnectionConfig

/**
 * The serializable data bundle collected for export — every device-local secret the plan covers
 * (connection config, community key, all chat keys, identity keypair). Lives in memory only;
 * serialized to JSON → AEAD-encrypted before leaving the device.
 *
 * All binary fields are base64-encoded strings so the JSON is plain ASCII (no raw byte blobs in the
 * output — base64 is safe for paste/share channels). Nullable fields represent stores that were empty
 * at export time (e.g. no connection config set yet, no community key stored).
 */
internal data class ExportPayload(
    /** WebDAV connection config, or null if not configured yet. */
    val connectionConfig: ConnectionConfig?,
    /** Community key (32-byte symmetric key), or null if not stored yet. */
    val communityKeyBase64: String?,
    /** Per-chat keys by chat-id; the value is the base64-encoded 32-byte key. May be empty. */
    val chatKeys: Map<String, String>,
    /** Identity keypair, or null if not generated yet. */
    val identitySerialized: String?,
) {
    companion object {
        fun build(
            connectionConfig: ConnectionConfig?,
            communityKey: ChatKey?,
            chatKeys: Map<String, ChatKey>,
            identity: Identity?,
        ): ExportPayload =
            ExportPayload(
                connectionConfig = connectionConfig,
                communityKeyBase64 = communityKey?.let { base64(it.export()) },
                chatKeys = chatKeys.mapValues { (_, key) -> base64(key.export()) },
                identitySerialized = identity?.let { base64(Identity.serialize(it)) },
            )

        /**
         * JSON serialization — manual to avoid an extra dependency. Produces deterministic
         * field ordering so two identical payloads produce identical ciphertext (given the
         * same password — the nonce is random so the ciphertext still differs).
         */
        fun toJson(payload: ExportPayload): String {
            val sb = StringBuilder(4096)
            sb.append('{')
            sb.append("\"v\":1")

            // connectionConfig
            sb.append(",\"cc\":")
            val cc = payload.connectionConfig
            if (cc == null) {
                sb.append("null")
            } else {
                sb.append("{\"bu\":")
                appendJsonString(sb, cc.baseUrl)
                sb.append(",\"un\":")
                appendJsonString(sb, cc.username)
                sb.append(",\"ap\":")
                appendJsonString(sb, cc.appPassword)
                sb.append(",\"cr\":")
                appendJsonString(sb, cc.chatRoot)
                sb.append('}')
            }

            // communityKey
            sb.append(",\"ck\":")
            val ck = payload.communityKeyBase64
            if (ck == null) sb.append("null") else appendJsonString(sb, ck)

            // chatKeys — a JSON object
            sb.append(",\"ch\":{")
            var firstChat = true
            for ((chatId, keyB64) in payload.chatKeys) {
                if (!firstChat) sb.append(',')
                firstChat = false
                appendJsonString(sb, chatId)
                sb.append(':')
                appendJsonString(sb, keyB64)
            }
            sb.append('}')

            // identitySerialized
            sb.append(",\"id\":")
            val id = payload.identitySerialized
            if (id == null) sb.append("null") else appendJsonString(sb, id)

            sb.append('}')
            return sb.toString()
        }

        fun fromJson(json: String): ExportPayload? {
            // Minimal hand-rolled JSON parser for the fixed schema — avoids a dependency.
            // The parser is intentionally strict: unknown fields are ignored, missing fields
            // return null (rejection upstream).
            return try {
                var v: Int? = null
                var cc: ConnectionConfig? = null
                var ck: String? = null
                val ch = mutableMapOf<String, String>()
                var id: String? = null

                val p = JsonParser(json)
                p.enterObject()
                while (p.hasNext()) {
                    when (p.nextKey()) {
                        "v" -> v = p.nextInt()
                        "cc" -> cc = p.parseConnectionConfig()
                        "ck" -> ck = p.parseNullableString()
                        "ch" -> p.parseStringMap(ch)
                        "id" -> id = p.parseNullableString()
                        else -> p.skipValue()
                    }
                    p.consumeComma()
                }
                p.exitObject()

                if (v != 1) return null
                ExportPayload(
                    connectionConfig = cc,
                    communityKeyBase64 = ck,
                    chatKeys = ch,
                    identitySerialized = id,
                )
            } catch (_: Exception) {
                null
            }
        }

        // -- internal helpers ---------------------------------------------------

        private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

        internal fun decodeBase64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

        private fun appendJsonString(
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
                    else ->
                        if (c.code < 0x20) {
                            sb.append("\\u")
                            sb.append(String.format("%04x", c.code))
                        } else {
                            sb.append(c)
                        }
                }
            }
            sb.append('"')
        }
    }
}

/**
 * Minimal, predictable JSON parser for the export payload schema. Not a general-purpose parser —
 * built for one fixed nested object with string/int/null values. No streaming, no number parsing
 * beyond int, no arrays. Strict: rejects malformed input.
 */
private class JsonParser(private val s: String) {
    private var pos = 0

    fun enterObject() {
        skipWs()
        require(s[pos] == '{') { "expected '{' at $pos" }
        pos++
    }

    fun exitObject() {
        skipWs()
        require(s[pos] == '}') { "expected '}' at $pos" }
        pos++
    }

    fun hasNext(): Boolean {
        skipWs()
        return s[pos] != '}'
    }

    fun nextKey(): String {
        skipWs()
        // Consume optional comma separator between values.
        if (pos < s.length && s[pos] == ',') {
            pos++
            skipWs()
        }
        val key = parseString()
        skipWs()
        require(s[pos] == ':') { "expected ':' after key at $pos" }
        pos++
        return key
    }

    fun nextInt(): Int {
        skipWs()
        var i = pos
        while (i < s.length && s[i] in '0'..'9') i++
        require(i > pos) { "expected integer at $pos" }
        val v = s.substring(pos, i).toInt()
        pos = i
        return v
    }

    fun parseNullableString(): String? {
        skipWs()
        if (s[pos] == 'n') {
            require(s.startsWith("null", pos)) { "expected null at $pos" }
            pos += 4
            return null
        }
        return parseString()
    }

    fun parseString(): String {
        skipWs()
        require(s[pos] == '"') { "expected '\"' at $pos" }
        pos++
        val sb = StringBuilder()
        while (pos < s.length) {
            val c = s[pos]
            if (c == '"') {
                pos++
                return sb.toString()
            }
            if (c == '\\') {
                pos++
                require(pos < s.length) { "unexpected end after '\\'" }
                when (s[pos]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = s.substring(pos + 1, pos + 5)
                        require(hex.length == 4 && hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                            "invalid \\u escape at $pos"
                        }
                        sb.append(hex.toInt(16).toChar())
                        pos += 4
                    }
                    else -> error("invalid escape '\\${s[pos]}' at $pos")
                }
                pos++
            } else {
                sb.append(c)
                pos++
            }
        }
        error("unterminated string")
    }

    fun parseStringMap(target: MutableMap<String, String>) {
        skipWs()
        require(s[pos] == '{') { "expected '{' at $pos" }
        pos++
        skipWs()
        if (s[pos] == '}') {
            pos++
            return
        }
        while (true) {
            val key = parseString()
            skipWs()
            require(s[pos] == ':') { "expected ':' at $pos" }
            pos++
            val value = parseNullableString()
            if (value != null) target[key] = value
            skipWs()
            if (s[pos] == '}') break
            require(s[pos] == ',') { "expected ',' or '}' at $pos" }
            pos++
        }
        pos++ // consume '}'
    }

    fun parseConnectionConfig(): ConnectionConfig? {
        skipWs()
        if (s[pos] == 'n') {
            require(s.startsWith("null", pos)) { "expected null at $pos" }
            pos += 4
            return null
        }
        require(s[pos] == '{') { "expected '{' at $pos" }
        pos++
        var bu: String? = null
        var un: String? = null
        var ap: String? = null
        var cr: String? = null
        while (hasNext()) {
            when (nextKey()) {
                "bu" -> bu = parseString()
                "un" -> un = parseString()
                "ap" -> ap = parseString()
                "cr" -> cr = parseString()
                else -> skipValue()
            }
        }
        exitObject()
        if (bu == null || un == null || ap == null || cr == null) return null
        return ConnectionConfig(
            baseUrl = bu,
            username = un,
            appPassword = ap,
            chatRoot = cr,
        )
    }

    fun consumeComma() {
        skipWs()
        if (pos < s.length && s[pos] == ',') pos++
    }

    fun skipValue() {
        skipWs()
        when (s[pos]) {
            '"' -> {
                parseString()
            }
            '{' -> {
                pos++
                var depth = 1
                while (depth > 0 && pos < s.length) {
                    when (s[pos]) {
                        '{' -> depth++
                        '}' -> depth--
                        '"' -> {
                            pos++
                            while (pos < s.length && s[pos] != '"') {
                                if (s[pos] == '\\') pos++
                                pos++
                            }
                        }
                    }
                    pos++
                }
            }
            'n' -> {
                require(s.startsWith("null", pos))
                pos += 4
            }
            else -> {
                while (pos < s.length && s[pos] !in setOf(',', '}', ']', ' ', '\t', '\n', '\r')) pos++
            }
        }
    }

    private fun skipWs() {
        while (pos < s.length && s[pos] in setOf(' ', '\t', '\n', '\r')) pos++
    }
}
