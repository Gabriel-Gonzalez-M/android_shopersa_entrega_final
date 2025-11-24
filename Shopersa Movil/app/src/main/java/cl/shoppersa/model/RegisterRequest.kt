package cl.shoppersa.model

import com.google.gson.annotations.SerializedName

data class RegisterRequest(
    @SerializedName("name")       val name: String,
    @SerializedName("last_name")  val lastName: String,
    @SerializedName("email")      val email: String,
    @SerializedName("password")   val password: String,
    @SerializedName("phone")      val phone: String,
    @SerializedName("address")    val address: String   // <- CLAVE DEFINITIVA
)
