package kr.mirozo.app

import kr.mirozo.app.data.local.entity.Schedule
import kr.mirozo.app.data.remote.mirozo.ScheduleSummary
import kr.mirozo.app.ui.model.CalendarScheduleItem
import kr.mirozo.app.ui.model.toCalendarScheduleItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CalendarScheduleItemTest {

    @Test
    fun localScheduleRoundTripKeepsLocalId() {
        val local = Schedule(
            id = 42,
            title = "알고리즘 과제",
            description = "DP 복습",
            dateString = "2026-06-04",
            startTimeString = "09:30",
            endTimeString = "10:30"
        )

        val item = local.toCalendarScheduleItem().copy(title = "알고리즘 과제 수정")

        assertEquals(42L, item.toLocalSchedule().id)
        assertEquals("알고리즘 과제 수정", item.toLocalSchedule().title)
    }

    @Test
    fun cloudScheduleItemPreservesSummaryAndDoesNotReuseCloudIdAsRoomId() {
        val summary = scheduleSummary(id = 99, version = 7)
        val item = CalendarScheduleItem(
            id = summary.id.toLong(),
            title = summary.title,
            description = summary.description.orEmpty(),
            dateString = "2026-06-04",
            mirozoSummary = summary
        )

        assertSame(summary, item.mirozoSummary)
        assertEquals(0L, item.toLocalSchedule().id)
    }

    private fun scheduleSummary(id: Int, version: Int): ScheduleSummary {
        return ScheduleSummary(
            id = id,
            type = "TEMPORARY",
            title = "클라우드 일정",
            description = "원본 summary 보존",
            people = null,
            location = null,
            dayOfWeek = null,
            startTime = "09:00",
            endTime = "10:00",
            startAt = "2026-06-04T09:00:00.000Z",
            endAt = "2026-06-04T10:00:00.000Z",
            status = "PLANNED",
            completedMinutes = null,
            isActive = true,
            version = version,
            recurrenceSeriesId = null,
            recurrenceRuleJson = null,
            recurrenceIndex = null,
            recurrenceStartsOn = null,
            recurrenceEndsOn = null,
            fixedOccurrenceParentId = null,
            fixedOccurrenceDateKey = null,
            parentScheduleId = null,
            parentSchedule = null,
            targetScheduleId = null,
            targetSchedule = null,
            isMeetAppointment = false,
            preparingCount = 0,
            createdAt = "2026-06-04T00:00:00.000Z",
            updatedAt = "2026-06-04T00:00:00.000Z"
        )
    }
}
