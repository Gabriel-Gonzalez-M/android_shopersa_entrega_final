package cl.shoppersa.model

import com.google.gson.annotations.SerializedName

data class OrderProduct(
    @SerializedName("id")
    val id: Long? = null,

    // Algunos backends usan 'order' en vez de 'order_id'
    @SerializedName(value = "order_id", alternate = ["order"])
    val order_id: Long? = null,

    // Algunos backends usan 'product' en vez de 'product_id'
    @SerializedName(value = "product_id", alternate = ["product"])
    val product_id: Long? = null,

    // Cantidad puede venir como 'qty' o 'quantity_ordered'
    @SerializedName(value = "quantity", alternate = ["qty", "quantity_ordered"])
    val quantity: Int? = null,

    // Precio unitario registrado (puede venir como 'unit_price')
    @SerializedName(value = "price", alternate = ["unit_price"])
    val price: Double? = null,

    // Campos opcionales presentes en algunos workspaces de Xano
    @SerializedName("name")
    val name: String? = null,

    @SerializedName("final_price")
    val final_price: Double? = null,

    @SerializedName("main_image")
    val main_image: ProductImage? = null
)