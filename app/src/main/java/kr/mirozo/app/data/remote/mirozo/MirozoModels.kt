package kr.mirozo.app.data.remote.mirozo

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ApiSuccess<T>(
    val ok: Boolean,
    val data: T
)

@JsonClass(generateAdapter = true)
data class ApiFailureMessage(
    val code: String,
    val message: String,
    val details: Any? = null
)

@JsonClass(generateAdapter = true)
data class ApiFailure(
    val ok: Boolean,
    val error: ApiFailureMessage
)

@JsonClass(generateAdapter = true)
data class MobileUser(
    val id: Int,
    val email: String,
    val name: String?,
    val role: String, // "USER" | "ADMIN"
    @Json(name = "isGuest") val isGuest: Boolean
)

@JsonClass(generateAdapter = true)
data class OAuthAccountSummary(
    val provider: String, // "GOOGLE"
    val email: String?,
    val emailVerified: Boolean,
    val linkedAt: String,
    val lastLoginAt: String?
)

@JsonClass(generateAdapter = true)
data class GatesSummary(
    val needsOnboarding: Boolean,
    val needsTimetableOnboarding: Boolean
)

@JsonClass(generateAdapter = true)
data class GoogleOAuthSummary(
    val configured: Boolean,
    val linkedAccounts: List<OAuthAccountSummary>
)

@JsonClass(generateAdapter = true)
data class OAuthSummary(
    val google: GoogleOAuthSummary
)

@JsonClass(generateAdapter = true)
data class UserPreferenceSummary(
    val primaryPurpose: String, // "EXAM_PREP" | "ASSIGNMENT_MANAGEMENT" | "CLASS_AND_LIFE_BALANCE" | "SELF_STUDY"
    val studyStyle: String, // "DEEP_FOCUS" | "BALANCED" | "MICRO_SESSION"
    val preparationDistributionStyle: String, // "EVEN" | "EARLY" | "DEADLINE_WEIGHTED" | "HILL"
    val maxStudyHoursPerDay: Int,
    val allowMicroSessions: Boolean,
    val minSessionMinutes: Int,
    val preferredSessionMinutes: Int,
    val maxSessionMinutes: Int,
    val sleepStartTime: String,
    val sleepEndTime: String,
    val recoveryStyle: String, // "GENTLE" | "BALANCED" | "URGENT"
    val supportsOpenEndedStudy: Boolean,
    val onboardingCompletedAt: String?,
    val scheduleTutorialCompletedAt: String?,
    val schoolTimetableImportedAt: String?,
    val schoolTimetableTerm: String?,
    val schoolTimetableUploadDeclined: Boolean
)

@JsonClass(generateAdapter = true)
data class UserProfileSummary(
    val planningType: String, // "RELAXED" | "BALANCED" | "INTENSIVE"
    val successCount: Int,
    val failureCount: Int,
    val successStreak: Int,
    val failureStreak: Int
)

@JsonClass(generateAdapter = true)
data class OnboardingSummary(
    val preference: UserPreferenceSummary,
    val profile: UserProfileSummary
)

@JsonClass(generateAdapter = true)
data class UserHashtagSummary(
    val id: Int,
    val name: String,
    val color: String,
    val isDefault: Boolean,
    val createdAt: String,
    val updatedAt: String
)

@JsonClass(generateAdapter = true)
data class UserScheduleSettings(
    val sleepStart: String,
    val sleepEnd: String,
    val commuteStart: String?,
    val commuteEnd: String?,
    val commuteDays: List<Int>,
    val studyBlockMinutes: Int,
    val dailyStudyLimitMinutes: Int,
    val studyTendency: String, // "EARLY" | "BALANCED" | "LATE"
    val theme: String, // "LIGHT" | "DARK", etc.
    val parserProvider: String,
    val parserModel: String,
    val parserApiKeySet: Boolean
)

@JsonClass(generateAdapter = true)
data class SchedulesResponse(
    val schedules: List<ScheduleSummary>,
    val stats: ScheduleStats,
    val settings: UserScheduleSettings,
    val hashtags: List<UserHashtagSummary>,
    val preference: UserPreferenceSummary,
    val profile: UserProfileSummary
)

@JsonClass(generateAdapter = true)
data class MobileBootstrap(
    val appVersionLabel: String,
    val authenticated: Boolean,
    val user: MobileUser?,
    val gates: GatesSummary,
    val oauth: OAuthSummary,
    val scheduleData: SchedulesResponse?,
    val onboarding: OnboardingSummary?
)

@JsonClass(generateAdapter = true)
data class ScheduleSummary(
    val id: Int,
    val type: String, // "FIXED" | "TEMPORARY" | "PREPARING"
    val title: String,
    val description: String?,
    val people: String?,
    val location: String?,
    val dayOfWeek: Int?,
    val startTime: String?,
    val endTime: String?,
    val startAt: String?,
    val endAt: String?,
    val status: String, // "PLANNED" | "COMPLETED" | "MISSED" | "RESCHEDULED" | "CANCELED"
    val completedMinutes: Int?,
    val isActive: Boolean,
    val version: Int,
    val recurrenceSeriesId: String?,
    val recurrenceRuleJson: String?,
    val recurrenceIndex: Int?,
    val recurrenceStartsOn: String?,
    val recurrenceEndsOn: String?,
    val fixedOccurrenceParentId: Int?,
    val fixedOccurrenceDateKey: String?,
    val parentScheduleId: Int?,
    val parentSchedule: ParentScheduleSummary?,
    val targetScheduleId: Int?,
    val targetSchedule: TargetScheduleSummary?,
    val isMeetAppointment: Boolean,
    val preparingCount: Int,
    val createdAt: String,
    val updatedAt: String
)

@JsonClass(generateAdapter = true)
data class ParentScheduleSummary(val id: Int, val title: String)

@JsonClass(generateAdapter = true)
data class TargetScheduleSummary(val id: Int, val title: String)

@JsonClass(generateAdapter = true)
data class ScheduleStats(
    val total: Int,
    val fixed: Int,
    val temporary: Int,
    val preparing: Int,
    val active: Int
)

@JsonClass(generateAdapter = true)
data class OnboardingAnswers(
    val primaryPurpose: String,
    val studyStyle: String,
    val preparationDistributionStyle: String,
    val maxStudyHoursPerDay: Int,
    val allowMicroSessions: Boolean,
    val sleepStartTime: String,
    val sleepEndTime: String,
    val commuteStartTime: String,
    val commuteEndTime: String,
    val recoveryStyle: String,
    val assignmentStartTiming: String, // "RIGHT_AWAY" | "FEW_DAYS_BEFORE" | "LAST_MINUTE"
    val planAdherence: String, // "OFTEN" | "SOMETIMES" | "RARELY"
    val dailyFocusCapacity: String, // "SHORT" | "MEDIUM" | "LONG"
    val disruptionResponse: String, // "REPLAN_FAST" | "ADJUST_SLOWLY" | "GIVE_UP"
    val preferredPlanningIntensity: String // "RELAXED" | "BALANCED" | "INTENSIVE"
)

@JsonClass(generateAdapter = true)
data class ScheduleRecurrenceInput(
    val startsOn: String? = null,
    val endsOn: String? = null,
    val intervalWeeks: Int? = null,
    val weekPattern: String? = null,
    val anchorDate: String? = null
)

@JsonClass(generateAdapter = true)
data class CreateSchedulePayload(
    val type: String, // "FIXED" | "TEMPORARY" | "PREPARING"
    val title: String,
    val status: String? = null,
    val description: String? = null,
    val people: String? = null,
    val location: String? = null,
    val dayOfWeek: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val startAt: String? = null,
    val endAt: String? = null,
    val recurrence: ScheduleRecurrenceInput? = null,
    val recurrenceSeriesId: String? = null,
    val recurrenceRuleJson: String? = null,
    val recurrenceIndex: Int? = null,
    val recurrenceStartsOn: String? = null,
    val recurrenceEndsOn: String? = null,
    val parentScheduleId: Int? = null,
    val targetScheduleId: Int? = null
)

@JsonClass(generateAdapter = true)
data class CreateScheduleListPayload(
    val schedules: List<CreateSchedulePayload>
)

@JsonClass(generateAdapter = true)
data class SchedulePatchPayload(
    val type: String? = null,
    val title: String? = null,
    val description: String? = null,
    val people: String? = null,
    val location: String? = null,
    val dayOfWeek: Int? = null,
    val startTime: String? = null,
    val endTime: String? = null,
    val startAt: String? = null,
    val endAt: String? = null,
    val status: String? = null,
    val version: Int? = null,
    val recurrenceSeriesId: String? = null,
    val recurrenceRuleJson: String? = null,
    val recurrenceIndex: Int? = null,
    val recurrenceStartsOn: String? = null,
    val recurrenceEndsOn: String? = null,
    val parentScheduleId: Int? = null,
    val targetScheduleId: Int? = null
)

@JsonClass(generateAdapter = true)
data class NaturalLanguageParseRequest(
    val message: String,
    val history: List<ChatMessage>? = null,
    val draft: CreateSchedulePayload? = null,
    val hashtags: List<HashtagInput>? = null
)

@JsonClass(generateAdapter = true)
data class ChatMessage(
    val role: String,
    val content: String
)

@JsonClass(generateAdapter = true)
data class HashtagInput(
    val name: String,
    val color: String
)

@JsonClass(generateAdapter = true)
data class GoogleNonceRequest(
    val mode: String // "login" | "connect"
)

@JsonClass(generateAdapter = true)
data class GoogleNonceResponse(
    val nonce: String,
    val mode: String,
    val expiresAt: String
)

@JsonClass(generateAdapter = true)
data class GoogleTokenPayload(
    val idToken: String
)

@JsonClass(generateAdapter = true)
data class SingleScheduleResponse(
    val schedule: ScheduleSummary
)

@JsonClass(generateAdapter = true)
data class MultiSchedulesResponse(
    val schedules: List<ScheduleSummary>
)

@JsonClass(generateAdapter = true)
data class PrepareExamPayload(
    val action: String = "prepare_exam",
    val preparationPlan: PreparationPlanInput
)

@JsonClass(generateAdapter = true)
data class PrepareExamResponse(
    val schedule: ScheduleSummary?,
    val schedules: List<ScheduleSummary>,
    val unscheduledMinutes: Int,
    val unscheduledMinutesByTarget: Map<String, Int>?,
    val explanation: String
)

@JsonClass(generateAdapter = true)
data class PreparationPlanInput(
    val exam: CreateSchedulePayload,
    val preparationHours: Int,
    val preparationTitle: String
)

@JsonClass(generateAdapter = true)
data class PrepareTargetPayload(
    val action: String = "prepare_target",
    val targetScheduleId: Int,
    val preparationHours: Int,
    val preparationTitle: String,
    val description: String? = null
)

@JsonClass(generateAdapter = true)
data class PrepareTargetResponse(
    val schedules: List<ScheduleSummary>,
    val unscheduledMinutes: Int,
    val explanation: String
)

@JsonClass(generateAdapter = true)
data class PatchActionPayload(
    val version: Int,
    val action: String, // "mark_completed" | "mark_missed" | "update_recurrence_series" | "skip_fixed_occurrence"
    val importance: Int? = null,
    val difficulty: Int? = null,
    val failureReason: String? = null,
    val scope: String? = null,
    val dateKey: String? = null,
    val patch: SchedulePatchPayload? = null
)

@JsonClass(generateAdapter = true)
data class ParseTimetableResponse(
    val draftSchedules: List<CreateSchedulePayload>,
    val importedCount: Int,
    val parserModel: String,
    val termStart: String,
    val termEnd: String
)

@JsonClass(generateAdapter = true)
data class ConfirmTimetableResponse(
    val schedules: List<ScheduleSummary>,
    val preference: UserPreferenceSummary,
    val deletedCount: Int,
    val importedCount: Int,
    val createdCount: Int,
    val duplicateCount: Int,
    val termStart: String,
    val termEnd: String
)

@JsonClass(generateAdapter = true)
data class SkipTimetableResponse(
    val schedules: List<ScheduleSummary>,
    val preference: UserPreferenceSummary,
    val createdCount: Int,
    val duplicateCount: Int,
    val skipped: Boolean
)

@JsonClass(generateAdapter = true)
data class ParseResponse(
    val intent: ScheduleChatIntent,
    val parserSource: String? = null
)

@JsonClass(generateAdapter = true)
data class ScheduleChatIntent(
    val action: String,
    val reply: String,
    val confidence: Double,
    val missingFields: List<String>,
    val schedule: CreateSchedulePayload? = null,
    val schedules: List<CreateSchedulePayload>? = null,
    val preparationPlan: PreparationPlanOutput? = null,
    val preparationPlans: List<PreparationPlanOutput>? = null,
    val prepareTarget: PrepareTargetOutput? = null,
    val update: UpdateOutput? = null,
    val skipFixedOccurrences: SkipFixedOccurrencesOutput? = null,
    val intents: List<ScheduleChatIntent>? = null
)

@JsonClass(generateAdapter = true)
data class PreparationPlanOutput(
    val exam: CreateSchedulePayload,
    val preparationHours: Int,
    val preparationTitle: String? = null
)

@JsonClass(generateAdapter = true)
data class PrepareTargetOutput(
    val targetScheduleId: Int,
    val preparationHours: Int,
    val preparationTitle: String? = null,
    val description: String? = null
)

@JsonClass(generateAdapter = true)
data class UpdateOutput(
    val id: Int,
    val patch: SchedulePatchPayload,
    val version: Int? = null
)

@JsonClass(generateAdapter = true)
data class SkipFixedOccurrencesOutput(
    val dateKey: String,
    val scheduleIds: List<Int>? = null
)

@JsonClass(generateAdapter = true)
data class ResolveConflictPayload(
    val resolutionType: String,
    val newScheduleDraft: CreateSchedulePayload? = null,
    val newScheduleDrafts: List<CreateSchedulePayload>? = null,
    val scheduleId: Int? = null,
    val proposedStartAt: String? = null,
    val proposedEndAt: String? = null,
    val affectedScheduleIds: List<Int>? = null,
    val expectedVersions: Map<String, Int>? = null
)

@JsonClass(generateAdapter = true)
data class ResolveConflictResponse(
    val schedules: List<ScheduleSummary>,
    val conflictResolutionExplanation: String,
    val notices: List<String>
)

@JsonClass(generateAdapter = true)
data class SettingsResponse(
    val settings: UserScheduleSettings,
    val hashtags: List<UserHashtagSummary>
)

@JsonClass(generateAdapter = true)
data class SettingsPatchRequest(
    val sleepStart: String? = null,
    val sleepEnd: String? = null,
    val commuteStart: String? = null,
    val commuteEnd: String? = null,
    val commuteDays: List<Int>? = null,
    val studyBlockMinutes: Int? = null,
    val dailyStudyLimitMinutes: Int? = null,
    val studyTendency: String? = null,
    val theme: String? = null,
    val hashtags: List<HashtagInput>? = null
)

@JsonClass(generateAdapter = true)
data class HashtagsPatchRequest(
    val hashtags: List<HashtagInput>
)
