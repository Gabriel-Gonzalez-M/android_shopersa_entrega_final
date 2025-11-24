package cl.shoppersa.api

import cl.shoppersa.model.Cart
import retrofit2.http.*

interface CartService {
    @GET("cart")
    suspend fun list(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<Cart>

    @GET("cart/{id}")
    suspend fun getById(@Path("id") id: Long): Cart

    @POST("cart")
    suspend fun create(@Body body: Map<String, @JvmSuppressWildcards Any?> = emptyMap()): Cart

    @PATCH("cart/{id}")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Cart

    @DELETE("cart/{id}")
    suspend fun delete(@Path("id") id: Long)
}