package cl.shoppersa.model

data class CartItem(
    val id: Long? = null,
    val cart_id: Long? = null,
    val product_id: Long? = null,
    val quantity: Int? = null,
    val unit_price: Double? = null,
    // Campos opcionales presentes en algunos workspaces de Xano
    val name: String? = null,
    val final_price: Double? = null,
    val main_image: ProductImage? = null
)