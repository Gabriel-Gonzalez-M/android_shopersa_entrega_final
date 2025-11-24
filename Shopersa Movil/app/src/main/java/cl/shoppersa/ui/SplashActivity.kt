package cl.shoppersa.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import cl.shoppersa.R
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.api.TokenManager
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {
    private fun isAdminRole(role: String?): Boolean {
        val normalized = role?.trim()?.lowercase()
        return normalized in setOf("admin", "administrator", "administrador")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val token = TokenManager(this)
        lifecycleScope.launch {
            if (token.isLoggedIn()) {
                var blocked = false
                try {
                    val me = RetrofitClient.authService(this@SplashActivity).me()
                    token.saveProfile(
                        id = me.id,
                        name = me.name,
                        email = me.email,
                        lastName = me.lastName,
                        phone = me.phone,
                        address = me.address,
                        role = me.role,
                        status = me.status
                    )
                    val statusNorm = me.status?.trim()?.lowercase()
                    blocked = statusNorm == "bloqueado"
                    if (blocked) {
                        token.clear()
                        Toast.makeText(this@SplashActivity, "Tu cuenta está bloqueada. Contacta con un administrador.", Toast.LENGTH_LONG).show()
                    }
                } catch (_: Exception) { }

                if (blocked) {
                    startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
                } else {
                    val role = token.getProfileRole()
                    val dest = if (isAdminRole(role)) {
                        Intent(this@SplashActivity, AdminActivity::class.java)
                    } else {
                        Intent(this@SplashActivity, HomeActivity::class.java)
                    }
                    startActivity(dest)
                }
            } else {
                startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
            }
            finish()
        }
    }
}
