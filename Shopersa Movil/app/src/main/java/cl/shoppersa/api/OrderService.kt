package cl.shoppersa.api

import cl.shoppersa.model.Order
import retrofit2.http.*

interface OrderService {
    @GET("order")
    suspend fun list(
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<Order>

    @GET("order/{id}")
    suspend fun getById(@Path("id") id: Long): Order

    @POST("order")
    suspend fun create(@Body body: Map<String, @JvmSuppressWildcards Any?>): Order

    @PATCH("order/{id}")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Order

    // Fallback para workspaces que requieren PUT en vez de PATCH
    @PUT("order/{id}")
    suspend fun updatePut(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Order

    @DELETE("order/{id}")
    suspend fun delete(@Path("id") id: Long)
}