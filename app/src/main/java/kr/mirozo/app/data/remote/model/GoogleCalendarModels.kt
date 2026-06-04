package kr.mirozo.app.data.remote.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GoogleEventList(
    @Json(name = "items") val items: List<GoogleEvent> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GoogleEvent(
    @Json(name = "id") val id: String? = null,
    @Json(name = "summary") val summary: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "start") val start: GoogleEventDateTime? = null,
    @Json(name = "end") val end: GoogleEventDateTime? = null
)

@JsonClass(generateAdapter = true)
data class GoogleEventDateTime(
    @Json(name = "dateTime") val dateTime: String? = null, // "2026-06-02T10:00:00Z"
    @Json(name = "date") val date: String? = null // "2026-06-02"
)
