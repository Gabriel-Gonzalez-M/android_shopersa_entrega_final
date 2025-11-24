package cl.shoppersa.model

import com.google.gson.annotations.SerializedName
data class CreateProductResponse(
    val id: Long,
    val nombre: String,
    val categoria: String
)