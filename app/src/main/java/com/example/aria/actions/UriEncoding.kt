package com.example.aria.actions

/**
 * PHASE 6 — percent-encoding for `geo:` and `google.navigation:` URIs.
 *
 * ── WHY NOT `Uri.encode` OR `URLEncoder` ─────────────────────────────────────
 * `android.net.Uri.encode` is an Android call, so a handler using it could not be
 * tested on the JVM. `URLEncoder` is worse than useless here: it is designed for
 * `application/x-www-form-urlencoded` and turns every space into `+`, which a
 * `geo:` URI reads as a literal plus sign — "Eiffel Tower" would search for
 * "Eiffel+Tower".
 *
 * This encoder implements exactly RFC 3986: unreserved characters
 * (`A-Z a-z 0-9 - _ . ~`) pass through, everything else becomes `%XX` over the
 * UTF-8 bytes, and a space becomes `%20`.
 *
 * Non-ASCII input is encoded byte-wise, so a Persian or emoji destination still
 * produces a valid URI (the *command* strings themselves stay English this
 * phase).
 */
object UriEncoding {

    private const val HEX = "0123456789ABCDEF"

    /** True for characters that never need escaping under RFC 3986. */
    private fun isUnreserved(char: Char): Boolean =
        char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' ||
            char == '-' || char == '_' || char == '.' || char == '~'

    /** Percent-encodes [value] for use as a URI query component. */
    fun encode(value: String): String {
        val out = StringBuilder(value.length * 3)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val char = byte.toInt().toChar()
            // Only single-byte characters can be "unreserved"; UTF-8 continuation
            // bytes are 0x80..0xFF and must be escaped, which the unsigned test
            // below guarantees.
            val unsigned = byte.toInt() and 0xFF
            if (unsigned < 0x80 && isUnreserved(char)) {
                out.append(char)
            } else {
                out.append('%')
                out.append(HEX[unsigned shr 4])
                out.append(HEX[unsigned and 0x0F])
            }
        }
        return out.toString()
    }
}
