package kr.mirozo.app.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.mirozo.app.data.local.database.AppDatabase
import kr.mirozo.app.data.local.entity.Schedule
import kr.mirozo.app.data.repository.ScheduleRepository
import kr.mirozo.app.data.remote.mirozo.*
import kr.mirozo.app.ui.model.CalendarScheduleItem
import kr.mirozo.app.ui.model.toCalendarScheduleItem
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.UUID

sealed class MirozoAuthState {
    object Checking : MirozoAuthState()
    object Unauthenticated : MirozoAuthState()
    data class OnboardingRequired(val gates: GatesSummary) : MirozoAuthState()
    data class TimetableOnboardingRequired(val gates: GatesSummary) : MirozoAuthState()
    data class Authenticated(val bootstrap: MobileBootstrap) : MirozoAuthState()
}

class CalendarViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: ScheduleRepository
    private val sharedPrefs = application.getSharedPreferences("drag_calendar_prefs", Context.MODE_PRIVATE)

    // Mirozo Cloud Repositories
    val bootstrapRepo = BootstrapRepository(application)
    val authRepo = AuthRepository(application)
    val mirozoScheduleRepo = MirozoScheduleRepository(application)
    val settingsRepo = SettingsRepository(application)
    val timetableRepo = TimetableRepository(application)

    // Mirozo State Flows
    val useMirozoCloud = MutableStateFlow(sharedPrefs.getBoolean("use_mirozo_cloud", false))
    val mirozoAuthState = MutableStateFlow<MirozoAuthState>(MirozoAuthState.Checking)
    val mirozoSchedules = MutableStateFlow<List<ScheduleSummary>>(emptyList())
    val mirozoStats = MutableStateFlow<ScheduleStats?>(null)
    val mirozoSettings = MutableStateFlow<UserScheduleSettings?>(null)
    val mirozoHashtags = MutableStateFlow<List<UserHashtagSummary>>(emptyList())

    // Onboarding Answers cache (for multi-step survey UI)
    val onboardingAnswers = MutableStateFlow<OnboardingAnswers?>(null)

    // Loaded Timetable draft schedules for verification in UI
    val timetableDraftSchedules = MutableStateFlow<List<CreateSchedulePayload>?>(null)
    val timetableTermStart = MutableStateFlow("2026-03-02")
    val timetableTermEnd = MutableStateFlow("2026-06-21")
    val timetableIsLoading = MutableStateFlow(false)

    // Natural Language processing results
    val nlpReply = MutableStateFlow("")
    val nlpParsedIntent = MutableStateFlow<ScheduleChatIntent?>(null)
    val nlpLoading = MutableStateFlow(false)

    // Current selected calendar view date (Year, Month 1-indexed)
    val currentYear = MutableStateFlow(Calendar.getInstance().get(Calendar.YEAR))
    val currentMonth = MutableStateFlow(Calendar.getInstance().get(Calendar.MONTH) + 1) // 1-12
    val selectedDate = MutableStateFlow(getFormattedToday()) // "yyyy-MM-dd"

    // Google API connection state
    val googleToken = MutableStateFlow(sharedPrefs.getString("google_token", "") ?: "")
    val syncStatus = MutableStateFlow<SyncState>(SyncState.Idle)

    // Task Pool/Unassigned schedules (tasks that have dateString == "unassigned")
    val allSchedules: StateFlow<List<CalendarScheduleItem>>

    // Schedules filtered for the selected month to render markers
    val calendarSchedules: StateFlow<Map<String, List<CalendarScheduleItem>>>

    // Schedules for currently selected date
    val selectedDateSchedules: StateFlow<List<CalendarScheduleItem>>

    // Unscheduled / Idea pool (dateString == "unassigned" or "pool")
    val taskPool: StateFlow<List<CalendarScheduleItem>>

    init {
        val database = AppDatabase.getDatabase(application)
        repository = ScheduleRepository(database.scheduleDao())

        // 1. Combine local database stream and remote Mirozo cache stream dynamically!
        allSchedules = combine(
            useMirozoCloud,
            repository.allSchedules,
            mirozoSchedules,
            currentYear,
            currentMonth
        ) { useCloud, localList, mirozoList, year, month ->
            if (useCloud) {
                mapMirozoSchedulesList(mirozoList, year, month)
            } else {
                localList.map { it.toCalendarScheduleItem() }
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        calendarSchedules = allSchedules.map { list ->
            list.filter { it.dateString != "pool" }.groupBy { it.dateString }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

        selectedDateSchedules = combine(allSchedules, selectedDate) { schedules, date ->
            schedules.filter { it.dateString == date }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        taskPool = allSchedules.map { list ->
            list.filter { it.dateString == "pool" }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        // Prepopulate database with mock/starter schedules on first run if empty
        viewModelScope.launch {
            repository.allSchedules.first { true } // wait for initial fetch
            if (repository.allSchedules.first().isEmpty()) {
                prepopulateDatabase()
            }
        }

        // Initialize Mirozo if enabled
        if (useMirozoCloud.value) {
            bootstrapMirozo()
        } else {
            mirozoAuthState.value = MirozoAuthState.Unauthenticated
        }
    }

    private fun getFormattedToday(): String {
        val cal = Calendar.getInstance()
        return String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
    }

    fun selectDate(dateString: String) {
        selectedDate.value = dateString
    }

    fun setYearAndMonth(year: Int, month: Int) {
        currentYear.value = year
        currentMonth.value = month
    }

    fun nextMonth() {
        if (currentMonth.value == 12) {
            currentMonth.value = 1
            currentYear.value += 1
        } else {
            currentMonth.value += 1
        }
    }

    fun previousMonth() {
        if (currentMonth.value == 1) {
            currentMonth.value = 12
            currentYear.value -= 1
        } else {
            currentMonth.value -= 1
        }
    }

    fun saveToken(token: String) {
        googleToken.value = token
        sharedPrefs.edit().putString("google_token", token).apply()
    }

    // Toggle Mirozo Cloud sync on/off!
    fun setUseMirozoCloud(enabled: Boolean) {
        useMirozoCloud.value = enabled
        sharedPrefs.edit().putBoolean("use_mirozo_cloud", enabled).apply()
        if (enabled) {
            bootstrapMirozo()
        } else {
            mirozoAuthState.value = MirozoAuthState.Unauthenticated
        }
    }

    // Mirozo Bootstrap / Setup Lifecycles
    fun bootstrapMirozo() {
        viewModelScope.launch {
            mirozoAuthState.value = MirozoAuthState.Checking
            val result = bootstrapRepo.getBootstrap()
            result.onSuccess { bootstrapSource ->
                if (!bootstrapSource.authenticated) {
                    mirozoAuthState.value = MirozoAuthState.Unauthenticated
                } else if (bootstrapSource.gates.needsOnboarding) {
                    mirozoAuthState.value = MirozoAuthState.OnboardingRequired(bootstrapSource.gates)
                } else if (bootstrapSource.gates.needsTimetableOnboarding) {
                    mirozoAuthState.value = MirozoAuthState.TimetableOnboardingRequired(bootstrapSource.gates)
                } else {
                    mirozoAuthState.value = MirozoAuthState.Authenticated(bootstrapSource)
                    // Save schedule data
                    bootstrapSource.scheduleData?.let { data ->
                        mirozoSchedules.value = data.schedules
                        mirozoStats.value = data.stats
                        mirozoSettings.value = data.settings
                        mirozoHashtags.value = data.hashtags
                    } ?: run {
                        refreshMirozoSchedules()
                    }
                }
            }.onFailure { err ->
                if (err.message?.contains("AUTH_REQUIRED") == true) {
                    mirozoAuthState.value = MirozoAuthState.Unauthenticated
                } else {
                    syncStatus.value = SyncState.Error("Mirozo 부트스트랩 오류: ${err.localizedMessage ?: "연결 실패"}")
                }
            }
        }
    }

    fun refreshMirozoSchedules() {
        viewModelScope.launch {
            val result = mirozoScheduleRepo.getSchedules()
            result.onSuccess { schedulesRes ->
                mirozoSchedules.value = schedulesRes.schedules
                mirozoStats.value = schedulesRes.stats
                mirozoSettings.value = schedulesRes.settings
                mirozoHashtags.value = schedulesRes.hashtags
            }.onFailure { err ->
                if (err.message?.contains("AUTH_REQUIRED") == true) {
                    mirozoAuthState.value = MirozoAuthState.Unauthenticated
                } else {
                    syncStatus.value = SyncState.Error("Mirozo 일정 새로고침 실패: ${err.localizedMessage}")
                }
            }
        }
    }

    // Auth actions
    fun mirozoLogin(email: String, password: String) {
        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val result = authRepo.login(email, password)
            result.onSuccess {
                syncStatus.value = SyncState.Success("로그인에 성공했습니다.")
                setUseMirozoCloud(true)
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("로그인 실패: ${err.message}")
            }
        }
    }

    fun mirozoRegister(email: String, name: String, password: String, testerCode: String = "") {
        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val result = authRepo.register(email, name, password, testerCode)
            result.onSuccess {
                syncStatus.value = SyncState.Success("회원 가입에 성공했습니다.")
                setUseMirozoCloud(true)
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("가입 실패: ${err.message}")
            }
        }
    }

    fun mirozoGuestLogin() {
        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val result = authRepo.guestLogin()
            result.onSuccess {
                syncStatus.value = SyncState.Success("방문자 로그인에 성공했습니다.")
                setUseMirozoCloud(true)
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("방문자 로그인 실패: ${err.message}")
            }
        }
    }

    suspend fun requestGoogleNonce(mode: String): Result<GoogleNonceResponse> {
        return authRepo.requestGoogleNonce(mode)
    }

    fun submitGoogleIdToken(mode: String, idToken: String) {
        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val result = when (mode) {
                "connect" -> authRepo.googleConnect(idToken)
                else -> authRepo.googleLogin(idToken)
            }
            result.onSuccess {
                syncStatus.value = SyncState.Success(
                    if (mode == "connect") "Google 계정이 연결되었습니다." else "Google 로그인에 성공했습니다."
                )
                setUseMirozoCloud(true)
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("Google 인증 실패: ${err.message}")
            }
        }
    }

    fun mirozoLogout() {
        viewModelScope.launch {
            authRepo.logout()
            bootstrapRepo.clearCache()
            mirozoScheduleRepo.clearCache()
            mirozoSchedules.value = emptyList()
            setUseMirozoCloud(false)
            mirozoAuthState.value = MirozoAuthState.Unauthenticated
        }
    }

    fun reportError(message: String) {
        syncStatus.value = SyncState.Error(message)
    }

    // Onboarding Actions
    fun submitMirozoOnboarding(answers: OnboardingAnswers) {
        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val result = settingsRepo.submitOnboarding(answers)
            result.onSuccess {
                bootstrapMirozo()
                syncStatus.value = SyncState.Success("설문 조사가 저장되었습니다.")
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("정보 등록 실패: ${err.message}")
            }
        }
    }

    // Timetable Onboarding Actions
    fun parseTimetableImage(bytes: ByteArray, name: String, mime: String) {
        viewModelScope.launch {
            timetableIsLoading.value = true
            val result = timetableRepo.parseTimetableImage(
                fileBytes = bytes,
                fileName = name,
                mimeType = mime,
                termStart = timetableTermStart.value,
                termEnd = timetableTermEnd.value
            )
            result.onSuccess { response ->
                timetableDraftSchedules.value = response.draftSchedules
                timetableIsLoading.value = false
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("시간표 이미지 파싱 실패: ${err.message}")
                timetableIsLoading.value = false
            }
        }
    }

    fun confirmTimetableDraft() {
        viewModelScope.launch {
            val draft = timetableDraftSchedules.value
            if (draft == null) {
                syncStatus.value = SyncState.Error("제출할 시간표 정보가 부족합니다.")
                return@launch
            }
            timetableIsLoading.value = true
            
            // Standardise payload representation to string JSON
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val listAdapter = moshi.adapter<List<CreateSchedulePayload>>(
                com.squareup.moshi.Types.newParameterizedType(List::class.java, CreateSchedulePayload::class.java)
            )
            val jsonSchedules = listAdapter.toJson(draft)

            val result = timetableRepo.confirmTimetable(
                schedulesJson = jsonSchedules,
                originalScheduleIdsJson = null,
                termStart = timetableTermStart.value,
                termEnd = timetableTermEnd.value
            )
            result.onSuccess {
                timetableDraftSchedules.value = null
                timetableIsLoading.value = false
                bootstrapMirozo()
                syncStatus.value = SyncState.Success("시간표 일정을 성공적으로 연동했습니다!")
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("시간표 연동 완료 실패: ${err.message}")
                timetableIsLoading.value = false
            }
        }
    }

    fun skipTimetableOnboarding() {
        viewModelScope.launch {
            timetableIsLoading.value = true
            val result = timetableRepo.skipTimetable()
            result.onSuccess {
                timetableIsLoading.value = false
                bootstrapMirozo()
                syncStatus.value = SyncState.Success("시간표 연동 단계를 건너뛰었습니다.")
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("연동 건너뛰기 실패: ${err.message}")
                timetableIsLoading.value = false
            }
        }
    }

    // Natural Language Query action
    fun parseMirozoSpeech(query: String) {
        viewModelScope.launch {
            if (query.trim().isEmpty()) return@launch
            nlpLoading.value = true
            nlpReply.value = ""
            nlpParsedIntent.value = null
            
            val result = mirozoScheduleRepo.parseNaturalLanguage(query)
            result.onSuccess { response ->
                nlpReply.value = response.intent.reply
                nlpParsedIntent.value = response.intent
                nlpLoading.value = false
            }.onFailure { err ->
                nlpReply.value = "이해하는 중 오류가 발생했습니다: ${err.message}"
                nlpLoading.value = false
            }
        }
    }

    // Execute Approved AI Natural Language Actions!
    fun executeMirozoParsedIntent(intent: ScheduleChatIntent) {
        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val idempotency = UUID.randomUUID().toString()
            var actionSucceeded = false
            var actionMsg = ""

            when (intent.action) {
                "create" -> {
                    val sch = intent.schedule
                    if (sch != null) {
                        val result = mirozoScheduleRepo.createSchedule(sch, idempotency)
                        if (result.isSuccess) {
                            actionSucceeded = true
                            actionMsg = "일정을 추가했습니다: '${sch.title}'"
                        } else {
                            actionMsg = "추가 실패: ${result.exceptionOrNull()?.message}"
                        }
                    }
                }
                "create_many" -> {
                    val schs = intent.schedules
                    if (!schs.isNullOrEmpty()) {
                        val result = mirozoScheduleRepo.createManySchedules(CreateScheduleListPayload(schs), idempotency)
                        if (result.isSuccess) {
                            actionSucceeded = true
                            actionMsg = "${schs.size}개 일정을 일괄 추가했습니다."
                        } else {
                            actionMsg = "추가 실패: ${result.exceptionOrNull()?.message}"
                        }
                    }
                }
                "prepare_exam" -> {
                    val exam = intent.preparationPlan?.exam
                    val hours = intent.preparationPlan?.preparationHours ?: 0
                    if (exam != null) {
                        val result = mirozoScheduleRepo.prepareExam(
                            PrepareExamPayload(
                                preparationPlan = PreparationPlanInput(exam, hours, intent.preparationPlan?.preparationTitle ?: "시험 준비")
                            ),
                            idempotency
                        )
                        if (result.isSuccess) {
                            actionSucceeded = true
                            actionMsg = "시험 일정을 생성하고 대비 세션을 분배했습니다."
                        } else {
                            actionMsg = "대비 일정 분배 실패: ${result.exceptionOrNull()?.message}"
                        }
                    }
                }
                "prepare_target" -> {
                    val targetId = intent.prepareTarget?.targetScheduleId ?: 0
                    val hours = intent.prepareTarget?.preparationHours ?: 0
                    if (targetId > 0) {
                        val result = mirozoScheduleRepo.prepareTarget(
                            PrepareTargetPayload(
                                targetScheduleId = targetId,
                                preparationHours = hours,
                                preparationTitle = intent.prepareTarget?.preparationTitle ?: "중간 대비",
                                description = intent.prepareTarget?.description
                            ),
                            idempotency
                        )
                        if (result.isSuccess) {
                            actionSucceeded = true
                            actionMsg = "수업 준비 전용 일정이 자동 배정되었습니다."
                        } else {
                            actionMsg = "준비 일정 자동 분배 실패: ${result.exceptionOrNull()?.message}"
                        }
                    }
                }
                "update" -> {
                    val patchUpdate = intent.update
                    if (patchUpdate != null) {
                        val result = mirozoScheduleRepo.patchSchedule(patchUpdate.id, patchUpdate.patch)
                        if (result.isSuccess) {
                            actionSucceeded = true
                            actionMsg = "일정 내용을 성공적으로 업데이트했습니다!"
                        } else {
                            actionMsg = "수정 실패: ${result.exceptionOrNull()?.message}"
                        }
                    }
                }
                "none" -> {
                    actionSucceeded = true
                    actionMsg = intent.reply
                }
                else -> {
                    actionMsg = "아직 지원되지 않는 인텐트 액션입니다 (${intent.action})"
                }
            }

            if (actionSucceeded) {
                refreshMirozoSchedules()
                nlpReply.value = ""
                nlpParsedIntent.value = null
                syncStatus.value = SyncState.Success(actionMsg)
            } else {
                syncStatus.value = SyncState.Error(actionMsg)
            }
        }
    }

    // Resolves standard overlapping conflicts
    fun resolveMirozoConflict(resolutionType: String, originalScheduleId: Int, newDraft: CreateSchedulePayload) {
        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val payload = ResolveConflictPayload(
                resolutionType = resolutionType,
                scheduleId = originalScheduleId,
                newScheduleDraft = newDraft
            )
            val result = mirozoScheduleRepo.resolveConflict(payload)
            result.onSuccess {
                refreshMirozoSchedules()
                syncStatus.value = SyncState.Success("충돌이 성공적으로 해결되었습니다! ${it.conflictResolutionExplanation}")
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("충돌 조율 실패: ${err.message}")
            }
        }
    }

    fun applyMirozoScheduleAction(schedule: CalendarScheduleItem, action: String) {
        if (!useMirozoCloud.value) {
            syncStatus.value = SyncState.Error("클라우드 일정에서만 상태를 변경할 수 있습니다.")
            return
        }

        viewModelScope.launch {
            val original = schedule.mirozoSummary ?: mirozoSchedules.value.find { it.id == schedule.id.toInt() }
            if (original == null) {
                syncStatus.value = SyncState.Error("상태를 변경할 클라우드 일정을 찾지 못했습니다.")
                return@launch
            }

            val payload = PatchActionPayload(
                version = original.version,
                action = action,
                importance = 2,
                difficulty = 2,
                failureReason = if (action == "mark_missed") "PROCRASTINATED" else null
            )
            syncStatus.value = SyncState.Syncing
            val result = mirozoScheduleRepo.patchAction(original.id, payload)
            result.onSuccess {
                refreshMirozoSchedules()
                val message = when (action) {
                    "mark_completed" -> "일정을 완료로 기록했습니다."
                    "mark_missed" -> "미완료로 기록하고 복구 계산에 반영했습니다."
                    "mark_planned" -> "일정을 다시 예정 상태로 돌렸습니다."
                    else -> "일정 상태를 업데이트했습니다."
                }
                syncStatus.value = SyncState.Success(message)
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("상태 변경 실패: ${err.message}")
            }
        }
    }

    fun updateMirozoSettings(payload: SettingsPatchRequest) {
        if (!useMirozoCloud.value) {
            syncStatus.value = SyncState.Error("클라우드 설정을 켠 뒤 저장할 수 있습니다.")
            return
        }

        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val result = settingsRepo.patchSettings(payload)
            result.onSuccess { response ->
                mirozoSettings.value = response.settings
                mirozoHashtags.value = response.hashtags
                syncStatus.value = SyncState.Success("설정을 저장했습니다.")
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("설정 저장 실패: ${err.message}")
            }
        }
    }

    fun updateMirozoHashtags(hashtags: List<HashtagInput>) {
        if (!useMirozoCloud.value) {
            syncStatus.value = SyncState.Error("클라우드 설정을 켠 뒤 해시태그를 저장할 수 있습니다.")
            return
        }

        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val result = settingsRepo.patchHashtags(hashtags)
            result.onSuccess { response ->
                mirozoSettings.value = response.settings
                mirozoHashtags.value = response.hashtags
                syncStatus.value = SyncState.Success("해시태그 색상을 저장했습니다.")
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("해시태그 저장 실패: ${err.message}")
            }
        }
    }

    // CRUD database actions (mapped for both Offline SQLite and Mirozo Cloud!)
    fun addSchedule(
        title: String,
        description: String,
        dateString: String,
        startTime: String = "09:00",
        endTime: String = "10:00",
        color: Int = 0xFF6750A4.toInt(),
        isPool: Boolean = false
    ) {
        if (useMirozoCloud.value) {
            viewModelScope.launch {
                val targetDate = if (isPool) null else dateString
                val payload = CreateSchedulePayload(
                    type = "TEMPORARY",
                    title = title,
                    description = description,
                    startAt = if (targetDate != null) "${targetDate}T${startTime}:00.000Z" else null,
                    endAt = if (targetDate != null) "${targetDate}T${endTime}:00.000Z" else null,
                    startTime = startTime,
                    endTime = endTime
                )
                val result = mirozoScheduleRepo.createSchedule(payload)
                result.onSuccess {
                    refreshMirozoSchedules()
                }.onFailure { err ->
                    syncStatus.value = SyncState.Error("Mirozo 클라우드에 일정 추가 실패: ${err.message}")
                }
            }
        } else {
            viewModelScope.launch {
                val targetDate = if (isPool) "pool" else dateString
                val schedule = Schedule(
                    title = title,
                    description = description,
                    dateString = targetDate,
                    startTimeString = startTime,
                    endTimeString = endTime,
                    color = color
                )
                repository.insert(schedule)
            }
        }
    }

    fun updateSchedule(schedule: CalendarScheduleItem) {
        if (useMirozoCloud.value) {
            viewModelScope.launch {
                val original = schedule.mirozoSummary ?: mirozoSchedules.value.find { it.id == schedule.id.toInt() }
                if (original != null) {
                    val isPool = schedule.dateString == "pool"
                    val patch = SchedulePatchPayload(
                        title = schedule.title,
                        description = schedule.description,
                        startAt = if (isPool) null else "${schedule.dateString}T${schedule.startTimeString}:00.000Z",
                        endAt = if (isPool) null else "${schedule.dateString}T${schedule.endTimeString}:00.000Z",
                        startTime = schedule.startTimeString,
                        endTime = schedule.endTimeString,
                        version = original.version
                    )
                    val result = mirozoScheduleRepo.patchSchedule(original.id, patch)
                    result.onSuccess {
                        refreshMirozoSchedules()
                    }.onFailure { err ->
                        syncStatus.value = SyncState.Error("클라우드 일정 수정 실패: ${err.message}")
                    }
                }
            }
        } else {
            viewModelScope.launch {
                repository.update(schedule.toLocalSchedule())
            }
        }
    }

    fun deleteSchedule(schedule: CalendarScheduleItem) {
        if (useMirozoCloud.value) {
            viewModelScope.launch {
                val original = schedule.mirozoSummary ?: mirozoSchedules.value.find { it.id == schedule.id.toInt() }
                if (original != null) {
                    val patch = SchedulePatchPayload(
                        status = "CANCELED",
                        version = original.version
                    )
                    val result = mirozoScheduleRepo.patchSchedule(original.id, patch)
                    result.onSuccess {
                        refreshMirozoSchedules()
                    }.onFailure { err ->
                        syncStatus.value = SyncState.Error("클라우드 일정 취소 실패: ${err.message}")
                    }
                }
            }
        } else {
            viewModelScope.launch {
                val localSchedule = schedule.toLocalSchedule()
                repository.delete(localSchedule)
                val token = googleToken.value
                val eventId = localSchedule.googleEventId
                if (token.isNotEmpty() && eventId != null) {
                    repository.removeFromGoogle(eventId, token)
                }
            }
        }
    }

    fun moveSchedule(schedule: CalendarScheduleItem, newDateString: String) {
        if (useMirozoCloud.value) {
            viewModelScope.launch {
                val original = schedule.mirozoSummary ?: mirozoSchedules.value.find { it.id == schedule.id.toInt() }
                if (original != null) {
                    val isPool = newDateString == "pool"
                    val patch = SchedulePatchPayload(
                        startAt = if (isPool) null else "${newDateString}T${schedule.startTimeString}:00.000Z",
                        endAt = if (isPool) null else "${newDateString}T${schedule.endTimeString}:00.000Z",
                        startTime = schedule.startTimeString,
                        endTime = schedule.endTimeString,
                        version = original.version
                    )
                    val result = mirozoScheduleRepo.patchSchedule(original.id, patch)
                    result.onSuccess {
                        refreshMirozoSchedules()
                    }.onFailure { err ->
                        syncStatus.value = SyncState.Error("클라우드 일정 이동 실패: ${err.message}")
                    }
                }
            }
        } else {
            viewModelScope.launch {
                val updated = schedule.toLocalSchedule().copy(
                    dateString = newDateString,
                    isGoogleSynced = false
                )
                repository.update(updated)

                val token = googleToken.value
                if (token.isNotEmpty()) {
                    repository.pushToGoogle(updated, token)
                }
            }
        }
    }

    // Bidirectional Google sync (Local mode remains unaffected)
    fun syncGoogleCalendar() {
        val token = googleToken.value
        if (token.isEmpty()) {
            syncStatus.value = SyncState.Error("토큰이 비어있습니다. 액세스 토큰을 먼저 입력해주세요.")
            return
        }

        viewModelScope.launch {
            syncStatus.value = SyncState.Syncing
            val result = repository.syncWithGoogle(token)
            result.onSuccess {
                val unSynced = repository.allSchedules.first().filter { !it.isGoogleSynced && it.dateString != "pool" }
                var pushSuccessCount = 0
                unSynced.forEach { schedule ->
                    val pushResult = repository.pushToGoogle(schedule, token)
                    if (pushResult.isSuccess) {
                        pushSuccessCount++
                    }
                }
                syncStatus.value = SyncState.Success("동기화 성공! (${pushSuccessCount}개 올림)")
            }.onFailure { err ->
                syncStatus.value = SyncState.Error("동기화 오류: ${err.localizedMessage ?: "알 수 없음"}")
            }
        }
    }

    fun resetSyncStatus() {
        syncStatus.value = SyncState.Idle
    }

    // Maps Mirozo schedule summaries to standard frontend models for visual display
    private fun mapMirozoSchedulesList(mirozoList: List<ScheduleSummary>, year: Int, month: Int): List<CalendarScheduleItem> {
        val mappedList = mutableListOf<CalendarScheduleItem>()
        // Keep canceled schedules available so the UI can hide them by default and reveal them when filtered.
        val activeMirozo = mirozoList.filter { it.isActive || it.status == "CANCELED" }

        activeMirozo.forEach { mirozo ->
            when (mirozo.type) {
                "TEMPORARY", "PREPARING" -> {
                    val dateString = if (!mirozo.startAt.isNullOrEmpty() && mirozo.startAt.length >= 10) {
                        mirozo.startAt.substring(0, 10)
                    } else if (!mirozo.fixedOccurrenceDateKey.isNullOrEmpty()) {
                        mirozo.fixedOccurrenceDateKey
                    } else {
                        "pool"
                    }
                    mappedList.add(mapSummaryToUI(mirozo, dateString))
                }
                "FIXED" -> {
                    if (!mirozo.fixedOccurrenceDateKey.isNullOrEmpty()) {
                        mappedList.add(mapSummaryToUI(mirozo, mirozo.fixedOccurrenceDateKey))
                    } else if (mirozo.dayOfWeek != null) {
                        // Project weekly recurring schedules into this month
                        val targetDayOfWeek = when (mirozo.dayOfWeek) {
                            7 -> Calendar.SUNDAY
                            1 -> Calendar.MONDAY
                            2 -> Calendar.TUESDAY
                            3 -> Calendar.WEDNESDAY
                            4 -> Calendar.THURSDAY
                            5 -> Calendar.FRIDAY
                            6 -> Calendar.SATURDAY
                            else -> Calendar.MONDAY
                        }
                        
                        val cal = Calendar.getInstance()
                        cal.set(Calendar.YEAR, year)
                        cal.set(Calendar.MONTH, month - 1)
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        val maxDay = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
                        
                        for (day in 1..maxDay) {
                            cal.set(Calendar.DAY_OF_MONTH, day)
                            if (cal.get(Calendar.DAY_OF_WEEK) == targetDayOfWeek) {
                                val dateKey = String.format("%04d-%02d-%02d", year, month, day)
                                mappedList.add(mapSummaryToUI(mirozo, dateKey))
                            }
                        }
                    } else {
                        mappedList.add(mapSummaryToUI(mirozo, "pool"))
                    }
                }
                else -> {
                    mappedList.add(mapSummaryToUI(mirozo, "pool"))
                }
            }
        }
        return mappedList
    }

    private fun mapSummaryToUI(mirozo: ScheduleSummary, dateString: String): CalendarScheduleItem {
        val colorInt = when (mirozo.type) {
            "FIXED" -> 0xFF4F46E5.toInt() // Indigo
            "TEMPORARY" -> 0xFF10B981.toInt() // Teal
            "PREPARING" -> 0xFFEC4899.toInt() // Pink
            else -> 0xFF6B7280.toInt() // Slate Grey
        }
        
        return CalendarScheduleItem(
            id = mirozo.id.toLong(),
            title = mirozo.title,
            description = mirozo.description ?: "",
            dateString = dateString,
            startTimeString = mirozo.startTime ?: "09:00",
            endTimeString = mirozo.endTime ?: "10:00",
            color = colorInt,
            isGoogleSynced = mirozo.isMeetAppointment,
            googleEventId = if (mirozo.preparingCount > 0) "has_preparing" else null,
            mirozoSummary = mirozo
        )
    }

    private suspend fun prepopulateDatabase() {
        val today = getFormattedToday()
        val cal = Calendar.getInstance()
        
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterday = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
        
        cal.add(Calendar.DAY_OF_YEAR, 2)
        val tomorrow = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

        val scheduleList = listOf(
            Schedule(
                title = "아침 요가 & 가벼운 스트레칭",
                description = "하루를 상쾌하게 시작하는 요가 세션",
                dateString = today,
                startTimeString = "07:30",
                endTimeString = "08:15",
                color = 0xFF10B981.toInt()
            ),
            Schedule(
                title = "프로젝트 기획 워크숍",
                description = "캘린더 앱 드래그 앤 드롭 및 동기화 설계 회의",
                dateString = today,
                startTimeString = "14:00",
                endTimeString = "15:30",
                color = 0xFF4F46E5.toInt()
            ),
            Schedule(
                title = "개발 파트 정기 화상 회의",
                description = "Google Meet를 통한 정기 팀 싱크업",
                dateString = tomorrow,
                startTimeString = "11:00",
                endTimeString = "12:00",
                color = 0xFFEC4899.toInt()
            ),
            Schedule(
                title = "가족 저녁 식사 약속",
                description = "시내 레스토랑에서 모임 예약 완료",
                dateString = yesterday,
                startTimeString = "19:00",
                endTimeString = "21:00",
                color = 0xFFF59E0B.toInt()
            ),
            Schedule(
                title = "도서관에 책 반납하기",
                description = "드래그하여 원하는 날짜에 직접 놓아보세요!",
                dateString = "pool",
                color = 0xFF8B5CF6.toInt()
            ),
            Schedule(
                title = "차량 정기 검사 예약",
                description = "카센터 일정 조율해서 날짜 정하기",
                dateString = "pool",
                color = 0xFF6B7280.toInt()
            )
        )

        scheduleList.forEach { repository.insert(it) }
    }
}

sealed class SyncState {
    object Idle : SyncState()
    object Syncing : SyncState()
    data class Success(val message: String) : SyncState()
    data class Error(val error: String) : SyncState()
}
