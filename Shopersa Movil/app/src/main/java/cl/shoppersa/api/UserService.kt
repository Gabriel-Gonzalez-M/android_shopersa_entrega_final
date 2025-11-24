package cl.shoppersa.api

import cl.shoppersa.model.User
import retrofit2.http.*

interface UserService {
    @GET("user")
    suspend fun list(
        @Query("q") q: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<User>

    // Variante alternativa en algunos workspaces de Xano
    @GET("users")
    suspend fun listPlural(
        @Query("q") q: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<User>

    @GET("user/{id}")
    suspend fun getById(@Path("id") id: Long): User

    // Algunos workspaces exponen /users/{id}
    @GET("users/{id}")
    suspend fun getByIdPlural(@Path("id") id: Long): User

    @POST("user")
    suspend fun create(@Body body: Map<String, @JvmSuppressWildcards Any?>): User

    // Actualizar usuario: algunos workspaces usan /user/{id} y otros /users/{id}, y
    // el método puede ser PUT o PATCH. Exponemos variantes para permitir fallback.
    @PUT("user/{id}")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): User

    @PATCH("user/{id}")
    suspend fun updatePatch(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): User

    @PUT("users/{id}")
    suspend fun updatePlural(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): User

    @PATCH("users/{id}")
    suspend fun updatePluralPatch(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): User

    @DELETE("user/{id}")
    suspend fun delete(@Path("id") id: Long)

    // Bloquear/desbloquear (toggle)
    @POST("user/{id}/toggle_block")
    suspend fun toggleBlock(@Path("id") id: Long)

    // === Variantes bajo el prefijo /auth (comunes en Xano) ===
    @GET("auth/user")
    suspend fun listAuth(
        @Query("q") q: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<User>

    @GET("auth/users")
    suspend fun listAuthPlural(
        @Query("q") q: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<User>

    @GET("auth/user/{id}")
    suspend fun getAuthById(@Path("id") id: Long): User

    @GET("auth/users/{id}")
    suspend fun getAuthByIdPlural(@Path("id") id: Long): User
}