package kr.mirozo.app.data.remote.mirozo

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.Response
import retrofit2.http.*

interface MobileApiClient {

    @GET("api/mobile/v1/bootstrap")
    suspend fun getBootstrap(): Response<ApiSuccess<MobileBootstrap>>

    @POST("api/mobile/v1/auth/login")
    suspend fun login(@Body credentials: Map<String, String>): Response<ApiSuccess<MobileBootstrap>>

    @POST("api/mobile/v1/auth/register")
    suspend fun register(@Body payload: Map<String, String>): Response<ApiSuccess<MobileBootstrap>>

    @POST("api/mobile/v1/auth/guest")
    suspend fun guestLogin(): Response<ApiSuccess<MobileBootstrap>>

    @POST("api/mobile/v1/auth/logout")
    suspend fun logout(): Response<ApiSuccess<MobileBootstrap>>

    @POST("api/mobile/v1/auth/google/nonce")
    suspend fun requestGoogleNonce(@Body payload: GoogleNonceRequest): Response<ApiSuccess<GoogleNonceResponse>>

    @POST("api/mobile/v1/auth/google/login")
    suspend fun googleLogin(@Body payload: GoogleTokenPayload): Response<ApiSuccess<MobileBootstrap>>

    @POST("api/mobile/v1/auth/google/connect")
    suspend fun googleConnect(@Body payload: GoogleTokenPayload): Response<ApiSuccess<MobileBootstrap>>

    @GET("api/mobile/v1/schedules")
    suspend fun getSchedules(): Response<ApiSuccess<SchedulesResponse>>

    @POST("api/mobile/v1/schedules")
    suspend fun createSchedule(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body payload: CreateSchedulePayload
    ): Response<ApiSuccess<SingleScheduleResponse>>

    @POST("api/mobile/v1/schedules")
    suspend fun createManySchedules(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body payload: CreateScheduleListPayload
    ): Response<ApiSuccess<MultiSchedulesResponse>>

    @POST("api/mobile/v1/schedules")
    suspend fun prepareExam(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body payload: PrepareExamPayload
    ): Response<ApiSuccess<PrepareExamResponse>>

    @POST("api/mobile/v1/schedules")
    suspend fun prepareTarget(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body payload: PrepareTargetPayload
    ): Response<ApiSuccess<PrepareTargetResponse>>

    @PATCH("api/mobile/v1/schedules/{id}")
    suspend fun patchSchedule(
        @Path("id") id: Int,
        @Body payload: SchedulePatchPayload
    ): Response<ApiSuccess<SingleScheduleResponse>>

    @PATCH("api/mobile/v1/schedules/{id}")
    suspend fun patchAction(
        @Path("id") id: Int,
        @Body payload: PatchActionPayload
    ): Response<ApiSuccess<SingleScheduleResponse>>

    @POST("api/mobile/v1/schedules/parse")
    suspend fun parseNaturalLanguage(@Body payload: NaturalLanguageParseRequest): Response<ApiSuccess<ParseResponse>>

    @POST("api/mobile/v1/schedules/resolve-conflict")
    suspend fun resolveConflict(
        @Header("Idempotency-Key") idempotencyKey: String,
        @Body payload: ResolveConflictPayload
    ): Response<ApiSuccess<ResolveConflictResponse>>

    @GET("api/mobile/v1/settings")
    suspend fun getSettings(): Response<ApiSuccess<SettingsResponse>>

    @PATCH("api/mobile/v1/settings")
    suspend fun patchSettings(@Body payload: SettingsPatchRequest): Response<ApiSuccess<SettingsResponse>>

    @PATCH("api/mobile/v1/hashtags")
    suspend fun patchHashtags(@Body payload: HashtagsPatchRequest): Response<ApiSuccess<SettingsResponse>>

    @GET("api/mobile/v1/onboarding")
    suspend fun getOnboarding(): Response<ApiSuccess<OnboardingSummary>>

    @POST("api/mobile/v1/onboarding")
    suspend fun submitOnboarding(@Body answers: OnboardingAnswers): Response<ApiSuccess<OnboardingSummary>>

    @Multipart
    @POST("api/mobile/v1/timetable")
    suspend fun parseTimetableImage(
        @Part("action") action: RequestBody,
        @Part file: MultipartBody.Part,
        @Part("termStart") termStart: RequestBody,
        @Part("termEnd") termEnd: RequestBody
    ): Response<ApiSuccess<ParseTimetableResponse>>

    @Multipart
    @POST("api/mobile/v1/timetable")
    suspend fun confirmTimetable(
        @Part("action") action: RequestBody,
        @Part("schedules") schedulesJson: RequestBody,
        @Part("originalScheduleIds") originalScheduleIdsJson: RequestBody?,
        @Part("termStart") termStart: RequestBody,
        @Part("termEnd") termEnd: RequestBody
    ): Response<ApiSuccess<ConfirmTimetableResponse>>

    @Multipart
    @POST("api/mobile/v1/timetable")
    suspend fun skipTimetable(
        @Part("action") action: RequestBody
    ): Response<ApiSuccess<SkipTimetableResponse>>
}
