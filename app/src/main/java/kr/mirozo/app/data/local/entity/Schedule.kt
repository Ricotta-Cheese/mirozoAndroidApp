package kr.mirozo.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "schedules")
data class Schedule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String,
    val dateString: String, // "yyyy-MM-dd"
    val startTimeString: String = "09:00", // "HH:mm"
    val endTimeString: String = "10:00", // "HH:mm"
    val color: Int = 0xFF4F46E5.toInt(), // Modern Indigo default
    val isGoogleSynced: Boolean = false,
    val googleEventId: String? = null
)
