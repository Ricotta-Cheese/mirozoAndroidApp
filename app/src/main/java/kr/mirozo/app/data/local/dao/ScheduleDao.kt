package kr.mirozo.app.data.local.dao

import androidx.room.*
import kr.mirozo.app.data.local.entity.Schedule
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules ORDER BY dateString ASC, startTimeString ASC")
    fun getAllSchedules(): Flow<List<Schedule>>

    @Query("SELECT * FROM schedules WHERE dateString = :dateString ORDER BY startTimeString ASC")
    fun getSchedulesForDate(dateString: String): Flow<List<Schedule>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSchedule(schedule: Schedule): Long

    @Update
    suspend fun updateSchedule(schedule: Schedule)

    @Delete
    suspend fun deleteSchedule(schedule: Schedule)

    @Query("DELETE FROM schedules WHERE id = :id")
    suspend fun deleteScheduleById(id: Long)

    @Query("SELECT * FROM schedules WHERE googleEventId = :googleEventId LIMIT 1")
    suspend fun getScheduleByGoogleEventId(googleEventId: String): Schedule?
}
