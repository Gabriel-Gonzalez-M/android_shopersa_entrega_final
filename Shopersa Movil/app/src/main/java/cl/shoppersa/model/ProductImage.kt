package cl.shoppersa.model


import com.google.gson.annotations.SerializedName


data class ProductImage(
    val id: Long? = null,
    val name: String? = null,
    val url: String?,
    val path: String? = null,
    val mime: String? = null,
    val size: Long? = null,
    val type: String? = null,
    val meta: Map<String, Any?>? = null
)