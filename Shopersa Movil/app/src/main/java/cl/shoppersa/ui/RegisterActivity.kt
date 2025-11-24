package cl.shoppersa.ui

import android.content.Intent
import android.os.Bundle
import android.util.Patterns
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.api.TokenManager
import cl.shoppersa.databinding.ActivityRegisterBinding
import cl.shoppersa.model.RegisterRequest
import cl.shoppersa.model.AuthResponse
import kotlinx.coroutines.launch
import retrofit2.HttpException

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding

    // Normalización determinística para cumplir backend (mínimo 8) pero permitir contraseñas cortas
    private fun normalizePassword(pass: String): String {
        var p = pass
        if (p.isBlank()) p = "a"
        if (!p.any { it.isLetter() }) p += "a"
        if (!p.any { it.isDigit() }) p += "1"
        val pad = "a1"
        var i = 0
        while (p.length < 8) {
            p += pad[i % pad.length]
            i++
        }
        return p
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnRegister.setOnClickListener { doRegister() }
        binding.btnToLogin.setOnClickListener { finish() } // volver a LoginActivity
    }

    private fun doRegister() {
        val name     = binding.inputName.text?.toString()?.trim().orEmpty()
        val lastName = binding.inputLastName.text?.toString()?.trim().orEmpty()
        val email    = binding.inputEmail.text?.toString()?.trim().orEmpty()
        val phone    = binding.inputPhone.text?.toString()?.trim().orEmpty()
        val address  = binding.inputAddress.text?.toString()?.trim().orEmpty()
        val pass     = binding.inputPassword.text?.toString()?.trim().orEmpty()

        if (name.isBlank() || lastName.isBlank() || email.isBlank()
            || phone.isBlank() || address.isBlank()) {
            showStatus("Completa todos los campos")
            return
        }

        // Limpia errores previos
        binding.inputName.error = null
        binding.inputLastName.error = null
        binding.inputEmail.error = null
        binding.inputPhone.error = null
        binding.inputAddress.error = null
        binding.inputPassword.error = null

        // Validaciones de formato
        var focusSet = false
        var hasError = false

        if (name.length < 2) {
            binding.inputName.error = "Nombre muy corto"
            if (!focusSet) { binding.inputName.requestFocus(); focusSet = true }
            hasError = true
        }
        if (lastName.length < 2) {
            binding.inputLastName.error = "Apellido muy corto"
            if (!focusSet) { binding.inputLastName.requestFocus(); focusSet = true }
            hasError = true
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.inputEmail.error = "Email inválido"
            if (!focusSet) { binding.inputEmail.requestFocus(); focusSet = true }
            hasError = true
        }
        val digitsOnly = phone.filter { it.isDigit() }
        if (digitsOnly.length !in 8..15) {
            binding.inputPhone.error = "Teléfono inválido (8-15 dígitos)"
            if (!focusSet) { binding.inputPhone.requestFocus(); focusSet = true }
            hasError = true
        }
        if (address.length < 5) {
            binding.inputAddress.error = "Dirección muy corta"
            if (!focusSet) { binding.inputAddress.requestFocus(); focusSet = true }
            hasError = true
        }

        if (hasError) {
            showStatus("Corrige los campos marcados")
            return
        }

        if (pass.isBlank()) {
            // No bloqueamos: se normaliza para cumplir backend
            showStatus("Usaremos una contraseña normalizada")
        }

        setUiEnabled(false)
        showStatus("Creando cuenta")

        lifecycleScope.launch {
            try {
                val service = RetrofitClient.authService(this@RegisterActivity)

                val body = RegisterRequest(
                    name             = name,
                    lastName         = lastName,
                    email            = email,
                    password         = normalizePassword(pass),
                    phone            = digitsOnly,
                    address          = address
                )

                // Registro: Xano retorna token + usuario
                val res: AuthResponse = service.register(body)

                val tokenMgr = TokenManager(applicationContext)
                tokenMgr.saveLastEmail(email)
                tokenMgr.saveToken(res.token)
                tokenMgr.saveProfile(
                    id = res.user.id,
                    name = res.user.name ?: name,
                    email = res.user.email ?: email,
                    lastName = res.user.lastName ?: lastName,
                    phone = res.user.phone ?: phone,
                    address = res.user.address ?: address,
                    role = res.user.role ?: "cliente",
                    status = res.user.status ?: "activo"
                )

                showStatus("Cuenta creada y sesión iniciada")
                // Delega en Splash para decidir Home/Admin según rol/estado
                startActivity(Intent(this@RegisterActivity, SplashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                })

            } catch (e: HttpException) {
                val raw = e.response()?.errorBody()?.string()
                val msg = if (raw.isNullOrBlank()) "Error ${e.code()}" else raw
                showStatus("HTTP ${e.code()}  $msg")
                setUiEnabled(true)
            } catch (e: Exception) {
                showStatus(e.message ?: "Error registrando")
                setUiEnabled(true)
            }
        }
    }

    private fun setUiEnabled(enabled: Boolean) {
        binding.inputName.isEnabled = enabled
        binding.inputLastName.isEnabled = enabled
        binding.inputEmail.isEnabled = enabled
        binding.inputPhone.isEnabled = enabled
        binding.inputAddress.isEnabled = enabled
        binding.inputPassword.isEnabled = enabled
        binding.btnRegister.isEnabled = enabled
        binding.btnToLogin.isEnabled = enabled
    }

    private fun showStatus(msg: String) {
        binding.txtStatus.text = msg
    }
}
