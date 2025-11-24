package cl.shoppersa.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.api.TokenManager
import cl.shoppersa.databinding.ActivityLoginBinding
import cl.shoppersa.model.LoginRequest
import kotlinx.coroutines.launch
import retrofit2.HttpException

class LoginActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_EMAIL = "extra_email"
    }

    private lateinit var binding: ActivityLoginBinding
    private val tokenManager by lazy { TokenManager(this) }
    private val auth by lazy { RetrofitClient.authService(this) }

    // En login NO normalizamos la contraseña; se envía tal cual el usuario escribe.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (tokenManager.isLoggedIn()) {
            goHome(); return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.inputEmail.setText(TokenManager(this).getLastEmail() ?: "")

        // Prefill email desde el intent del registro o desde el “recordar email”
        val prefill = intent.getStringExtra(EXTRA_EMAIL) ?: tokenManager.getLastEmail()
        if (!prefill.isNullOrBlank()) binding.inputEmail.setText(prefill)

        binding.btnLogin.setOnClickListener { doLogin() }
        // Botón "Crear cuenta" si existe en tu layout
        binding.tvRegistrarse.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
        // Permitir Enter para iniciar sesión desde el campo de contraseña
        binding.inputPassword.setOnEditorActionListener { _: android.widget.TextView?, actionId: Int, event: android.view.KeyEvent? ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                (event != null && event.keyCode == android.view.KeyEvent.KEYCODE_ENTER && event.action == android.view.KeyEvent.ACTION_DOWN)
            ) {
                doLogin()
                true
            } else {
                false
            }
        }
    }

    private fun doLogin() {
        val email = binding.inputEmail.text?.toString()?.trim().orEmpty()
        val pass  = binding.inputPassword.text?.toString()?.trim().orEmpty()
        if (email.isBlank() || pass.isBlank()) {
            brandToast("Completa email y contraseña")
            return
        }

        setUiEnabled(false)

        lifecycleScope.launch {
            try {
                val res = auth.login(LoginRequest(email, pass))
                tokenManager.saveToken(res.token)
                tokenManager.saveLastEmail(email)

                val me = auth.me()
                tokenManager.saveProfile(
                    id       = me.id,
                    name     = me.name,
                    email    = me.email,
                    lastName = me.lastName,
                    phone    = me.phone,
                    address  = me.address,
                    role     = me.role,
                    status   = me.status
                )
                // Chequear estado bloqueado y evitar navegación (solo 'activo'/'bloqueado')
                val statusNorm = me.status?.trim()?.lowercase()
                val isBlocked = statusNorm == "bloqueado"
                if (isBlocked) {
                    tokenManager.clear()
                    tokenManager.saveLastEmail(email)
                    brandToast("Tu cuenta está bloqueada. Contacta con un administrador.", long = true)
                    setUiEnabled(true)
                } else {
                    brandToast("¡Bienvenido, ${me.name}!")
                    goHome()
                }
            } catch (e: HttpException) {
                val raw = e.response()?.errorBody()?.string()
                val msg = if (raw.isNullOrBlank()) {
                    when (e.code()) {
                        401 -> "Credenciales inválidas"
                        403 -> "Usuario bloqueado"
                        else -> "Error ${'$'}{e.code()}"
                    }
                } else raw
                brandToast(msg)
                setUiEnabled(true)
            } catch (e: Exception) {
                brandToast(e.message ?: "Error de red")
                setUiEnabled(true)
            }
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        // Usar setters explícitos para evitar el error "Variable expected"
        binding.inputEmail.setEnabled(enabled)
        binding.inputPassword.setEnabled(enabled)
        binding.btnLogin.setEnabled(enabled)
        binding.tvRegistrarse.setEnabled(enabled)
        // Mostrar un loader opcional
        binding.progressBar.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun goHome() {
        val role = tokenManager.getProfileRole()
        val intent = if (isAdminRole(role)) {
            Intent(this, AdminActivity::class.java)
        } else {
            Intent(this, HomeActivity::class.java)
        }
        startActivity(intent)
        finish()
    }
}
    private fun isAdminRole(role: String?): Boolean {
        val normalized = role?.trim()?.lowercase()
        return normalized in setOf("admin", "administrator", "administrador")
    }
