package cl.shoppersa.api

import cl.shoppersa.model.Product
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*

interface ProductService {

    @GET("product")
    suspend fun list(
        @Query("q") q: String? = null,
        @Query("limit") limit: Int? = null,
        @Query("offset") offset: Int? = null
    ): List<Product>

    @GET("product/{id}")
    suspend fun getById(@Path("id") id: Long): Product

    // Crear producto + imágenes (Xano: file_resource[] con clave image_files[$idx])
    @Multipart
    @POST("product")
    suspend fun createWithImages(
        @Part("name")        name: RequestBody,
        @Part("description") description: RequestBody,
        @Part("price")       price: RequestBody,
        @Part("category")    category: RequestBody,
        @Part("offer")       offer: RequestBody,
        @Part("novelty")     novelty: RequestBody,
        @Part("offer_price") offerPrice: RequestBody? = null,
        @Part images: List<MultipartBody.Part>? = null
    ): Product

    // Actualizar producto (sin imágenes)
    @PATCH("product/{id}")
    suspend fun update(
        @Path("id") id: Long,
        @Body body: Map<String, @JvmSuppressWildcards Any?>
    ): Product

    // (Deprecated) Actualizar producto + imágenes por multipart.
    // Preferir: subir imágenes por UploadService y luego enviar JSON vía PUT.
    @Multipart
    @PATCH("product/{id}")
    suspend fun updateWithImages(
        @Path("id") id: Long,
        @Part("name")        name: RequestBody,
        @Part("description") description: RequestBody,
        @Part("price")       price: RequestBody,
        @Part("category")    category: RequestBody,
        @Part("offer")       offer: RequestBody,
        @Part("novelty")     novelty: RequestBody,
        @Part("offer_price") offerPrice: RequestBody? = null,
        @Part images: List<MultipartBody.Part>? = null
    ): Product

    // Borrar producto
    @DELETE("product/{id}")
    suspend fun delete(@Path("id") id: Long)
}

