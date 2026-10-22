package one.njk.sao.extension.googlephotos

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/** Per-profile browser cookie semantics, including host-only, path, expiry and Secure. */
internal class GooglePhotosCookieJar(initial: List<Cookie>) : CookieJar {
    private val cookies = initial.toMutableList()
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter {
        it.expiresAt > System.currentTimeMillis() && it.matches(url)
    }
    @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { fresh ->
            this.cookies.removeAll { it.name == fresh.name && it.domain == fresh.domain && it.path == fresh.path }
            if (fresh.expiresAt > System.currentTimeMillis()) this.cookies += fresh
        }
        check(this.cookies.size <= 200)
    }
    @Synchronized fun snapshot(): List<Cookie> = cookies.filter { it.expiresAt > System.currentTimeMillis() }
        .sortedWith(compareBy({ it.domain }, { it.path }, { it.name }))
}
