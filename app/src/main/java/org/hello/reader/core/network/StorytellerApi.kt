package org.hello.reader.core.network

import org.hello.reader.core.network.model.BookDetailResponseDto
import org.hello.reader.core.network.model.HealthResponseDto
import org.hello.reader.core.network.model.LoginRequestDto
import org.hello.reader.core.network.model.StorytellerPositionPayloadDto
import org.hello.reader.core.network.model.SyncCheckpointDto
import org.hello.reader.core.network.model.SyncResponseDto
import org.hello.reader.core.network.model.TokenResponseDto
import org.hello.reader.core.network.model.UserDto
import okhttp3.ResponseBody
import kotlinx.serialization.json.JsonObject
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Storyteller REST contract. Paths are relative; [ServerAuthInterceptor]
 * resolves them against the active profile, or against `targetBaseUrl` when
 * given (Retrofit omits a header whose value is null).
 */
interface StorytellerApi {

    /** Verifies server responsiveness before login. */
    @GET("api/health")
    @Headers("${ServerAuthInterceptor.HEADER_NO_AUTH}: true")
    suspend fun checkHealth(
        @Header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL) targetBaseUrl: String? = null,
    ): Response<HealthResponseDto>

    /** Exchanges user credentials for a Bearer token. */
    @POST("api/token")
    @Headers("${ServerAuthInterceptor.HEADER_NO_AUTH}: true")
    suspend fun login(
        @Body request: LoginRequestDto,
        @Header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL) targetBaseUrl: String? = null,
    ): Response<TokenResponseDto>

    /**
     * OAuth2 password-grant form of [login], used as a fallback when a server
     * rejects the JSON body with 415/422.
     */
    @FormUrlEncoded
    @POST("api/token")
    @Headers("${ServerAuthInterceptor.HEADER_NO_AUTH}: true")
    suspend fun loginForm(
        @Field("username") username: String,
        @Field("password") password: String,
        @Header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL) targetBaseUrl: String? = null,
    ): Response<TokenResponseDto>

    /**
     * Authenticated user metadata. Pass both [targetBaseUrl] and [authorization]
     * to validate a token for a server that is not yet saved (QR quick-connect).
     */
    @GET("api/user/me")
    suspend fun getCurrentUser(
        @Header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL) targetBaseUrl: String? = null,
        @Header("Authorization") authorization: String? = null,
    ): Response<UserDto>

    /**
     * Raw `/api/books` fallback for Storyteller v2 servers that return a top-level
     * JSON array instead of a paginated envelope.
     */
    @GET("api/books")
    suspend fun listBooksRaw(): Response<ResponseBody>

    /**
     * Raw `/api/v2/books` fallback for Storyteller v2 servers when routes are scoped under `/api/v2/`.
     */
    @GET("api/v2/books")
    suspend fun listBooksV2Raw(): Response<ResponseBody>

    @GET("api/books/{id}")
    suspend fun getBookDetails(
        @Path("id") bookId: String,
        @Header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL) targetBaseUrl: String? = null,
        @Header("Authorization") authorization: String? = null,
    ): Response<BookDetailResponseDto>

    /** Streams a book package (`.epub`, `.m4b`, `.mp3`) from either a relative or absolute URL. */
    @Streaming
    @GET
    suspend fun downloadAsset(
        @Url assetUrl: String,
        @Header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL) targetBaseUrl: String? = null,
        @Header("Authorization") authorization: String? = null,
    ): Response<ResponseBody>

    @POST("api/sync")
    suspend fun pushSyncCheckpoint(
        @Body checkpoint: SyncCheckpointDto,
    ): Response<SyncResponseDto>

    @GET("api/sync/{bookId}")
    suspend fun pullSyncCheckpoint(
        @Path("bookId") bookId: String,
    ): Response<SyncCheckpointDto>

    /**
     * Storyteller v2 Readium positions API.
     */
    @GET("api/v2/books/{bookId}/positions")
    suspend fun getBookPosition(
        @Path("bookId") bookId: String,
    ): Response<ResponseBody>

    @POST("api/v2/books/{bookId}/positions")
    suspend fun postBookPosition(
        @Path("bookId") bookId: String,
        @Body body: StorytellerPositionPayloadDto,
    ): Response<ResponseBody>

    /**
     * Storyteller v2 single book details.
     */
    @GET("api/v2/books/{id}")
    suspend fun getBookV2Raw(
        @Path("id") bookId: String,
        @Header(ServerAuthInterceptor.HEADER_TARGET_BASE_URL) targetBaseUrl: String? = null,
        @Header("Authorization") authorization: String? = null,
    ): Response<ResponseBody>

    /**
     * Storyteller v2 reading statuses.
     */
    @GET("api/v2/statuses")
    suspend fun getStatusesV2Raw(): Response<ResponseBody>

    /**
     * Sets the reading status of a book on the server.
     */
    @PUT("api/v2/books/{id}/status")
    suspend fun updateBookStatus(
        @Path("id") bookId: String,
        @Body body: JsonObject,
    ): Response<ResponseBody>

    /**
     * User rating endpoints.
     */
    @PUT("api/v2/books/{id}/rating")
    suspend fun setBookRating(
        @Path("id") bookId: String,
        @Body body: Map<String, Float>,
    ): Response<ResponseBody>

    @DELETE("api/v2/books/{id}/rating")
    suspend fun deleteBookRating(@Path("id") bookId: String): Response<ResponseBody>

    /**
     * Storyteller v2 Home Sections & Shelves API.
     */
    @GET("api/v2/home/sections")
    suspend fun getHomeSectionsV2Raw(): Response<ResponseBody>

    @GET("api/v2/shelves/{uuid}/books")
    suspend fun getShelfBooksV2Raw(@Path("uuid") uuid: String): Response<ResponseBody>
}
