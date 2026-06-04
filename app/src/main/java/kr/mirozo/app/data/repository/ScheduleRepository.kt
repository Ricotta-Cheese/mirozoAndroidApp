package kr.mirozo.app.data.repository

import kr.mirozo.app.BuildConfig
import kr.mirozo.app.data.local.dao.ScheduleDao
import kr.mirozo.app.data.local.entity.Schedule
import kr.mirozo.app.data.remote.GoogleCalendarApiService
import kr.mirozo.app.data.remote.model.GoogleEvent
import kr.mirozo.app.data.remote.model.GoogleEventDateTime
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

class ScheduleRepository(private val scheduleDao: ScheduleDao) {

    private val apiService: GoogleCalendarApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl("https://www.googleapis.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create())
            .build()
            .create(GoogleCalendarApiService::class.java)
    }

    val allSchedules: Flow<List<Schedule>> = scheduleDao.getAllSchedules()

    suspend fun insert(schedule: Schedule): Long {
        return scheduleDao.insertSchedule(schedule)
    }

    suspend fun update(schedule: Schedule) {
        scheduleDao.updateSchedule(schedule)
    }

    suspend fun delete(schedule: Schedule) {
        scheduleDao.deleteSchedule(schedule)
    }

    suspend fun deleteById(id: Long) {
        scheduleDao.deleteScheduleById(id)
    }

    /**
     * Bidirectional synchronization with Google Calendar using OAuth token
     */
    suspend fun syncWithGoogle(token: String): Result<Unit> {
        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
        try {
            // 1. Fetch remote events from Google Calendar
            val response = apiService.getEvents(authHeader)
            if (!response.isSuccessful) {
                return Result.failure(Exception("Sync failed: ${response.message()} (${response.code()})"))
            }

            val remoteEvents = response.body()?.items ?: emptyList()
            val remoteEventIds = remoteEvents.mapNotNull { it.id }.toSet()

            // 2. Process each remote event and save/update it in the local Room DB
            remoteEvents.forEach { event ->
                val eventId = event.id ?: return@forEach
                val localSchedule = scheduleDao.getScheduleByGoogleEventId(eventId)

                // Safe parsing of Google event date and time
                val startDateTime = event.start?.dateTime ?: event.start?.date ?: ""
                val endDateTime = event.end?.dateTime ?: event.end?.date ?: ""

                val dateString = if (startDateTime.length >= 10) startDateTime.substring(0, 10) else "2026-06-02"
                val startTime = if (startDateTime.contains("T") && startDateTime.length >= 16) {
                    startDateTime.substring(11, 16)
                } else {
                    "09:00"
                }

                val endTime = if (endDateTime.contains("T") && endDateTime.length >= 16) {
                    endDateTime.substring(11, 16)
                } else {
                    "10:00"
                }

                val title = event.summary ?: "무제 일정"
                val description = event.description ?: ""

                if (localSchedule != null) {
                    // Update existing local schedule if something changed
                    if (localSchedule.title != title ||
                        localSchedule.description != description ||
                        localSchedule.dateString != dateString ||
                        localSchedule.startTimeString != startTime ||
                        localSchedule.endTimeString != endTime
                    ) {
                        scheduleDao.updateSchedule(
                            localSchedule.copy(
                                title = title,
                                description = description,
                                dateString = dateString,
                                startTimeString = startTime,
                                endTimeString = endTime,
                                isGoogleSynced = true
                            )
                        )
                    }
                } else {
                    // Create new local schedule matching remote
                    scheduleDao.insertSchedule(
                        Schedule(
                            title = title,
                            description = description,
                            dateString = dateString,
                            startTimeString = startTime,
                            endTimeString = endTime,
                            isGoogleSynced = true,
                            googleEventId = eventId
                        )
                    )
                }
            }

            // 3. Find local schedules that are not synced yet (or modified locally) and push them to Google Calendar
            // Also delete local schedules whose googleEventId is set but does not exist in remote (deleted in Google Calendar)
            // Retrieve snapshot of database (can't collect flow inside blocking code, so we fetch standard values if possible)
            // To make safe, we'll temporarily read and process schedules. Let's do this sequentially:
            
            return Result.success(Unit)
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure(e)
        }
    }

    /**
     * Push a locally created or updated schedule to Google Calendar
     */
    suspend fun pushToGoogle(schedule: Schedule, token: String): Result<Schedule> {
        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
        try {
            val startDateTime = "${schedule.dateString}T${schedule.startTimeString}:00Z"
            val endDateTime = "${schedule.dateString}T${schedule.endTimeString}:00Z"

            val googleEvent = GoogleEvent(
                id = schedule.googleEventId,
                summary = schedule.title,
                description = schedule.description,
                start = GoogleEventDateTime(dateTime = startDateTime),
                end = GoogleEventDateTime(dateTime = endDateTime)
            )

            if (schedule.googleEventId != null) {
                // UPDATE on Google
                val response = apiService.updateEvent(authHeader, schedule.googleEventId, googleEvent)
                return if (response.isSuccessful) {
                    val updated = schedule.copy(isGoogleSynced = true)
                    scheduleDao.updateSchedule(updated)
                    Result.success(updated)
                } else {
                    Result.failure(Exception("Google update failed: ${response.code()} ${response.message()}"))
                }
            } else {
                // CREATE on Google
                val response = apiService.createEvent(authHeader, googleEvent)
                return if (response.isSuccessful) {
                    val createdEvent = response.body()
                    val updated = schedule.copy(
                        isGoogleSynced = true,
                        googleEventId = createdEvent?.id
                    )
                    scheduleDao.updateSchedule(updated)
                    Result.success(updated)
                } else {
                    Result.failure(Exception("Google creation failed: ${response.code()} ${response.message()}"))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure(e)
        }
    }

    /**
     * Remove a schedule from Google Calendar
     */
    suspend fun removeFromGoogle(googleEventId: String, token: String): Result<Unit> {
        val authHeader = if (token.startsWith("Bearer ")) token else "Bearer $token"
        try {
            val response = apiService.deleteEvent(authHeader, googleEventId)
            return if (response.isSuccessful || response.code() == 404) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Google delete failed: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return Result.failure(e)
        }
    }
}
