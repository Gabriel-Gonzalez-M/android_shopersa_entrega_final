package cl.shoppersa.ui.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.api.TokenManager
import cl.shoppersa.databinding.FragmentProfileBinding
import cl.shoppersa.ui.LoginActivity
import kotlinx.coroutines.launch

class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!

    private val token by lazy { TokenManager(requireContext()) }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Prefill desde caché (por si /me demora)
        val cachedFullName = listOfNotNull(
            token.getProfileName(),
            token.getProfileLastName()
        ).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "—" }

        binding.txtName.text     = prettyName(cachedFullName)
        binding.txtEmail.text    = token.getProfileEmail() ?: token.getLastEmail() ?: "—"
        binding.txtPhone.text    = token.getProfilePhone() ?: "—"
        binding.txtAddress.text  = token.getProfileAddress() ?: "—"
        binding.avatarView.text  = initial(binding.txtName.text.toString(), binding.txtEmail.text.toString())

        // Chips ocultos de inicio
        binding.chipRole.isVisible = false
        binding.chipStatus.isVisible = false

        loadMe()

        binding.btnRefresh.setOnClickListener { loadMe() }
        binding.btnLogout.setOnClickListener {
            token.clear()
            startActivity(Intent(requireContext(), LoginActivity::class.java))
            requireActivity().finish()
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnRefresh.isEnabled = !loading
        binding.txtStatus.text = if (loading) "Cargando perfil…" else ""
    }

    private fun loadMe() {
        setLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val me = RetrofitClient.authService(requireContext()).me()

                val fullName = listOfNotNull(me.name?.trim(), me.lastName?.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
                    .ifBlank { "—" }

                binding.txtName.text     = prettyName(fullName)
                binding.txtEmail.text    = me.email ?: "—"
                binding.txtPhone.text    = me.phone?.takeIf { it.isNotBlank() } ?: "—"
                binding.txtAddress.text  = me.address?.takeIf { it.isNotBlank() } ?: "—"
                binding.avatarView.text  = initial(fullName, me.email)

                val role   = me.role?.trim().orEmpty()
                val status = me.status?.trim().orEmpty()
                binding.chipRole.text     = role
                binding.chipStatus.text   = status
                binding.chipRole.isVisible   = role.isNotEmpty()
                binding.chipStatus.isVisible = status.isNotEmpty()

                // Guardar caché local para próximas aperturas
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
                binding.txtStatus.text = ""
            } catch (e: Exception) {
                binding.txtStatus.text = e.message ?: "No fue posible cargar el perfil"
            } finally {
                setLoading(false)
            }
        }
    }

    private fun initial(name: String?, email: String?): String {
        val src = when {
            !name.isNullOrBlank() -> name.trim()
            !email.isNullOrBlank() -> email.trim()
            else -> "?"
        }
        val ch = src.firstOrNull { it.isLetter() } ?: '?'
        return ch.uppercase()
    }

    private fun prettyName(name: String?): String =
        name?.trim()
            ?.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            ?: "—"

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
