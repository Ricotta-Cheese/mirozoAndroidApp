package kr.mirozo.app.data.remote.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class GoogleEventList(
    @param:Json(name = "items") val items: List<GoogleEvent> = emptyList()
)

@JsonClass(generateAdapter = true)
data class GoogleEvent(
    @param:Json(name = "id") val id: String? = null,
    @param:Json(name = "summary") val summary: String? = null,
    @param:Json(name = "description") val description: String? = null,
    @param:Json(name = "start") val start: GoogleEventDateTime? = null,
    @param:Json(name = "end") val end: GoogleEventDateTime? = null
)

@JsonClass(generateAdapter = true)
data class GoogleEventDateTime(
    @param:Json(name = "dateTime") val dateTime: String? = null, // "2026-06-02T10:00:00Z"
    @param:Json(name = "date") val date: String? = null // "2026-06-02"
)
