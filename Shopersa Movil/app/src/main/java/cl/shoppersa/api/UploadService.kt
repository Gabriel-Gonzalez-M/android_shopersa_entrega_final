package cl.shoppersa.api

import cl.shoppersa.model.ProductImage
import okhttp3.MultipartBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface UploadService {
    // Xano: clave "content[]" para múltiples archivos
    @Multipart
    @POST("upload/image")
    suspend fun uploadImages(@Part parts: List<MultipartBody.Part>): List<ProductImage>
}
