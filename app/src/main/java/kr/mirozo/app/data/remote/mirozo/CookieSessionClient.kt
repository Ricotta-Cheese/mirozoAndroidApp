package kr.mirozo.app.data.remote.mirozo

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class SharedPrefsCookieJar(context: Context) : CookieJar {
    private val sharedPrefs = context.getSharedPreferences("mirozo_cookies", Context.MODE_PRIVATE)

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val editor = sharedPrefs.edit()
        cookies.forEach { cookie ->
            val serializedValue = serializeCookie(cookie)
            editor.putString("${url.host}:${cookie.name}", serializedValue)
        }
        editor.apply()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookies = mutableListOf<Cookie>()
        val keys = sharedPrefs.all.keys
        keys.forEach { key ->
            if (key.startsWith(url.host + ":")) {
                val serialized = sharedPrefs.getString(key, null)
                if (serialized != null) {
                    deserializeCookie(url.host, serialized)?.let { cookie ->
                        if (cookie.expiresAt > System.currentTimeMillis()) {
                            cookies.add(cookie)
                        }
                    }
                }
            }
        }
        return cookies
    }

    fun clear() {
        sharedPrefs.edit().clear().apply()
    }

    private fun serializeCookie(cookie: Cookie): String {
        return "${cookie.name}|${cookie.value}|${cookie.expiresAt}|${cookie.domain}|${cookie.path}|${cookie.secure}|${cookie.httpOnly}"
    }

    private fun deserializeCookie(host: String, serialized: String): Cookie? {
        val parts = serialized.split("|")
        if (parts.size < 7) return null
        try {
            val builder = Cookie.Builder()
                .name(parts[0])
                .value(parts[1])
                .expiresAt(parts[2].toLong())
                .path(parts[4])
            
            val domain = parts[3]
            if (domain.isNotEmpty()) {
                builder.domain(domain)
            } else {
                builder.hostOnlyDomain(host)
            }
            
            if (parts[5].toBoolean()) builder.secure()
            if (parts[6].toBoolean()) builder.httpOnly()
            
            return builder.build()
        } catch (e: Exception) {
            return null
        }
    }
}
