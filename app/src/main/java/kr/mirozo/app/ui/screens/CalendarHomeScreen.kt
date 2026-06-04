package kr.mirozo.app.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.launch
import kr.mirozo.app.BuildConfig
import kr.mirozo.app.data.remote.mirozo.*
import kr.mirozo.app.ui.components.*
import kr.mirozo.app.ui.model.CalendarScheduleItem
import kr.mirozo.app.ui.viewmodel.CalendarViewModel
import kr.mirozo.app.ui.viewmodel.MirozoAuthState
import kr.mirozo.app.ui.viewmodel.SyncState
import java.util.Calendar
import kotlin.math.roundToInt

data class CalendarDay(val dateString: String, val dayNumber: Int, val isCurrentMonth: Boolean)

fun getDaysInMonth(year: Int, month: Int): List<CalendarDay> {
    val days = mutableListOf<CalendarDay>()
    val cal = Calendar.getInstance()
    
    // Set first day of current month
    cal.set(Calendar.YEAR, year)
    cal.set(Calendar.MONTH, month - 1)
    cal.set(Calendar.DAY_OF_MONTH, 1)
    
    val firstDayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0-6 (Sunday = 0)
    val maxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    
    // Calculate previous month padding
    cal.add(Calendar.MONTH, -1)
    val prevMaxDays = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    for (i in firstDayOfWeek - 1 downTo 0) {
        val d = prevMaxDays - i
        val m = if (month == 1) 12 else month - 1
        val y = if (month == 1) year - 1 else year
        days.add(CalendarDay(String.format("%04d-%02d-%02d", y, m, d), d, false))
    }
    
    // Reset to current month values
    for (d in 1..maxDays) {
        days.add(CalendarDay(String.format("%04d-%02d-%02d", year, month, d), d, true))
    }
    
    // Rest filler targets for a full 42 grid slots
    val remaining = 42 - days.size
    for (d in 1..remaining) {
        val m = if (month == 12) 1 else month + 1
        val y = if (month == 12) year + 1 else year
        days.add(CalendarDay(String.format("%04d-%02d-%02d", y, m, d), d, false))
    }
    return days
}

private suspend fun requestGoogleIdToken(
    context: Context,
    credentialManager: CredentialManager,
    viewModel: CalendarViewModel,
    mode: String
): Result<String> {
    val activity = context.findActivity()
        ?: return Result.failure(IllegalStateException("Google 인증을 시작할 Activity를 찾지 못했습니다."))
    val serverClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID.trim()
    if (serverClientId.isEmpty() || serverClientId == GOOGLE_SERVER_CLIENT_ID_PLACEHOLDER) {
        return Result.failure(IllegalStateException("GOOGLE_SERVER_CLIENT_ID가 설정되지 않았습니다. .env에 Google Web Client ID를 추가해주세요."))
    }

    val nonceResult = viewModel.requestGoogleNonce(mode)
    if (nonceResult.isFailure) {
        return Result.failure(nonceResult.exceptionOrNull() ?: IllegalStateException("Google nonce 발급에 실패했습니다."))
    }
    val nonce = nonceResult.getOrThrow().nonce
    return requestGoogleIdTokenWithFilter(
        credentialManager = credentialManager,
        activity = activity,
        serverClientId = serverClientId,
        nonce = nonce,
        filterByAuthorizedAccounts = true
    ).recoverCatching { err ->
        if (err is NoCredentialException) {
            requestGoogleIdTokenWithFilter(
                credentialManager = credentialManager,
                activity = activity,
                serverClientId = serverClientId,
                nonce = nonce,
                filterByAuthorizedAccounts = false
            ).getOrThrow()
        } else {
            throw err
        }
    }
}

private suspend fun requestGoogleIdTokenWithFilter(
    credentialManager: CredentialManager,
    activity: Activity,
    serverClientId: String,
    nonce: String,
    filterByAuthorizedAccounts: Boolean
): Result<String> {
    return runCatching {
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
            .setServerClientId(serverClientId)
            .setNonce(nonce)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        val credential = credentialManager.getCredential(activity, request).credential
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            GoogleIdTokenCredential.createFrom(credential.data).idToken
        } else {
            throw IllegalStateException("지원하지 않는 Google 인증 응답입니다.")
        }
    }.recoverCatching { err ->
        when (err) {
            is GoogleIdTokenParsingException -> throw IllegalStateException("Google ID 토큰 응답을 읽지 못했습니다.", err)
            is GetCredentialException -> throw err
            else -> throw err
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun uploadTimetableImageFromUri(
    context: Context,
    uri: Uri,
    viewModel: CalendarViewModel
) {
    runCatching {
        val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
        if (!mime.startsWith("image/")) {
            throw IllegalArgumentException("이미지 파일만 업로드할 수 있습니다.")
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("선택한 파일을 읽지 못했습니다.")
        if (bytes.size > MAX_TIMETABLE_IMAGE_BYTES) {
            throw IllegalArgumentException("시간표 이미지는 10MB 이하만 업로드할 수 있습니다.")
        }
        val fileName = context.queryDisplayName(uri) ?: "timetable-image"
        viewModel.parseTimetableImage(bytes, fileName, mime)
    }.onFailure { err ->
        viewModel.reportError(err.message ?: "시간표 이미지를 읽지 못했습니다.")
    }
}

private fun Context.queryDisplayName(uri: Uri): String? {
    return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
    }
}

private const val MAX_TIMETABLE_IMAGE_BYTES = 10 * 1024 * 1024
private const val GOOGLE_SERVER_CLIENT_ID_PLACEHOLDER = "__SET_ME__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarHomeScreen(
    viewModel: CalendarViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    
    // Core calendar states
    val currentYear by viewModel.currentYear.collectAsState()
    val currentMonth by viewModel.currentMonth.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()

    val allSchedulesMap by viewModel.calendarSchedules.collectAsState()
    val selectedSchedules by viewModel.selectedDateSchedules.collectAsState()
    val unscheduledPool by viewModel.taskPool.collectAsState()

    // Mirozo Cloud states
    val useMirozoCloud by viewModel.useMirozoCloud.collectAsState()
    val mirozoAuthState by viewModel.mirozoAuthState.collectAsState()
    val nlpReply by viewModel.nlpReply.collectAsState()
    val nlpParsedIntent by viewModel.nlpParsedIntent.collectAsState()
    val nlpLoading by viewModel.nlpLoading.collectAsState()

    // Form modals
    var showAddDialog by remember { mutableStateOf(false) }
    var scheduleToEdit by remember { mutableStateOf<CalendarScheduleItem?>(null) }
    
    var showAIParserPanel by remember { mutableStateOf(false) }
    var speechInputText by remember { mutableStateOf("") }
    var preselectedDateForAdd by remember { mutableStateOf("") }
    val googleLinked = (mirozoAuthState as? MirozoAuthState.Authenticated)
        ?.bootstrap
        ?.oauth
        ?.google
        ?.linkedAccounts
        ?.isNotEmpty() == true

    fun startGoogleAuth(mode: String) {
        coroutineScope.launch {
            requestGoogleIdToken(
                context = context,
                credentialManager = credentialManager,
                viewModel = viewModel,
                mode = mode
            ).onSuccess { idToken ->
                viewModel.submitGoogleIdToken(mode, idToken)
            }.onFailure { err ->
                viewModel.reportError(err.message ?: "Google 인증을 완료하지 못했습니다.")
            }
        }
    }

    fun clearGoogleCredentialState() {
        coroutineScope.launch {
            runCatching {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            }
        }
    }

    // Init custom drag-drop orchestrations
    val dragAndDropState = rememberDragAndDropState { schedule, targetDateString ->
        viewModel.moveSchedule(schedule, targetDateString)
        val targetLabel = if (targetDateString == "pool") "미지정 보관함" else targetDateString
        Toast.makeText(context, "'${schedule.title}' 일정이 $targetLabel(으)로 변경되었습니다.", Toast.LENGTH_SHORT).show()
    }

    val configuration = LocalConfiguration.current
    val isTablet = configuration.screenWidthDp >= 600

    // Observes transactional synchronization feedback
    LaunchedEffect(syncStatus) {
        when (syncStatus) {
            is SyncState.Success -> {
                Toast.makeText(context, (syncStatus as SyncState.Success).message, Toast.LENGTH_LONG).show()
                viewModel.resetSyncStatus()
            }
            is SyncState.Error -> {
                Toast.makeText(context, (syncStatus as SyncState.Error).error, Toast.LENGTH_LONG).show()
                viewModel.resetSyncStatus()
            }
            else -> {}
        }
    }

    CompositionLocalProvider(LocalDragAndDropState provides dragAndDropState) {
        Box(modifier = modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = "App Icon",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Column {
                                    Text(
                                        text = "Mirozo",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Text(
                                        text = if (useMirozoCloud) "Mirozo Cloud Connected" else "LocalStorage Offline Mode",
                                        fontSize = 11.sp,
                                        color = if (useMirozoCloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { showAIParserPanel = !showAIParserPanel },
                                modifier = Modifier.testTag("ai_natural_parser_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Face,
                                    contentDescription = "Mirozo AI",
                                    tint = if (showAIParserPanel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = {
                                    if (useMirozoCloud) {
                                        viewModel.mirozoLogout()
                                        clearGoogleCredentialState()
                                        Toast.makeText(context, "클라우드가 해제되고 오프라인 모드로 변경되었습니다.", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.setUseMirozoCloud(true)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "Mirozo Cloud Mode",
                                    tint = if (useMirozoCloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                                )
                            }

                            IconButton(
                                onClick = {
                                    startGoogleAuth(
                                        if (mirozoAuthState is MirozoAuthState.Authenticated) "connect" else "login"
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccountCircle,
                                    contentDescription = "Google account",
                                    tint = if (googleLinked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    )
                },
                floatingActionButton = {
                    FloatingActionButton(
                        onClick = {
                            preselectedDateForAdd = selectedDate
                            showAddDialog = true
                        },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier
                            .testTag("add_schedule_fab")
                            .navigationBarsPadding()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Add Schedule")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("새 일정 추가", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(padding)
                ) {
                    if (useMirozoCloud) {
                        when (mirozoAuthState) {
                            is MirozoAuthState.Checking -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        CircularProgressIndicator()
                                        Spacer(modifier = Modifier.height(12.dp))
                                        Text("Mirozo 클라우드 동기화 확인 중...", color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }
                            is MirozoAuthState.Unauthenticated -> {
                                MirozoAuthGateScreen(
                                    viewModel = viewModel,
                                    onGoogleLogin = { startGoogleAuth("login") }
                                )
                            }
                            is MirozoAuthState.OnboardingRequired -> {
                                MirozoOnboardingQuizScreen(viewModel)
                            }
                            is MirozoAuthState.TimetableOnboardingRequired -> {
                                MirozoTimetableUploadScreen(viewModel)
                            }
                            is MirozoAuthState.Authenticated -> {
                                MainCalendarContent(
                                    isTablet = isTablet,
                                    currentYear = currentYear,
                                    currentMonth = currentMonth,
                                    selectedDate = selectedDate,
                                    allSchedulesMap = allSchedulesMap,
                                    selectedSchedules = selectedSchedules,
                                    unscheduledPool = unscheduledPool,
                                    dragAndDropState = dragAndDropState,
                                    showAIParserPanel = showAIParserPanel,
                                    speechInputText = speechInputText,
                                    nlpLoading = nlpLoading,
                                    nlpReply = nlpReply,
                                    nlpParsedIntent = nlpParsedIntent,
                                    viewModel = viewModel,
                                    onUpdateSpeech = { speechInputText = it },
                                    onSelectDate = { viewModel.selectDate(it) },
                                    onPrevMonth = { viewModel.previousMonth() },
                                    onNextMonth = { viewModel.nextMonth() },
                                    onAddQuick = { date ->
                                        preselectedDateForAdd = date
                                        showAddDialog = true
                                    },
                                    onEditSchedule = { scheduleToEdit = it },
                                    onDeleteSchedule = { viewModel.deleteSchedule(it) }
                                )
                            }
                        }
                    } else {
                        MainCalendarContent(
                            isTablet = isTablet,
                            currentYear = currentYear,
                            currentMonth = currentMonth,
                            selectedDate = selectedDate,
                            allSchedulesMap = allSchedulesMap,
                            selectedSchedules = selectedSchedules,
                            unscheduledPool = unscheduledPool,
                            dragAndDropState = dragAndDropState,
                            showAIParserPanel = showAIParserPanel,
                            speechInputText = speechInputText,
                            nlpLoading = nlpLoading,
                            nlpReply = nlpReply,
                            nlpParsedIntent = nlpParsedIntent,
                            viewModel = viewModel,
                            onUpdateSpeech = { speechInputText = it },
                            onSelectDate = { viewModel.selectDate(it) },
                            onPrevMonth = { viewModel.previousMonth() },
                            onNextMonth = { viewModel.nextMonth() },
                            onAddQuick = { date ->
                                preselectedDateForAdd = date
                                showAddDialog = true
                            },
                            onEditSchedule = { scheduleToEdit = it },
                            onDeleteSchedule = { viewModel.deleteSchedule(it) }
                        )
                    }
                }
            }

            // Dragged Floating Preview card
            if (dragAndDropState.isDragging && dragAndDropState.draggedSchedule != null) {
                val schedule = dragAndDropState.draggedSchedule!!
                val scale by animateFloatAsState(targetValue = 1.05f, label = "Scale drag")
                val elevation by animateDpAsState(targetValue = 12.dp, label = "Elevation drag")

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.15f))
                )

                Card(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (dragAndDropState.dragPosition.x - 120).roundToInt(),
                                (dragAndDropState.dragPosition.y - 45).roundToInt()
                            )
                        }
                        .size(width = 240.dp, height = 90.dp)
                        .shadow(elevation, RoundedCornerShape(12.dp))
                        .alpha(0.9f)
                        .scale(scale),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(schedule.color)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = schedule.title,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (schedule.dateString == "pool") "미지정 보관함" else "${schedule.startTimeString} - ${schedule.endTimeString}",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Add standard Dialog
            if (showAddDialog) {
                ScheduleFormDialog(
                    initialDate = preselectedDateForAdd,
                    onDismiss = { showAddDialog = false },
                    onConfirm = { title, desc, date, start, end, colorHex, isPool ->
                        viewModel.addSchedule(title, desc, date, start, end, colorHex, isPool)
                        showAddDialog = false
                    }
                )
            }

            // Edit dialog
            if (scheduleToEdit != null) {
                ScheduleFormDialog(
                    schedule = scheduleToEdit,
                    onDismiss = { scheduleToEdit = null },
                    onConfirm = { title, desc, date, start, end, colorHex, isPool ->
                        val targetDate = if (isPool) "pool" else date
                        val updated = scheduleToEdit!!.copy(
                            title = title,
                            description = desc,
                            dateString = targetDate,
                            startTimeString = start,
                            endTimeString = end,
                            color = colorHex,
                            isGoogleSynced = false
                        )
                        viewModel.updateSchedule(updated)
                        scheduleToEdit = null
                    }
                )
            }
        }
    }
}

@Composable
fun MainCalendarContent(
    isTablet: Boolean,
    currentYear: Int,
    currentMonth: Int,
    selectedDate: String,
    allSchedulesMap: Map<String, List<CalendarScheduleItem>>,
    selectedSchedules: List<CalendarScheduleItem>,
    unscheduledPool: List<CalendarScheduleItem>,
    dragAndDropState: DragAndDropState,
    showAIParserPanel: Boolean,
    speechInputText: String,
    nlpLoading: Boolean,
    nlpReply: String,
    nlpParsedIntent: ScheduleChatIntent?,
    viewModel: CalendarViewModel,
    onUpdateSpeech: (String) -> Unit,
    onSelectDate: (String) -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onAddQuick: (String) -> Unit,
    onEditSchedule: (CalendarScheduleItem) -> Unit,
    onDeleteSchedule: (CalendarScheduleItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = showAIParserPanel,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "🤖 Mirozo AI 음성 비서",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = speechInputText,
                            onValueChange = onUpdateSpeech,
                            placeholder = { Text("예: 내일 오후 3시에 회의 일정 추가해줘") },
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { viewModel.parseMirozoSpeech(speechInputText) },
                            enabled = !nlpLoading && speechInputText.isNotEmpty()
                        ) {
                            if (nlpLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Parse")
                            }
                        }
                    }
                    if (nlpReply.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Column {
                                Text(
                                    text = nlpReply,
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    lineHeight = 18.sp
                                )
                                if (nlpParsedIntent != null && nlpParsedIntent.action != "none") {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                        Button(
                                            onClick = { viewModel.executeMirozoParsedIntent(nlpParsedIntent) }
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Done, contentDescription = "Execute", modifier = Modifier.size(16.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text("클라우드 일정 등록", fontSize = 12.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isTablet) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.2f)
                        .fillMaxHeight()
                        .padding(end = 16.dp)
                ) {
                    CalendarHeaderSection(
                        year = currentYear,
                        month = currentMonth,
                        onPrev = onPrevMonth,
                        onNext = onNextMonth
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    CalendarGrid(
                        year = currentYear,
                        month = currentMonth,
                        selectedDate = selectedDate,
                        schedulesMap = allSchedulesMap,
                        onDateSelected = onSelectDate,
                        dragAndDropState = dragAndDropState
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(0.8f)
                        .fillMaxHeight()
                ) {
                    DayDetailsSection(
                        selectedDate = selectedDate,
                        schedules = selectedSchedules,
                        onEdit = onEditSchedule,
                        onDelete = onDeleteSchedule,
                        dragAndDropState = dragAndDropState
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    UnscheduledPoolSection(
                        items = unscheduledPool,
                        onAddQuickPool = { onAddQuick("pool") },
                        dragAndDropState = dragAndDropState
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                CalendarHeaderSection(
                    year = currentYear,
                    month = currentMonth,
                    onPrev = onPrevMonth,
                    onNext = onNextMonth
                )

                Spacer(modifier = Modifier.height(8.dp))

                CalendarGrid(
                    year = currentYear,
                    month = currentMonth,
                    selectedDate = selectedDate,
                    schedulesMap = allSchedulesMap,
                    onDateSelected = onSelectDate,
                    dragAndDropState = dragAndDropState
                )

                Spacer(modifier = Modifier.height(16.dp))

                var selectDetailTab by remember { mutableStateOf(true) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp)
                ) {
                    Button(
                        onClick = { selectDetailTab = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (selectDetailTab) MaterialTheme.colorScheme.surface else Color.Transparent,
                            contentColor = if (selectDetailTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Text("일정 상세", fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { selectDetailTab = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!selectDetailTab) MaterialTheme.colorScheme.surface else Color.Transparent,
                            contentColor = if (!selectDetailTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(6.dp),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("미지정 보관함", fontWeight = FontWeight.SemiBold)
                            if (unscheduledPool.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .background(MaterialTheme.colorScheme.error, CircleShape)
                                        .wrapContentSize(Alignment.Center)
                                ) {
                                    Text(
                                        text = "${unscheduledPool.size}",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Box(modifier = Modifier.weight(1f)) {
                    if (selectDetailTab) {
                        DayDetailsSection(
                            selectedDate = selectedDate,
                            schedules = selectedSchedules,
                            onEdit = onEditSchedule,
                            onDelete = onDeleteSchedule,
                            dragAndDropState = dragAndDropState
                        )
                    } else {
                        UnscheduledPoolSection(
                            items = unscheduledPool,
                            onAddQuickPool = { onAddQuick("pool") },
                            dragAndDropState = dragAndDropState
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CalendarHeaderSection(
    year: Int,
    month: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onPrev,
                modifier = Modifier.testTag("prev_month_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "이전 달"
                )
            }

            Text(
                text = "${year}년 ${month}월",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            IconButton(
                onClick = onNext,
                modifier = Modifier.testTag("next_month_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "다음 달"
                )
            }
        }
    }
}

@Composable
fun CalendarGrid(
    year: Int,
    month: Int,
    selectedDate: String,
    schedulesMap: Map<String, List<CalendarScheduleItem>>,
    onDateSelected: (String) -> Unit,
    dragAndDropState: DragAndDropState
) {
    val days = remember(year, month) { getDaysInMonth(year, month) }
    val daysOfWeek = listOf("일", "월", "화", "수", "목", "금", "토")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            daysOfWeek.forEachIndexed { index, day ->
                val textColor = when (index) {
                    0 -> Color(0xFFEF4444)
                    6 -> Color(0xFF3B82F6)
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = day,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = textColor
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Month days grid (6 rows x 7 columns)
        for (row in 0 until 6) {
            Row(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                for (col in 0 until 7) {
                    val index = row * 7 + col
                    if (index < days.size) {
                        val day = days[index]
                        val isSelected = day.dateString == selectedDate
                        val daySchedules = schedulesMap[day.dateString] ?: emptyList()
                        val isHovered = dragAndDropState.hoveredDateString == day.dateString

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .padding(2.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .dropTarget(day.dateString, dragAndDropState)
                                .background(
                                    when {
                                        isSelected -> MaterialTheme.colorScheme.primaryContainer
                                        isHovered -> MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                                        else -> Color.Transparent
                                    }
                                )
                                .clickable {
                                    onDateSelected(day.dateString)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "${day.dayNumber}",
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = when {
                                        isSelected -> MaterialTheme.colorScheme.primary
                                        !day.isCurrentMonth -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                        col == 0 -> Color(0xFFEF4444)
                                        col == 6 -> Color(0xFF3B82F6)
                                        else -> MaterialTheme.colorScheme.onSurface
                                    }
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                if (daySchedules.isNotEmpty()) {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        daySchedules.take(3).forEach { schedule ->
                                            Box(
                                                modifier = Modifier
                                                    .padding(horizontal = 1.dp)
                                                    .size(6.dp)
                                                    .background(Color(schedule.color), CircleShape)
                                            )
                                        }
                                        if (daySchedules.size > 3) {
                                            Text(
                                                text = "+",
                                                fontSize = 8.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.offset(y = (-1).dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DayDetailsSection(
    selectedDate: String,
    schedules: List<CalendarScheduleItem>,
    onEdit: (CalendarScheduleItem) -> Unit,
    onDelete: (CalendarScheduleItem) -> Unit,
    dragAndDropState: DragAndDropState
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp, max = 340.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(
                text = "📅 $selectedDate 일정 (${schedules.size})",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            )

            if (schedules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Empty",
                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "오늘 등록된 일정이 없습니다.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                        Text(
                            text = "하단 보관함에서 항목을 드래그해 오거나\n새 일정을 추가해 보세요!",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(schedules, key = { it.id }) { schedule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .dragSource(schedule, dragAndDropState)
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 5.dp, height = 40.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color(schedule.color))
                            )

                            Spacer(modifier = Modifier.width(12.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = schedule.title,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    if (schedule.googleEventId != null) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "Synced",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (schedule.description.isNotEmpty()) {
                                    Text(
                                        text = schedule.description,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "${schedule.startTimeString} - ${schedule.endTimeString}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            IconButton(
                                onClick = { onEdit(schedule) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "수정",
                                    tint = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            IconButton(
                                onClick = { onDelete(schedule) },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "삭제",
                                    tint = Color(0xFFEF4444),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun UnscheduledPoolSection(
    items: List<CalendarScheduleItem>,
    onAddQuickPool: () -> Unit,
    dragAndDropState: DragAndDropState
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 160.dp, max = 220.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "📥 일정 미지정 보관함 (Drag Pool)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                TextButton(
                    onClick = onAddQuickPool,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add pool", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("추가", fontSize = 13.sp)
                }
            }

            Text(
                text = "꾹 누르고 드래그해서 원하는 날짜 칸에 내려놓으세요!",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "보관함이 비어있습니다.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.id }) { schedule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .dragSource(schedule, dragAndDropState)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color(schedule.color), CircleShape)
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = schedule.title,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (schedule.description.isNotEmpty()) {
                                    Text(
                                        text = schedule.description,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScheduleFormDialog(
    schedule: CalendarScheduleItem? = null,
    initialDate: String = "",
    onDismiss: () -> Unit,
    onConfirm: (title: String, desc: String, date: String, start: String, end: String, color: Int, isPool: Boolean) -> Unit
) {
    var title by remember { mutableStateOf(schedule?.title ?: "") }
    var desc by remember { mutableStateOf(schedule?.description ?: "") }
    var dateString by remember { mutableStateOf(schedule?.dateString ?: initialDate) }
    var start by remember { mutableStateOf(schedule?.startTimeString ?: "09:00") }
    var end by remember { mutableStateOf(schedule?.endTimeString ?: "10:00") }
    var colorHex by remember { mutableStateOf(schedule?.color ?: 0xFF4F46E5.toInt()) }
    var isPool by remember { mutableStateOf(schedule?.dateString == "pool" || initialDate == "pool") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = if (schedule == null) "새 일정 추가" else "일정 상세 수정",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("일정 제목") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = desc,
                    onValueChange = { desc = it },
                    label = { Text("상세 정보") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isPool, onCheckedChange = { isPool = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("보관함에 저장하기 (날짜 미지정)", fontSize = 14.sp)
                }

                if (!isPool) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = dateString,
                        onValueChange = { dateString = it },
                        label = { Text("날짜 (yyyy-MM-dd)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = start,
                            onValueChange = { start = it },
                            label = { Text("시작 (HH:mm)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = end,
                            onValueChange = { end = it },
                            label = { Text("종료 (HH:mm)") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Text("카테고리 색상 선택:", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Spacer(modifier = Modifier.height(8.dp))
                
                val colorsList = listOf(
                    0xFF4F46E5.toInt(), // Indigo
                    0xFF10B981.toInt(), // Teal
                    0xFFEC4899.toInt(), // Pink
                    0xFFF59E0B.toInt(), // Amber
                    0xFF8B5CF6.toInt(), // Purple
                    0xFF6B7280.toInt()  // Slate Gray
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    colorsList.forEach { col ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(col))
                                .clickable { colorHex = col }
                                .border(
                                    width = if (colorHex == col) 3.dp else 1.dp,
                                    color = if (colorHex == col) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = CircleShape
                                )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("취소")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (title.isNotEmpty()) {
                                onConfirm(title, desc, dateString, start, end, colorHex, isPool)
                            }
                        }
                    ) {
                        Text("저장")
                    }
                }
            }
        }
    }
}

// MIROZO GATE SCREEN 1
@Composable
fun MirozoAuthGateScreen(
    viewModel: CalendarViewModel,
    onGoogleLogin: () -> Unit
) {
    var isLoginTab by remember { mutableStateOf(true) }
    var email by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Mirozo Cloud Portal",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "계정 연동을 통해 AI 실시간 대비 추천 및 고정 시간표 맞춤 일정을 분석하십시오.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp)
                ) {
                    Button(
                        onClick = { isLoginTab = true },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isLoginTab) MaterialTheme.colorScheme.surface else Color.Transparent,
                            contentColor = if (isLoginTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("로그인")
                    }
                    Button(
                        onClick = { isLoginTab = false },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (!isLoginTab) MaterialTheme.colorScheme.surface else Color.Transparent,
                            contentColor = if (!isLoginTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("회원가입")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("이메일") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (!isLoginTab) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("이름") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("비밀번호") },
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )

                if (!isLoginTab) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inviteCode,
                        onValueChange = { inviteCode = it },
                        label = { Text("베타 테스터 코드 (선택)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (isLoginTab) {
                            viewModel.mirozoLogin(email, password)
                        } else {
                            viewModel.mirozoRegister(email, name, password, inviteCode)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isLoginTab) "스마트 클라우드 로그인" else "계정 만들기")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onGoogleLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountCircle,
                        contentDescription = "Google",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Google로 계속하기")
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { viewModel.mirozoGuestLogin() }) {
                        Text("방문자로 사용해보기", fontSize = 13.sp)
                    }
                    TextButton(onClick = { viewModel.setUseMirozoCloud(false) }) {
                        Text("오프라인 전용", fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                    }
                }
            }
        }
    }
}

// MIROZO GATE SCREEN 2
@Composable
fun MirozoOnboardingQuizScreen(viewModel: CalendarViewModel) {
    var step by remember { mutableStateOf(1) }
    
    var purpose by remember { mutableStateOf("SELF_STUDY") }
    var studyStyle by remember { mutableStateOf("BALANCED") }
    var distribution by remember { mutableStateOf("EVEN") }
    var maxHours by remember { mutableStateOf(4) }
    var allowMicro by remember { mutableStateOf(true) }
    val sleepStart = "23:00"
    val sleepEnd = "07:30"
    val commuteStart = "08:15"
    val commuteEnd = "09:00"
    var recoveryStyle by remember { mutableStateOf("BALANCED") }
    var startTiming by remember { mutableStateOf("RIGHT_AWAY") }
    var adherence by remember { mutableStateOf("OFTEN") }
    var capacity by remember { mutableStateOf("MEDIUM") }
    var responseStyle by remember { mutableStateOf("REPLAN_FAST") }
    var planningIntensity by remember { mutableStateOf("BALANCED") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = "Mirozo Onboarding ($step/4)",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { step / 4f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                when (step) {
                    1 -> {
                        Text("목표하는 플래너 활용 방향성은 무엇인가요?", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        listOf(
                            "EXAM_PREP" to "고도화된 시험 점수 확보 준비",
                            "ASSIGNMENT_MANAGEMENT" to "마일스톤 분할 과제 마감 관리",
                            "CLASS_AND_LIFE_BALANCE" to "강의 수강과 자기 관리의 균형",
                            "SELF_STUDY" to "자율적 습관 다지기 장기 플랜"
                        ).forEach { (code, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (purpose == code) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { purpose = code }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = purpose == code, onClick = { purpose = code })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, fontSize = 14.sp)
                            }
                        }
                    }
                    2 -> {
                        Text("주요 집중 스타일은 어떻게 형성되어 있습니까?", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        listOf(
                            "DEEP_FOCUS" to "2시간 이상 고몰입 딥 포커스형",
                            "BALANCED" to "50분 집중 / 10분 재충전 뽀모도로형",
                            "MICRO_SESSION" to "15분 틈새 자투리 시간 활용형"
                        ).forEach { (code, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (studyStyle == code) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { studyStyle = code }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = studyStyle == code, onClick = { studyStyle = code })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, fontSize = 14.sp)
                            }
                        }
                    }
                    3 -> {
                        Text("하루 최대 학습 가용 공부 시간을 설정하십시오.", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            IconButton(onClick = { if (maxHours > 1) maxHours-- }) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "down")
                            }
                            Text("$maxHours 시간 / 일", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(horizontal = 16.dp))
                            IconButton(onClick = { if (maxHours < 24) maxHours++ }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "up")
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowMicro, onCheckedChange = { allowMicro = it })
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("자투리 시간에 초단기 일정을 추천받겠습니다.", fontSize = 13.sp)
                        }
                    }
                    4 -> {
                        Text("갑작스러운 일정 마찰이 발생했을 때 해결 방식은?", fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(12.dp))
                        listOf(
                            "REPLAN_FAST" to "AI가 즉시 전체를 분석해 보충 일정을 재분배",
                            "ADJUST_SLOWLY" to "차순위 항목 수동 세팅 조율",
                            "GIVE_UP" to "스킵하고 다음 날부터 플랜 복구"
                        ).forEach { (code, label) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (responseStyle == code) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                    .clickable { responseStyle = code }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = responseStyle == code, onClick = { responseStyle = code })
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(label, fontSize = 14.sp)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (step > 1) {
                        OutlinedButton(onClick = { step-- }) {
                            Text("이전")
                        }
                    } else {
                        Spacer(modifier = Modifier.width(1.dp))
                    }

                    Button(
                        onClick = {
                            if (step < 4) {
                                step++
                            } else {
                                val answers = OnboardingAnswers(
                                    primaryPurpose = purpose,
                                    studyStyle = studyStyle,
                                    preparationDistributionStyle = distribution,
                                    maxStudyHoursPerDay = maxHours,
                                    allowMicroSessions = allowMicro,
                                    sleepStartTime = sleepStart,
                                    sleepEndTime = sleepEnd,
                                    commuteStartTime = commuteStart,
                                    commuteEndTime = commuteEnd,
                                    recoveryStyle = recoveryStyle,
                                    assignmentStartTiming = startTiming,
                                    planAdherence = adherence,
                                    dailyFocusCapacity = capacity,
                                    disruptionResponse = responseStyle,
                                    preferredPlanningIntensity = planningIntensity
                                )
                                viewModel.submitMirozoOnboarding(answers)
                            }
                        }
                    ) {
                        Text(if (step < 4) "다음으로" else "설문 완료 제출")
                    }
                }
            }
        }
    }
}

// MIROZO GATE SCREEN 3
@Composable
fun MirozoTimetableUploadScreen(viewModel: CalendarViewModel) {
    val context = LocalContext.current
    val draftSchedules by viewModel.timetableDraftSchedules.collectAsState()
    val isLoading by viewModel.timetableIsLoading.collectAsState()
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            uploadTimetableImageFromUri(context, uri, viewModel)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "🏫 수강 시간표 연동 동기화",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "학교 시간표 캡처 이미지를 분석하여 매주 고정 수업 일정을 일괄 자동 등록합니다.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                if (draftSchedules == null) {
                    if (isLoading) {
                        Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("이미지 텍스트 파싱 OCR 추출 중...", color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
                            }
                        }
                    } else {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp)
                                .clickable { imagePicker.launch("image/*") },
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = "upload", modifier = Modifier.size(36.dp))
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text("시간표 사진 선택", fontWeight = FontWeight.Bold)
                                    Text("PNG, JPEG, WebP, HEIC 이미지를 업로드", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                }
                            }
                        }
                    }
                } else {
                    Text("💡 분석된 고정 수업 후보군 목록:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    draftSchedules?.forEach { schedule ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Star, contentDescription = "class", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(schedule.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                val dayLabel = when(schedule.dayOfWeek) {
                                    1 -> "월"
                                    2 -> "화"
                                    3 -> "수"
                                    4 -> "목"
                                    5 -> "금"
                                    6 -> "토"
                                    7 -> "일"
                                    else -> "무관"
                                }
                                Text("${dayLabel}요일 | ${schedule.startTime} - ${schedule.endTime}", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.confirmTimetableDraft() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("주별 반복 수업으로 캘린더에 고정")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                TextButton(
                    onClick = { viewModel.skipTimetableOnboarding() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.outline)
                ) {
                    Text("시간표는 매뉴얼로 등록하겠습니다 (지금 스킵)")
                }
            }
        }
    }
}
