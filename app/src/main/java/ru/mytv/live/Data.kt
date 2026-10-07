package ru.mytv.live

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class Item(
    val name: String,
    val url: String,
    val logo: String = "",
    val group: String = ""
)

data class Source(
    val title: String,
    val url: String,
    val code: String = ""
)

object Repo {
    private val logoRe = Regex("tvg-logo=\"([^\"]*)\"")
    private val groupRe = Regex("group-title=\"([^\"]*)\"")

    /** Все страны мира с русскими названиями. */
    val countries: List<Source> by lazy {
        Locale.getISOCountries()
            .map { cc ->
                Source(
                    title = Locale("", cc).getDisplayCountry(Locale("ru")),
                    url = "https://iptv-org.github.io/iptv/countries/${cc.lowercase()}.m3u",
                    code = cc
                )
            }
            .sortedBy { it.title }
    }

    private val categoryNames = listOf(
        "news" to "Новости",
        "general" to "Общие",
        "entertainment" to "Развлечения",
        "movies" to "Фильмы",
        "series" to "Сериалы",
        "comedy" to "Комедии",
        "music" to "Музыка",
        "sports" to "Спорт",
        "kids" to "Детские",
        "animation" to "Мультфильмы",
        "family" to "Семейные",
        "documentary" to "Документальные",
        "education" to "Образование",
        "science" to "Наука",
        "culture" to "Культура",
        "classic" to "Классика",
        "travel" to "Путешествия",
        "outdoor" to "Природа и улица",
        "relax" to "Релакс",
        "weather" to "Погода",
        "auto" to "Авто",
        "business" to "Бизнес",
        "cooking" to "Кулинария",
        "lifestyle" to "Стиль жизни",
        "religious" to "Религия",
        "public" to "Общественные",
        "legislative" to "Парламентские",
        "interactive" to "Интерактив",
        "shop" to "Магазин на диване"
    )

    /** Категории ТВ-каналов. */
    val categories: List<Source> = categoryNames.map { (id, ru) ->
        Source(ru, "https://iptv-org.github.io/iptv/categories/$id.m3u", id)
    }

    private fun download(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "MirTV/1.0")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun parseM3u(text: String): List<Item> {
        val res = ArrayList<Item>()
        var name: String? = null
        var logo = ""
        var group = ""
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#EXTINF")) {
                name = line.substringAfterLast(",").trim()
                logo = logoRe.find(line)?.groupValues?.get(1) ?: ""
                group = groupRe.find(line)?.groupValues?.get(1) ?: ""
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                if (name != null) {
                    res.add(Item(name.ifBlank { line }, line, logo, group))
                    name = null
                } else if (line.startsWith("http")) {
                    res.add(Item(line, line))
                }
            }
        }
        return res
    }

    /** Загрузка M3U-плейлиста (ТВ-каналы или любой свой список). */
    suspend fun tv(url: String): List<Item> = withContext(Dispatchers.IO) {
        parseM3u(download(url))
    }

    /** Радиостанции страны (каталог Radio Browser). */
    suspend fun radio(cc: String): List<Item> = withContext(Dispatchers.IO) {
        val mirrors = listOf("de1", "de2", "nl1", "at1")
        var last: Exception? = null
        for (m in mirrors) {
            try {
                val text = download(
                    "https://$m.api.radio-browser.info/json/stations/bycountrycodeexact/$cc" +
                        "?hidebroken=true&order=clickcount&reverse=true&limit=300"
                )
                val arr = JSONArray(text)
                val out = ArrayList<Item>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val u = o.optString("url_resolved").ifBlank { o.optString("url") }
                    val n = o.optString("name").trim()
                    if (u.isNotBlank() && n.isNotBlank()) {
                        out.add(Item(n, u, o.optString("favicon"), o.optString("tags")))
                    }
                }
                return@withContext out
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("Нет ответа от сервера")
    }
}

object Store {
    private fun p(c: Context) = c.getSharedPreferences("mirtv", Context.MODE_PRIVATE)

    fun loadFavs(c: Context): List<Item> {
        val s = p(c).getString("favs", null) ?: return emptyList()
        return try {
            val a = JSONArray(s)
            (0 until a.length()).map {
                val o = a.getJSONObject(it)
                Item(o.getString("n"), o.getString("u"), o.optString("l"), o.optString("g"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFavs(c: Context, list: List<Item>) {
        val a = JSONArray()
        list.forEach {
            a.put(
                JSONObject()
                    .put("n", it.name)
                    .put("u", it.url)
                    .put("l", it.logo)
                    .put("g", it.group)
            )
        }
        p(c).edit().putString("favs", a.toString()).apply()
    }

    fun getCustomUrl(c: Context): String = p(c).getString("custom", "") ?: ""

    fun setCustomUrl(c: Context, url: String) {
        p(c).edit().putString("custom", url).apply()
    }
}
