package cl.shoppersa.ui.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.core.widget.addTextChangedListener
import cl.shoppersa.databinding.FragmentAdminUsersBinding
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.model.User
import cl.shoppersa.data.CacheStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class UsersAdminFragment : Fragment() {
    private var _binding: FragmentAdminUsersBinding? = null
    private val binding get() = _binding!!
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val loadingIds = mutableSetOf<Long>()
    private var allUsers: List<User> = emptyList()
    private var searchJob: Job? = null

    private val adapter = UsersAdapter(
        onBlockToggle = { user -> toggleBlock(user) },
        onEdit = { user -> showEditDialog(user) },
        isLoading = { user -> user.id?.let { loadingIds.contains(it) } == true }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminUsersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        // FAB para crear usuario
        binding.fabCreate.setOnClickListener { showEditDialog(null) }
        binding.inputSearch.addTextChangedListener { text ->
            val q = text?.toString()?.trim().orEmpty()
            searchJob?.cancel()
            searchJob = scope.launch {
                kotlinx.coroutines.delay(300)
                applyLocalFilter(q)
            }
        }
        binding.swipe.setOnRefreshListener {
            val q = binding.inputSearch.text?.toString()?.trim()
            fetchUsers(q.takeUnless { it.isNullOrBlank() })
        }
        // Mostrar caché persistente para minimizar peticiones iniciales
        val cached = CacheStore.loadUsers(requireContext())
        if (cached.isNotEmpty()) {
            allUsers = cached
            applyLocalFilter("")
        }
        fetchUsers()
    }

    private fun fetchUsers(q: String? = null) {
        binding.stateLoading.visibility = View.VISIBLE
        binding.stateError.visibility = View.GONE
        binding.stateEmpty.visibility = View.GONE
        scope.launch {
            try {
                // Nuevo esquema: sólo usamos el endpoint /user del workspace
                val service = RetrofitClient.userService(requireContext())
                val items = service.list(q = q)
                allUsers = items
                CacheStore.saveUsers(requireContext(), items)
                applyLocalFilter(q.orEmpty())
            } catch (e: retrofit2.HttpException) {
                binding.stateError.visibility = View.VISIBLE
                val raw = e.response()?.errorBody()?.string()
                val msg = if (raw.isNullOrBlank()) "HTTP ${e.code()} al listar usuarios" else raw
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.stateError.visibility = View.VISIBLE
                Toast.makeText(requireContext(), e.message ?: "Error al listar usuarios", Toast.LENGTH_SHORT).show()
            } finally {
                binding.stateLoading.visibility = View.GONE
                binding.swipe.isRefreshing = false
            }
        }
    }

    private fun applyLocalFilter(q: String) {
        val list = if (q.isBlank()) {
            allUsers
        } else {
            val qq = q.lowercase()
            allUsers.filter { u ->
                (u.name ?: "").lowercase().contains(qq) || (u.email ?: "").lowercase().contains(qq)
            }
        }
        adapter.submitList(list)
        binding.stateEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        binding.stateError.visibility = View.GONE
    }

    private fun toggleBlock(user: User) {
        val ctx = requireContext()
        val isActive = user.status?.lowercase() == "activo"
        val actionText = if (isActive) "bloquear" else "desbloquear"
        AlertDialog.Builder(ctx)
            .setTitle("Confirmar")
            .setMessage("¿Deseas $actionText a ${user.name ?: "este usuario"}?")
            .setPositiveButton("Sí") { _, _ ->
                scope.launch {
                    try {
                        // Resolver id de forma segura; evitar NPE si el usuario no tiene id
                        val resolvedId: Long? = user.id ?: run {
                            val email = user.email
                            if (email.isNullOrBlank()) null else findUserIdByEmailFlexible(ctx, email)
                        }
                        if (user.id == null && resolvedId != null) {
                            android.util.Log.w("UsersAdmin", "toggleBlock: id resuelto por email=${user.email} -> id=$resolvedId")
                        }
                        if (resolvedId == null) {
                            Toast.makeText(ctx, "Usuario sin id. No es posible actualizar estado.", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        setRowLoading(resolvedId, true)
                        // Alinear formato con backend (Title Case)
                        val newStatus = if (isActive) "Bloqueado" else "Activo"
                        cl.shoppersa.api.UserUpdateHelper.updateFlexible(ctx, resolvedId, mapOf("status" to newStatus))
                        Toast.makeText(ctx, "Estado actualizado", Toast.LENGTH_SHORT).show()

                        // Actualiza UI localmente (stale-while-revalidate)
                        val pos = adapter.currentList.indexOfFirst { it.id == resolvedId }
                        if (pos >= 0) {
                            val updated = adapter.currentList.toMutableList()
                            updated[pos] = updated[pos].copy(status = newStatus.lowercase())
                            adapter.submitList(updated)
                        }

                        CacheStore.invalidateUsers(ctx)
                        // Refresca en background con pequeño delay (evita ráfagas a Xano)
                        val q = binding.inputSearch.text?.toString()?.trim()
                        scope.launch {
                            kotlinx.coroutines.delay(1500)
                            fetchUsers(q.takeUnless { it.isNullOrBlank() })
                        }
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "No se pudo actualizar estado", Toast.LENGTH_SHORT).show()
                    } finally {
                        val resolvedId: Long? = user.id ?: run {
                            val email = user.email
                            if (email.isNullOrBlank()) null else findUserIdByEmailFlexible(ctx, email)
                        }
                        if (resolvedId != null) setRowLoading(resolvedId, false)
                    }
                }
            }
            .setNegativeButton("No", null)
            .show()
    }

    private fun setRowLoading(id: Long, loading: Boolean) {
        if (loading) loadingIds.add(id) else loadingIds.remove(id)
        val pos = adapter.currentList.indexOfFirst { it.id == id }
        if (pos >= 0) adapter.notifyItemChanged(pos)
    }

    private fun showEditDialog(user: User?) {
        val ctx = requireContext()
        val container = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val inputName = android.widget.EditText(ctx).apply { hint = "Nombre" }
        val inputLastName = android.widget.EditText(ctx).apply { hint = "Apellidos" }
        val inputEmail = android.widget.EditText(ctx).apply { hint = "Email" }
        val inputPhone = android.widget.EditText(ctx).apply { hint = "Teléfono" }
        val inputAddress = android.widget.EditText(ctx).apply { hint = "Dirección" }
        val inputPassword = android.widget.EditText(ctx).apply {
            hint = "Contraseña"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val roles = listOf("cliente", "administrador")
        val roleSpinner = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, roles)
        }
        val statuses = listOf("activo", "bloqueado")
        val statusSpinner = android.widget.Spinner(ctx).apply {
            adapter = android.widget.ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, statuses)
        }
        container.addView(inputName)
        container.addView(inputLastName)
        container.addView(inputEmail)
        container.addView(inputPhone)
        container.addView(inputAddress)
        if (user == null) container.addView(inputPassword)
        container.addView(roleSpinner)
        container.addView(statusSpinner)

        // Prefill si viene usuario
        user?.let {
            inputName.setText(it.name ?: "")
            inputLastName.setText(it.lastName ?: "")
            inputEmail.setText(it.email ?: "")
            inputPhone.setText(it.phone ?: "")
            inputAddress.setText(it.address ?: "")
            val idx = roles.indexOf(it.role?.lowercase() ?: "cliente")
            if (idx >= 0) roleSpinner.setSelection(idx)
            val idxSt = statuses.indexOf(it.status?.lowercase() ?: "activo")
            if (idxSt >= 0) statusSpinner.setSelection(idxSt)
        }

        val builder = AlertDialog.Builder(ctx)
            .setTitle(if (user == null) "Crear Usuario" else "Editar Usuario")
            .setView(container)
            .setPositiveButton("Guardar") { _, _ ->
                val name = inputName.text?.toString()?.trim().orEmpty()
                val lastName = inputLastName.text?.toString()?.trim().orEmpty()
                val email = inputEmail.text?.toString()?.trim().orEmpty()
                val phone = inputPhone.text?.toString()?.trim().orEmpty()
                val address = inputAddress.text?.toString()?.trim().orEmpty()
                val password = inputPassword.text?.toString()?.trim().orEmpty()
                val role = roleSpinner.selectedItem?.toString()?.lowercase() ?: "cliente"
                val status = statusSpinner.selectedItem?.toString()?.lowercase() ?: "activo"

                if (name.isBlank() || email.isBlank()) {
                    Toast.makeText(ctx, "Nombre y email son obligatorios", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (user == null) {
                    // En creación, pedir datos básicos; la contraseña se normaliza automáticamente si está vacía o es corta
                    if (lastName.isBlank() || phone.isBlank() || address.isBlank()) {
                        Toast.makeText(ctx, "Completa apellidos, teléfono y dirección", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                }

                scope.launch {
                    try {
                        if (user == null) {
                            // Crear usuario directamente en /user
                            val body = mapOf(
                                "name" to name,
                                "last_name" to lastName,
                                "email" to email,
                                "password" to normalizePassword(password),
                                "phone" to phone,
                                "address" to address,
                                "role" to role,
                                "status" to status
                            )
                            val created = RetrofitClient.userService(ctx).create(body)
                            val createdId = created.id ?: findUserIdByEmailFlexible(ctx, email)
                            if (createdId != null) {
                                android.util.Log.i("UsersAdmin", "Usuario creado id=$createdId")
                            }
                        } else {
                            // Edición: actualizar todos los campos disponibles (excepto password)
                            val targetId: Long? = user.id ?: findUserIdByEmailFlexible(ctx, user.email ?: email)
                            if (user.id == null && targetId != null) {
                                android.util.Log.w("UsersAdmin", "edit: id resuelto por email=${user.email ?: email} -> id=$targetId")
                            }
                            if (targetId == null) {
                                Toast.makeText(ctx, "Usuario sin id. No se puede editar.", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            cl.shoppersa.api.UserUpdateHelper.updateFlexible(ctx, targetId, mapOf(
                                "name" to name,
                                "last_name" to lastName,
                                "email" to email,
                                "phone" to phone,
                                "address" to address,
                                "role" to role,
                                "status" to status
                            ))
                        }
                        // Invalida caché para forzar recarga controlada
                        CacheStore.invalidateUsers(ctx)
                        val q = binding.inputSearch.text?.toString()?.trim()
                        fetchUsers(q.takeUnless { it.isNullOrBlank() })
                        Toast.makeText(ctx, if (user == null) "Usuario creado" else "Guardado correctamente", Toast.LENGTH_SHORT).show()
                    } catch (e: retrofit2.HttpException) {
                        val raw = e.response()?.errorBody()?.string()
                        val msg = if (raw.isNullOrBlank()) "HTTP ${e.code()}" else raw
                        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, e.message ?: "No se pudo guardar", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancelar", null)

        if (user != null) {
            builder.setNeutralButton("Eliminar") { _, _ ->
                scope.launch {
                    try {
                        val delId: Long? = user.id ?: findUserIdByEmailFlexible(ctx, user.email ?: "")
                        if (user.id == null && delId != null) {
                            android.util.Log.w("UsersAdmin", "delete: id resuelto por email=${user.email} -> id=$delId")
                        }
                        if (delId == null) {
                            Toast.makeText(ctx, "Usuario sin id. No se puede eliminar.", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        RetrofitClient.userService(ctx).delete(delId)
                        CacheStore.invalidateUsers(ctx)
                        fetchUsers()
                    } catch (e: Exception) {
                        Toast.makeText(ctx, "No se pudo eliminar", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        builder.show()
    }

    // === Helpers flexibles para Xano ===
    private fun normalizePassword(pass: String): String {
        var p = pass
        if (p.isBlank()) p = "a"
        if (!p.any { it.isLetter() }) p += "a"
        if (!p.any { it.isDigit() }) p += "1"
        // Relleno determinístico para cumplir mínimo 8 sin aleatoriedad
        val pad = "a1"
        var i = 0
        while (p.length < 8) {
            p += pad[i % pad.length]
            i++
        }
        return p
    }
    private suspend fun findUserIdByEmailFlexible(ctx: android.content.Context, email: String): Long? {
        val service = RetrofitClient.userService(ctx)
        return try {
            val byQ = service.list(q = email)
            val matchByQ = byQ.firstOrNull { it.email.equals(email, ignoreCase = true) }
            matchByQ?.id ?: run {
                val all = service.list()
                all.firstOrNull { it.email.equals(email, ignoreCase = true) }?.id
            }
        } catch (e: Exception) {
            null
        }
    }

    // Eliminado: usar cl.shoppersa.api.UserUpdateHelper.updateFlexible

    override fun onDestroyView() {
        super.onDestroyView()
        job.cancel()
        _binding = null
    }
}

// Adaptador mínimo para la lista de usuarios (placeholder)
private class UsersAdapter(
    val onBlockToggle: (User) -> Unit,
    val onEdit: (User) -> Unit,
    val isLoading: (User) -> Boolean
) : androidx.recyclerview.widget.ListAdapter<User, UsersAdapter.VH>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<User>() {
        override fun areItemsTheSame(oldItem: User, newItem: User) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: User, newItem: User) = oldItem == newItem
    }
) {
    init {
        setHasStableIds(true)
    }
    class VH(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
        val avatarView: android.widget.TextView = itemView.findViewById(cl.shoppersa.R.id.avatarView)
        val txtName: android.widget.TextView = itemView.findViewById(cl.shoppersa.R.id.txtName)
        val txtMeta: android.widget.TextView = itemView.findViewById(cl.shoppersa.R.id.txtMeta)
        val btnEdit: android.widget.ImageButton = itemView.findViewById(cl.shoppersa.R.id.btnEdit)
        val btnToggle: android.widget.Button = itemView.findViewById(cl.shoppersa.R.id.btnToggle)
        val chipRole: com.google.android.material.chip.Chip = itemView.findViewById(cl.shoppersa.R.id.chipRole)
        val chipStatus: com.google.android.material.chip.Chip = itemView.findViewById(cl.shoppersa.R.id.chipStatus)
        val progress: android.widget.ProgressBar = itemView.findViewById(cl.shoppersa.R.id.progressRow)
        val txtPhone: android.widget.TextView = itemView.findViewById(cl.shoppersa.R.id.txtPhone)
        val txtAddress: android.widget.TextView = itemView.findViewById(cl.shoppersa.R.id.txtAddress)
        val btnCall: android.widget.Button = itemView.findViewById(cl.shoppersa.R.id.btnCall)
        val btnSms: android.widget.Button = itemView.findViewById(cl.shoppersa.R.id.btnSms)
    }

    override fun getItemId(position: Int): Long = getItem(position).id ?: position.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(cl.shoppersa.R.layout.item_user, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = getItem(position)
        holder.txtName.text = item.name ?: "(sin nombre)"
        val initial = (item.name?.trim()?.firstOrNull() ?: '?').toString().uppercase()
        holder.avatarView.text = initial
        val roleStr = item.role ?: ""
        val statusStr = item.status ?: ""
        val emailStr = item.email ?: ""
        holder.txtMeta.text = emailStr

        // Teléfono y dirección
        holder.txtPhone.text = if (item.phone.isNullOrBlank()) "Tel.: " else "Tel.: ${item.phone}"
        holder.txtAddress.text = item.address?.takeIf { it.isNotBlank() } ?: ""

        // Chip de rol
        holder.chipRole.text = if (roleStr.isBlank()) "(sin rol)" else roleStr
        holder.chipRole.chipBackgroundColor = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, cl.shoppersa.R.color.brand_primary)
        )
        holder.chipRole.setTextColor(
            androidx.core.content.ContextCompat.getColor(holder.itemView.context, cl.shoppersa.R.color.brand_on_primary)
        )

        // Chip de estado (ACTIVO / BLOQUEADO)
        val ctx = holder.itemView.context
        val statusNorm = statusStr.trim().lowercase()
        holder.chipStatus.text = if (statusNorm.isBlank()) "activo" else statusNorm
        val statusBg = androidx.core.content.ContextCompat.getColor(
            ctx,
            when (statusNorm) {
                "activo"     -> cl.shoppersa.R.color.brand_success
                "bloqueado"  -> cl.shoppersa.R.color.brand_error
                else         -> cl.shoppersa.R.color.brand_primary
            }
        )
        holder.chipStatus.chipBackgroundColor = android.content.res.ColorStateList.valueOf(statusBg)
        holder.chipStatus.setTextColor(
            androidx.core.content.ContextCompat.getColor(ctx, cl.shoppersa.R.color.brand_on_primary)
        )

        // Botones y acciones
        val isActive = statusNorm == "activo"
        holder.btnEdit.setOnClickListener { onEdit(item) }
        holder.btnToggle.text = if (isActive) "Bloquear" else "Desbloquear"
        run {
            val bgColor = androidx.core.content.ContextCompat.getColor(
                ctx,
                if (isActive) cl.shoppersa.R.color.brand_error else cl.shoppersa.R.color.brand_success
            )
            val textColor = androidx.core.content.ContextCompat.getColor(ctx, cl.shoppersa.R.color.brand_on_primary)
            androidx.core.view.ViewCompat.setBackgroundTintList(
                holder.btnToggle,
                android.content.res.ColorStateList.valueOf(bgColor)
            )
            holder.btnToggle.setTextColor(textColor)
        }
        val loading = isLoading(item)
        holder.progress.visibility = if (loading) View.VISIBLE else View.GONE
        holder.btnToggle.visibility = if (loading) View.INVISIBLE else View.VISIBLE
        holder.btnToggle.isEnabled = !loading
        holder.btnEdit.isEnabled = !loading
        holder.btnToggle.setOnClickListener { if (!loading) onBlockToggle(item) }

        // Acciones de llamada y SMS (deshabilitar si no hay teléfono)
        val canContact = !item.phone.isNullOrBlank()
        holder.btnCall.isEnabled = canContact && !loading
        holder.btnSms.isEnabled = canContact && !loading
        holder.btnCall.setOnClickListener {
            if (canContact && !loading) {
                val intent = android.content.Intent(android.content.Intent.ACTION_DIAL).apply {
                    data = android.net.Uri.parse("tel:" + item.phone)
                }
                holder.itemView.context.startActivity(intent)
            }
        }
        holder.btnSms.setOnClickListener {
            if (canContact && !loading) {
                val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO).apply {
                    data = android.net.Uri.parse("smsto:" + item.phone)
                }
                holder.itemView.context.startActivity(intent)
            }
        }
    }
}