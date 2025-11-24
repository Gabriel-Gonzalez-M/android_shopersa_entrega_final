package cl.shoppersa.model

import com.google.gson.annotations.SerializedName

data class Product(
    @SerializedName("id")          val id: Long? = null,
    @SerializedName("name")        val nombre: String? = null,
    @SerializedName("description") val descripcion: String? = null,
    @SerializedName("price")       val precio: Double = 0.0,
    @SerializedName("offer")       val oferta: Boolean = false,       // <- NO NULL
    @SerializedName("offer_price") val precioOferta: Double? = null,
    @SerializedName("category")    val categoria: String? = null,
    @SerializedName("novelty")     val novedad: Boolean = false,      // <- NO NULL
    @SerializedName("brand")       val brand: String? = null,
    @SerializedName("status")      val status: String? = null,
    @SerializedName("slug")        val slug: String? = null,
    @SerializedName("images")      val imagenes: List<ProductImage>? = null,
    @SerializedName("created_at")  val createdAt: Long? = null,
    @SerializedName("updated_at")  val updatedAt: Long? = null
)
