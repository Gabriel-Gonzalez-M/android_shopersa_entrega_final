package cl.shoppersa.api

import android.content.Context
import android.content.SharedPreferences

class TokenManager(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)

    fun saveToken(token: String) { prefs.edit().putString("token", token).apply() }
    fun getToken(): String? = prefs.getString("token", null)
    fun isLoggedIn(): Boolean = !getToken().isNullOrBlank()
    fun clear() { prefs.edit().clear().apply() }

    // ---- Perfil extendido ----
    fun saveProfile(
        id: Long? = null,
        name: String?,
        email: String?,
        lastName: String? = null,
        phone: String? = null,
        address: String? = null,
        role: String? = null,
        status: String? = null
    ) {
        prefs.edit()
            .putLong("profile_id", id ?: -1L)
            .putString("profile_name", name)
            .putString("profile_email", email)
            .putString("profile_last_name", lastName)
            .putString("profile_phone", phone)
            .putString("profile_address", address) // <- address (nuevo)
            .putString("profile_role", role)
            .putString("profile_status", status)
            .apply()
    }

    fun getProfileId(): Long? = prefs.getLong("profile_id", -1L).let { if (it > 0L) it else null }
    fun getProfileName(): String? = prefs.getString("profile_name", null)
    fun getProfileEmail(): String? = prefs.getString("profile_email", null)
    fun getProfileLastName(): String? = prefs.getString("profile_last_name", null)
    fun getProfilePhone(): String? = prefs.getString("profile_phone", null)
    fun getProfileAddress(): String? =
        prefs.getString("profile_address",
            prefs.getString("profile_shipping_address", null)) // compatibilidad
    fun getProfileRole(): String? = prefs.getString("profile_role", null)
    fun getProfileStatus(): String? = prefs.getString("profile_status", null)

    // Recordar email (prefill en Login)
    fun saveLastEmail(email: String) { prefs.edit().putString("last_email", email).apply() }
    fun getLastEmail(): String? = prefs.getString("last_email", null)
}
