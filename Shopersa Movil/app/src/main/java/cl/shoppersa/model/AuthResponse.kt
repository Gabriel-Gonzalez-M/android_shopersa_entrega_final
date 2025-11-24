package cl.shoppersa.model

import com.google.gson.annotations.SerializedName

data class AuthResponse(
    @SerializedName(value = "authToken", alternate = ["token", "access_token"])
    val token: String,
    val user: User
)