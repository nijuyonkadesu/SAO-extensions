package one.njk.sao.extension.googlephotos

/** A user-supplied Google Photos session. Nothing is read from another app's storage. */
internal object GooglePhotosCookies {
    private val name = Regex("[A-Za-z0-9_-]{1,80}")
    private val auth = setOf("SID", "__Secure-1PSID", "__Secure-3PSID")

    fun parse(input: String): String {
        require(input.length <= 128 * 1024)
        val values = linkedMapOf<String, String>()
        if (input.lineSequence().any { it.count { c -> c == '\t' } >= 6 }) {
            input.lineSequence().forEach { line ->
                val row = line.removePrefix("#HttpOnly_").split('\t')
                if (row.size == 7 && row[0].removePrefix(".") in setOf("google.com", "photos.google.com") && row[2] == "/") {
                    val expiry = row[4].toLongOrNull() ?: return@forEach
                    if (expiry == 0L || expiry > System.currentTimeMillis() / 1000) values[row[5]] = row[6]
                }
            }
        } else {
            require('\r' !in input && '\n' !in input)
            input.removePrefix("Cookie:").trim().split(';').forEach { pair ->
                val parts = pair.trim().split('=', limit = 2)
                if (parts.size == 2) values[parts[0]] = parts[1]
            }
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }.also(::validate)
    }

    fun validate(header: String) {
        require(header.length in 1..48_000 && '\r' !in header && '\n' !in header)
        val pairs = header.split(';').map { it.trim().split('=', limit = 2) }
        require(pairs.size <= 100 && pairs.all {
            it.size == 2 && name.matches(it[0]) && it[1].length in 1..8192 && it[1].all { c -> c.code in 0x21..0x7e }
        })
        require(pairs.any { it[0] in auth })
    }
}
