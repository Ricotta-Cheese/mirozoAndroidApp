package kr.mirozo.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import kr.mirozo.app.ui.model.CalendarScheduleItem

class DragAndDropState {
    var isDragging by mutableStateOf(false)
        private set
    var draggedSchedule by mutableStateOf<CalendarScheduleItem?>(null)
        private set
    var dragPosition by mutableStateOf(Offset.Zero)
        private set
    var hoveredDateString by mutableStateOf<String?>(null)
        private set
    
    // Stores the screen bounds of active drop targets mapped by dateString ("yyyy-MM-dd")
    private val targets = mutableMapOf<String, Rect>()
    
    var onDropOccurred: ((CalendarScheduleItem, String) -> Unit)? = null

    fun startDrag(schedule: CalendarScheduleItem, initialPosition: Offset) {
        draggedSchedule = schedule
        dragPosition = initialPosition
        isDragging = true
        hoveredDateString = null
    }

    fun drag(dragAmount: Offset) {
        if (!isDragging) return
        dragPosition = Offset(dragPosition.x + dragAmount.x, dragPosition.y + dragAmount.y)
        
        // Find if our drag pointer intersects with any target boundaries on screen
        var foundHoverTarget: String? = null
        for ((date, rect) in targets) {
            if (rect.contains(dragPosition)) {
                foundHoverTarget = date
                break
            }
        }
        hoveredDateString = foundHoverTarget
    }

    fun endDrag() {
        if (!isDragging) return
        val schedule = draggedSchedule
        val targetDate = hoveredDateString
        if (schedule != null && targetDate != null && targetDate != schedule.dateString) {
            onDropOccurred?.invoke(schedule, targetDate)
        }
        isDragging = false
        draggedSchedule = null
        hoveredDateString = null
    }

    fun cancelDrag() {
        isDragging = false
        draggedSchedule = null
        hoveredDateString = null
    }

    fun registerTarget(dateString: String, bounds: Rect) {
        targets[dateString] = bounds
    }

    fun unregisterTarget(dateString: String) {
        targets.remove(dateString)
    }
}

val LocalDragAndDropState = staticCompositionLocalOf { DragAndDropState() }

@Composable
fun rememberDragAndDropState(onDrop: (CalendarScheduleItem, String) -> Unit): DragAndDropState {
    val state = remember { DragAndDropState() }
    LaunchedEffect(onDrop) {
        state.onDropOccurred = onDrop
    }
    return state
}

fun Modifier.dragSource(
    schedule: CalendarScheduleItem,
    state: DragAndDropState
) = pointerInput(schedule, state) {
    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            state.startDrag(schedule, offset)
        },
        onDrag = { change, dragAmount ->
            change.consume()
            state.drag(dragAmount)
        },
        onDragEnd = {
            state.endDrag()
        },
        onDragCancel = {
            state.cancelDrag()
        }
    )
}

fun Modifier.dropTarget(
    dateString: String,
    state: DragAndDropState
) = onGloballyPositioned { layoutCoordinates ->
    if (layoutCoordinates.isAttached) {
        state.registerTarget(dateString, layoutCoordinates.boundsInWindow())
    }
}
