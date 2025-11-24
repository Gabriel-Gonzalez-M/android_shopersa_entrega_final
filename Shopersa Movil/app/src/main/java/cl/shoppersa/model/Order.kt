package cl.shoppersa.model

data class Order(
    val id: Long? = null,
    val user_id: Long? = null,
    val total: Double? = null,
    val status: String? = null,
    val created_at: String? = null
)