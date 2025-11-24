package cl.shoppersa.model

import com.google.gson.annotations.SerializedName

data class User(
    @SerializedName("id")       val id: Long? = null,
    @SerializedName("name")     val name: String? = null,
    @SerializedName("last_name")val lastName: String? = null,
    @SerializedName("email")    val email: String? = null,
    @SerializedName("phone")    val phone: String? = null,
    @SerializedName("address")  val address: String? = null,   // <- AQUÍ
    @SerializedName("role")     val role: String? = null,
    @SerializedName("status")   val status: String? = null
)
