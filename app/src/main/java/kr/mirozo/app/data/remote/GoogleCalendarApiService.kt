package kr.mirozo.app.data.remote

import kr.mirozo.app.data.remote.model.GoogleEvent
import kr.mirozo.app.data.remote.model.GoogleEventList
import retrofit2.Response
import retrofit2.http.*

interface GoogleCalendarApiService {

    @GET("calendar/v3/calendars/primary/events")
    suspend fun getEvents(
        @Header("Authorization") authHeader: String,
        @Query("timeMin") timeMin: String? = null,
        @Query("maxResults") maxResults: Int = 250
    ): Response<GoogleEventList>

    @POST("calendar/v3/calendars/primary/events")
    suspend fun createEvent(
        @Header("Authorization") authHeader: String,
        @Body event: GoogleEvent
    ): Response<GoogleEvent>

    @PUT("calendar/v3/calendars/primary/events/{eventId}")
    suspend fun updateEvent(
        @Header("Authorization") authHeader: String,
        @Path("eventId") eventId: String,
        @Body event: GoogleEvent
    ): Response<GoogleEvent>

    @DELETE("calendar/v3/calendars/primary/events/{eventId}")
    suspend fun deleteEvent(
        @Header("Authorization") authHeader: String,
        @Path("eventId") eventId: String
    ): Response<Unit>
}
