package cl.shoppersa.model

import com.google.gson.annotations.SerializedName

data class CreateProductRequest(
    @SerializedName("name")         val nombre: String,
    @SerializedName("description")  val descripcion: String,
    @SerializedName("price")        val precio: Double,
    @SerializedName("offer")        val oferta: Boolean,
    @SerializedName("offer_price")  val precioOferta: Double? = null,
    @SerializedName("category")     val categoria: String,
    @SerializedName("novelty")      val novedad: Boolean,
    // opcionales (no usados en Xano actual, pero los puedes conservar)
    val codigo: String? = null,
    val tipoJuguete: String? = null,
    val tamano: String? = null,
    val tipoRopa: String? = null,
    val talla: String? = null,
    val genero: String? = null,
    val material: String? = null,
    val dimensiones: String? = null,
    val subcategoria: String? = null
)
