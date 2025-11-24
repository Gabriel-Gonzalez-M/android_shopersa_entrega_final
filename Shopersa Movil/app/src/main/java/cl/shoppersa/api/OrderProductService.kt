package cl.shoppersa.api

import cl.shoppersa.model.OrderProduct
import retrofit2.http.*

interface OrderProductService {
    @GET("order_product")
    suspend fun list(
        @Query("order_id") orderId: Long? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<OrderProduct>

    // Variante alternativa: algunos workspaces usan el filtro 'order'
    @GET("order_product")
    suspend fun listByOrder(
        @Query("order") order: Long? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<OrderProduct>

    @GET("order_product/{id}")
    suspend fun getById(@Path("id") id: Long): OrderProduct

    @POST("order_product")
    suspend fun create(@Body body: Map<String, @JvmSuppressWildcards Any?>): OrderProduct

    @PATCH("order_product/{id}")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): OrderProduct

    @DELETE("order_product/{id}")
    suspend fun delete(@Path("id") id: Long)
}