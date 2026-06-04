package kr.mirozo.app.data.remote.mirozo

import android.content.Context
import kr.mirozo.app.BuildConfig
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException
import java.util.UUID

object MirozoService {
    private var client: MobileApiClient? = null
    var cookieJar: SharedPrefsCookieJar? = null
        private set

    fun getClient(context: Context): MobileApiClient {
        if (client == null) {
            val jar = SharedPrefsCookieJar(context.applicationContext)
            cookieJar = jar
            
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            }
            
            val okHttpClient = OkHttpClient.Builder()
                .cookieJar(jar)
                .addInterceptor(logging)
                .build()

            val moshi = Moshi.Builder()
                .addLast(KotlinJsonAdapterFactory())
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(normalizedMirozoBaseUrl())
                .client(okHttpClient)
                .addConverterFactory(MoshiConverterFactory.create(moshi))
                .build()

            client = retrofit.create(MobileApiClient::class.java)
        }
        return client!!
    }

    private fun normalizedMirozoBaseUrl(): String {
        val configured = BuildConfig.MIROZO_API_BASE_URL.trim()
        val baseUrl = configured.ifEmpty { "https://mirozo.kr/" }
        return if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
    }
}

fun <T> handleResponse(response: Response<ApiSuccess<T>>): Result<T> {
    if (response.isSuccessful) {
        val body = response.body()
        if (body != null && body.ok) {
            return Result.success(body.data)
        }
    }
    val errorBodyStr = response.errorBody()?.string()
    if (!errorBodyStr.isNullOrEmpty()) {
        try {
            val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
            val failureAdapter = moshi.adapter(ApiFailure::class.java)
            val failure = failureAdapter.fromJson(errorBodyStr)
            if (failure != null && !failure.ok) {
                return Result.failure(Exception("[${failure.error.code}] ${failure.error.message}"))
            }
        } catch (e: Exception) {
            // ignore
        }
    }
    return Result.failure(Exception("HTTP ${response.code()}: ${response.message()}"))
}

class BootstrapRepository(private val context: Context) {
    private val api = MirozoService.getClient(context)
    private val prefs = context.getSharedPreferences("mirozo_bootstrap", Context.MODE_PRIVATE)
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    suspend fun getBootstrap(): Result<MobileBootstrap> {
        return try {
            val res = api.getBootstrap()
            val result = handleResponse(res)
            result.onSuccess { bootstrap ->
                val adapter = moshi.adapter(MobileBootstrap::class.java)
                prefs.edit().putString("cached_bootstrap", adapter.toJson(bootstrap)).apply()
            }
            result
        } catch (e: Exception) {
            val cached = prefs.getString("cached_bootstrap", null)
            if (cached != null) {
                try {
                    val adapter = moshi.adapter(MobileBootstrap::class.java)
                    val bootstrap = adapter.fromJson(cached)
                    if (bootstrap != null) return Result.success(bootstrap)
                } catch (ex: Exception) { /* ignore */ }
            }
            Result.failure(e)
        }
    }

    fun clearCache() {
        prefs.edit().clear().apply()
        MirozoService.cookieJar?.clear()
    }
}

class AuthRepository(private val context: Context) {
    private val api = MirozoService.getClient(context)

    suspend fun login(email: String, password: String): Result<MobileBootstrap> {
        return try {
            val res = api.login(mapOf("email" to email, "password" to password))
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun register(email: String, name: String, password: String, testerCode: String = ""): Result<MobileBootstrap> {
        return try {
            val payload = mutableMapOf(
                "email" to email,
                "name" to name,
                "password" to password,
                "confirmPassword" to password
            )
            if (testerCode.isNotEmpty()) {
                payload["testerCode"] = testerCode
            }
            val res = api.register(payload)
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun guestLogin(): Result<MobileBootstrap> {
        return try {
            val res = api.guestLogin()
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun logout(): Result<MobileBootstrap> {
        return try {
            val res = api.logout()
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestGoogleNonce(mode: String): Result<GoogleNonceResponse> {
        return try {
            val res = api.requestGoogleNonce(GoogleNonceRequest(mode = mode))
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun googleLogin(idToken: String): Result<MobileBootstrap> {
        return try {
            val res = api.googleLogin(GoogleTokenPayload(idToken = idToken))
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun googleConnect(idToken: String): Result<MobileBootstrap> {
        return try {
            val res = api.googleConnect(GoogleTokenPayload(idToken = idToken))
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class MirozoScheduleRepository(private val context: Context) {
    private val api = MirozoService.getClient(context)
    private val prefs = context.getSharedPreferences("mirozo_schedules", Context.MODE_PRIVATE)
    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()

    suspend fun getSchedules(): Result<SchedulesResponse> {
        return try {
            val res = api.getSchedules()
            val result = handleResponse(res)
            result.onSuccess { schedules ->
                val adapter = moshi.adapter(SchedulesResponse::class.java)
                prefs.edit().putString("cached_schedules", adapter.toJson(schedules)).apply()
            }
            result
        } catch (e: Exception) {
            val cached = prefs.getString("cached_schedules", null)
            if (cached != null) {
                try {
                    val adapter = moshi.adapter(SchedulesResponse::class.java)
                    val s = adapter.fromJson(cached)
                    if (s != null) return Result.success(s)
                } catch (ex: Exception) { /* ignore */ }
            }
            Result.failure(e)
        }
    }

    suspend fun createSchedule(payload: CreateSchedulePayload, idempotencyKey: String = UUID.randomUUID().toString()): Result<ScheduleSummary> {
        return try {
            val res = api.createSchedule(idempotencyKey, payload)
            handleResponse(res).map { it.schedule }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createManySchedules(payload: CreateScheduleListPayload, idempotencyKey: String = UUID.randomUUID().toString()): Result<List<ScheduleSummary>> {
        return try {
            val res = api.createManySchedules(idempotencyKey, payload)
            handleResponse(res).map { it.schedules }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun prepareExam(payload: PrepareExamPayload, idempotencyKey: String = UUID.randomUUID().toString()): Result<PrepareExamResponse> {
        return try {
            val res = api.prepareExam(idempotencyKey, payload)
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun prepareTarget(payload: PrepareTargetPayload, idempotencyKey: String = UUID.randomUUID().toString()): Result<PrepareTargetResponse> {
        return try {
            val res = api.prepareTarget(idempotencyKey, payload)
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun patchSchedule(id: Int, payload: SchedulePatchPayload): Result<ScheduleSummary> {
        return try {
            val res = api.patchSchedule(id, payload)
            handleResponse(res).map { it.schedule }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun patchAction(id: Int, payload: PatchActionPayload): Result<ScheduleSummary> {
        return try {
            val res = api.patchAction(id, payload)
            handleResponse(res).map { it.schedule }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun parseNaturalLanguage(query: String, history: List<ChatMessage>? = null, hashtags: List<HashtagInput>? = null): Result<ParseResponse> {
        return try {
            val res = api.parseNaturalLanguage(
                NaturalLanguageParseRequest(
                    message = query,
                    history = history,
                    hashtags = hashtags
                )
            )
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun resolveConflict(payload: ResolveConflictPayload, idempotencyKey: String = UUID.randomUUID().toString()): Result<ResolveConflictResponse> {
        return try {
            val res = api.resolveConflict(idempotencyKey, payload)
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun clearCache() {
        prefs.edit().clear().apply()
    }
}

class SettingsRepository(private val context: Context) {
    private val api = MirozoService.getClient(context)

    suspend fun getSettings(): Result<SettingsResponse> {
        return try {
            val res = api.getSettings()
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun patchSettings(payload: SettingsPatchRequest): Result<SettingsResponse> {
        return try {
            val res = api.patchSettings(payload)
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun patchHashtags(hashtags: List<HashtagInput>): Result<SettingsResponse> {
        return try {
            val res = api.patchHashtags(HashtagsPatchRequest(hashtags = hashtags))
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getOnboarding(): Result<OnboardingSummary> {
        return try {
            val res = api.getOnboarding()
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun submitOnboarding(answers: OnboardingAnswers): Result<OnboardingSummary> {
        return try {
            val res = api.submitOnboarding(answers)
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class TimetableRepository(private val context: Context) {
    private val api = MirozoService.getClient(context)

    suspend fun parseTimetableImage(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String,
        termStart: String,
        termEnd: String
    ): Result<ParseTimetableResponse> {
        return try {
            val actionBody = "parse".toRequestBody("text/plain".toMediaType())
            val termStartBody = termStart.toRequestBody("text/plain".toMediaType())
            val termEndBody = termEnd.toRequestBody("text/plain".toMediaType())
            
            val requestFile = fileBytes.toRequestBody(mimeType.toMediaType())
            val filePart = MultipartBody.Part.createFormData("file", fileName, requestFile)

            val res = api.parseTimetableImage(
                action = actionBody,
                file = filePart,
                termStart = termStartBody,
                termEnd = termEndBody
            )
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun confirmTimetable(
        schedulesJson: String,
        originalScheduleIdsJson: String?,
        termStart: String,
        termEnd: String
    ): Result<ConfirmTimetableResponse> {
        return try {
            val actionBody = "confirm".toRequestBody("text/plain".toMediaType())
            val schedulesBody = schedulesJson.toRequestBody("text/plain".toMediaType())
            val originalScheduleIdsBody = originalScheduleIdsJson?.toRequestBody("text/plain".toMediaType())
            val termStartBody = termStart.toRequestBody("text/plain".toMediaType())
            val termEndBody = termEnd.toRequestBody("text/plain".toMediaType())

            val res = api.confirmTimetable(
                action = actionBody,
                schedulesJson = schedulesBody,
                originalScheduleIdsJson = originalScheduleIdsBody,
                termStart = termStartBody,
                termEnd = termEndBody
            )
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun skipTimetable(): Result<SkipTimetableResponse> {
        return try {
            val actionBody = "skip".toRequestBody("text/plain".toMediaType())
            val res = api.skipTimetable(action = actionBody)
            handleResponse(res)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
