package cl.shoppersa.api

import cl.shoppersa.model.*
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthService {
    @POST("https://x8ki-letl-twmt.n7.xano.io/api:rP0BKx0s/auth/signup")
    suspend fun register(@Body body: RegisterRequest): AuthResponse

    @POST("https://x8ki-letl-twmt.n7.xano.io/api:rP0BKx0s/auth/login")
    suspend fun login(@Body body: LoginRequest): AuthResponse

    @GET("https://x8ki-letl-twmt.n7.xano.io/api:rP0BKx0s/auth/me")
    suspend fun me(): User
}
