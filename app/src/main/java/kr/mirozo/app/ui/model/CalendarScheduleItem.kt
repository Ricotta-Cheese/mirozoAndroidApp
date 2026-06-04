package kr.mirozo.app.ui.model

import kr.mirozo.app.data.local.entity.Schedule
import kr.mirozo.app.data.remote.mirozo.ScheduleSummary

data class CalendarScheduleItem(
    val id: Long,
    val title: String,
    val description: String,
    val dateString: String,
    val startTimeString: String = "09:00",
    val endTimeString: String = "10:00",
    val color: Int = 0xFF4F46E5.toInt(),
    val isGoogleSynced: Boolean = false,
    val googleEventId: String? = null,
    val localSchedule: Schedule? = null,
    val mirozoSummary: ScheduleSummary? = null
) {
    fun toLocalSchedule(): Schedule {
        val base = localSchedule ?: Schedule(
            id = if (mirozoSummary == null) id else 0,
            title = title,
            description = description,
            dateString = dateString,
            startTimeString = startTimeString,
            endTimeString = endTimeString,
            color = color,
            isGoogleSynced = isGoogleSynced,
            googleEventId = googleEventId
        )

        return base.copy(
            title = title,
            description = description,
            dateString = dateString,
            startTimeString = startTimeString,
            endTimeString = endTimeString,
            color = color,
            isGoogleSynced = isGoogleSynced,
            googleEventId = googleEventId
        )
    }
}

fun Schedule.toCalendarScheduleItem(): CalendarScheduleItem {
    return CalendarScheduleItem(
        id = id,
        title = title,
        description = description,
        dateString = dateString,
        startTimeString = startTimeString,
        endTimeString = endTimeString,
        color = color,
        isGoogleSynced = isGoogleSynced,
        googleEventId = googleEventId,
        localSchedule = this
    )
}
