package cl.shoppersa.api

import cl.shoppersa.model.CartItem
import retrofit2.http.*

interface CartItemService {
    @GET("cart_item")
    suspend fun list(
        @Query("cart_id") cartId: Long? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<CartItem>

    @GET("cart_item/{id}")
    suspend fun getById(@Path("id") id: Long): CartItem

    @POST("cart_item")
    suspend fun create(@Body body: Map<String, @JvmSuppressWildcards Any?>): CartItem

    @PATCH("cart_item/{id}")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): CartItem

    // Fallback para workspaces de Xano que requieren cuerpo completo (PUT)
    @PUT("cart_item/{id}")
    suspend fun updatePut(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): CartItem

    @DELETE("cart_item/{id}")
    suspend fun delete(@Path("id") id: Long)
}