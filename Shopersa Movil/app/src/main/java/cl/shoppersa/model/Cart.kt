package cl.shoppersa.model

data class Cart(
    val id: Long? = null,
    val user_id: Long? = null,
    // Campos comunes en Xano según tu captura
    val created_at: Long? = null,
    val total: Long? = null,
    val reservation_expires_at: Long? = null,
)