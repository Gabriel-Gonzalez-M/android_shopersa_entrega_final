package cl.shoppersa.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cl.shoppersa.R
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.api.TokenManager
import cl.shoppersa.databinding.FragmentUserOrdersBinding
import cl.shoppersa.model.Order
import cl.shoppersa.model.OrderProduct
import kotlinx.coroutines.*
import cl.shoppersa.util.Money
import cl.shoppersa.data.CacheStore
// Removed local CLP formatter imports; using common util Money

class OrdersUserFragment : Fragment() {
    private var _binding: FragmentUserOrdersBinding? = null
    private val binding get() = _binding!!

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private var meId: Long? = null
    private var pollJob: Job? = null
    private val lastStatuses = mutableMapOf<Long, String?>()

    // Rate limiting y deduplicación para list()
    private var isFetchingList: Boolean = false
    private var lastListFetchAt: Long = 0L
    private val MIN_LIST_INTERVAL_MS: Long = 20_000L

    // Using common util Money for CLP formatting

    private val productNameCache = mutableMapOf<Long, String>()

    private val adapter = UserOrdersAdapter(
        scope = scope,
        resolveProductName = { pid -> resolveProductName(pid) },
        formatTotal = { v -> Money.formatCLP(v) }
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentUserOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.swipe.setOnRefreshListener { fetchAndDisplay() }
        fetchAndDisplay()
    }

    private fun fetchAndDisplay() {
        binding.stateError.visibility = View.GONE
        binding.stateEmpty.visibility = View.GONE
        binding.stateLoading.visibility = View.VISIBLE
        binding.swipe.isRefreshing = false

        // Rate limit: evitar llamadas demasiado seguidas
        val since = System.currentTimeMillis() - lastListFetchAt
        if (isFetchingList || since < MIN_LIST_INTERVAL_MS) {
            binding.stateLoading.visibility = View.GONE
            binding.swipe.isRefreshing = false
            return
        }
        isFetchingList = true

        scope.launch {
            try {
                if (meId == null) {
                    meId = TokenManager(requireContext()).getProfileId()
                    if (meId == null) {
                        val me = RetrofitClient.authService(requireContext()).me()
                        meId = me.id
                    }
                }
                val all = RetrofitClient.orderService(requireContext()).list()
                lastListFetchAt = System.currentTimeMillis()

                val mine = all.filter { it.user_id == meId }
                adapter.submitList(mine)
                binding.stateEmpty.visibility = if (mine.isEmpty()) View.VISIBLE else View.GONE
                startPolling(mine.mapNotNull { it.id })
            } catch (e: Exception) {
                binding.stateError.visibility = View.VISIBLE
            } finally {
                binding.stateLoading.visibility = View.GONE
                binding.swipe.isRefreshing = false
                isFetchingList = false
            }
        }
    }

    private fun startPolling(orderIds: List<Long>) {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                try {
                    // Coordinar con rate limit: esperar si fue muy reciente
                    val now = System.currentTimeMillis()
                    val since = now - lastListFetchAt
                    if (since < MIN_LIST_INTERVAL_MS) {
                        delay(MIN_LIST_INTERVAL_MS - since)
                    }

                    isFetchingList = true
                    val all = RetrofitClient.orderService(requireContext()).list()
                    lastListFetchAt = System.currentTimeMillis()
                    val mine = all.filter { it.user_id == meId }

                    // Detectar cambios de estado y notificar
                    mine.forEach { ord ->
                        val prev = lastStatuses[ord.id ?: -1L]
                        val curr = ord.status?.uppercase()
                        if (prev != null && curr != prev) {
                            when (curr) {
                                "ACCEPTED" -> {
                                    showNotification("Orden aceptada", "Tu pedido #${ord.id} fue aceptado", "orders_channel")
                                    android.widget.Toast.makeText(requireContext(), "Tu pedido #${ord.id} fue aceptado", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "SHIPPED"  -> {
                                    showNotification("Pedido enviado", "Tu pedido #${ord.id} está en camino", "orders_channel")
                                    android.widget.Toast.makeText(requireContext(), "Tu pedido #${ord.id} fue enviado", android.widget.Toast.LENGTH_SHORT).show()
                                }
                                "REJECTED" -> {
                                    showNotification("Orden rechazada", "Tu pedido #${ord.id} fue rechazado", "orders_channel")
                                    android.widget.Toast.makeText(requireContext(), "Tu pedido #${ord.id} fue rechazado", android.widget.Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        lastStatuses[ord.id ?: -1L] = curr
                    }
                    adapter.submitList(mine)
                    binding.stateEmpty.visibility = if (mine.isEmpty()) View.VISIBLE else View.GONE
                } catch (e: retrofit2.HttpException) {
                    // Backoff más largo si Xano devuelve 429
                    if (e.code() == 429) {
                        val jitter = (0..5000).random().toLong()
                        delay(30_000L + jitter)
                    }
                } catch (_: Exception) {
                    // silencio
                } finally {
                    isFetchingList = false
                }
                // Polling no agresivo
                delay(20_000)
            }
        }
    }

    private suspend fun resolveProductName(pid: Long): String? {
        productNameCache[pid]?.let { return it }
        // Primero intentar desde caché persistente
        CacheStore.getProduct(requireContext(), pid)?.nombre?.let {
            productNameCache[pid] = it
            return it
        }
        // Si no existe, pedir al backend y guardar
        return runCatching { RetrofitClient.productService(requireContext()).getById(pid) }
            .getOrNull()
            ?.also { CacheStore.saveProduct(requireContext(), it) }
            ?.nombre
            ?.also { productNameCache[pid] = it }
    }

    private fun showNotification(title: String, text: String, channelId: String) {
        val mgr = requireContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(channelId, "Órdenes", android.app.NotificationManager.IMPORTANCE_DEFAULT)
            mgr.createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(requireContext(), channelId)
            .setSmallIcon(R.drawable.placeholder_rect)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        // Android 13+: verificar permiso POST_NOTIFICATIONS
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2002)
                return
            }
        }
        try {
            NotificationManagerCompat.from(requireContext()).notify(2001, notif)
        } catch (_: SecurityException) {
            // Silenciar si el usuario deniega; evitamos crash
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2002 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            // No reenviamos la notificación automáticamente para evitar duplicados.
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        pollJob?.cancel()
        job.cancel()
        _binding = null
    }
}

private class UserOrdersAdapter(
    private val scope: CoroutineScope,
    private val resolveProductName: suspend (Long) -> String?,
    private val formatTotal: (Double?) -> String
) : androidx.recyclerview.widget.ListAdapter<Order, UserOrdersAdapter.VH>(
    object : DiffUtil.ItemCallback<Order>() {
        override fun areItemsTheSame(oldItem: Order, newItem: Order) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Order, newItem: Order) = oldItem == newItem
    }
) {
    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtId: TextView = itemView.findViewById(R.id.txtOrderId)
        val txtTotal: TextView = itemView.findViewById(R.id.txtTotal)
        val chipStatus: com.google.android.material.chip.Chip = itemView.findViewById(R.id.chipStatus)
        val txtDate: TextView = itemView.findViewById(R.id.txtDate)
        val txtCount: TextView = itemView.findViewById(R.id.txtProductCount)
        val txtAddress: TextView = itemView.findViewById(R.id.txtAddress)
        val txtAcceptedState: com.google.android.material.chip.Chip = itemView.findViewById(R.id.txtAcceptedState)
        val txtShippingState: com.google.android.material.chip.Chip = itemView.findViewById(R.id.txtShippingState)
        val btnRequestShipment: com.google.android.material.button.MaterialButton = itemView.findViewById(R.id.btnRequestShipment)
        val btnToggle: TextView = itemView.findViewById(R.id.btnToggleDetails)
        val container: ViewGroup = itemView.findViewById(R.id.containerDetails)
        val list: RecyclerView = itemView.findViewById(R.id.listDetails)
    }

    private val expanded = mutableSetOf<Long>()
    private val cache = mutableMapOf<Long, List<OrderProduct>>()

    // TTL y deduplicación para detalles de orden
    private val inflightDetails = mutableSetOf<Long>()
    private val detailsLastFetched = mutableMapOf<Long, Long>()
    private val DETAILS_TTL_MS: Long = 60_000L

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_order_user, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.itemView.context
        val order = getItem(position)
        val id = order.id ?: -1L
        holder.txtId.text = ctx.getString(R.string.order_id_fmt, id)
        holder.txtTotal.text = formatTotal(order.total)
        run {
            val raw = order.created_at
            val pretty = if (!raw.isNullOrBlank()) formatPrettyDate(raw) else null
            holder.txtDate.text = ctx.getString(R.string.date_prefix, pretty ?: "—")
        }
        holder.txtAddress.text = ctx.getString(R.string.address_prefix, cl.shoppersa.api.TokenManager(ctx).getProfileAddress() ?: "—")
        holder.txtCount.text = ctx.getString(R.string.products_prefix, "—")
        val st = order.status?.uppercase().orEmpty()
        holder.chipStatus.text = when (st) {
            "REQUESTED" -> ctx.getString(R.string.status_requested)
            "ACCEPTED" -> ctx.getString(R.string.status_accepted)
            "SHIPPED"  -> ctx.getString(R.string.status_shipped)
            "REJECTED" -> ctx.getString(R.string.status_rejected)
            else -> ctx.getString(R.string.status_pending)
        }
        val color = when (st) {
            "REQUESTED" -> android.graphics.Color.parseColor("#616161")
            "ACCEPTED" -> android.graphics.Color.parseColor("#1976D2")
            "SHIPPED"  -> android.graphics.Color.parseColor("#388E3C")
            "REJECTED" -> android.graphics.Color.parseColor("#D32F2F")
            else        -> android.graphics.Color.parseColor("#616161")
        }
        holder.chipStatus.chipBackgroundColor = ColorStateList.valueOf(color)
        holder.chipStatus.setTextColor(android.graphics.Color.WHITE)

        // Chips complementarios: aceptación y envío
        // Aceptación
        val acceptedText = if (st == "ACCEPTED" || st == "SHIPPED") ctx.getString(R.string.chip_product_accepted) else ctx.getString(R.string.chip_product_pending)
        val acceptedColor = if (st == "ACCEPTED" || st == "SHIPPED") android.graphics.Color.parseColor("#1976D2") else android.graphics.Color.parseColor("#616161")
        holder.txtAcceptedState.text = acceptedText
        holder.txtAcceptedState.chipBackgroundColor = ColorStateList.valueOf(acceptedColor)
        holder.txtAcceptedState.setTextColor(android.graphics.Color.WHITE)

        // Envío
        val shippingText = if (st == "SHIPPED") ctx.getString(R.string.chip_product_shipped) else ctx.getString(R.string.chip_waiting)
        // Solicitan que NO sea verde para enviado; usamos púrpura
        val shippingColor = if (st == "SHIPPED") android.graphics.Color.parseColor("#6A1B9A") else android.graphics.Color.parseColor("#616161")
        holder.txtShippingState.text = shippingText
        holder.txtShippingState.chipBackgroundColor = ColorStateList.valueOf(shippingColor)
        holder.txtShippingState.setTextColor(android.graphics.Color.WHITE)

        // Mostrar botón "Solicitar envío" aquí (Mis compras), no en el carrito
        holder.btnRequestShipment.apply {
            visibility = when (st) {
                "REJECTED", "SHIPPED", "REQUESTED" -> View.GONE
                else -> View.VISIBLE
            }
            isEnabled = true
            setOnClickListener {
                isEnabled = false
                requestShipmentForOrder(ctx, id) {
                    isEnabled = true
                }
            }
        }

        val isExpanded = expanded.contains(id)
        holder.container.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.btnToggle.text = if (isExpanded) ctx.getString(R.string.toggle_hide) else ctx.getString(R.string.toggle_show_details)

        holder.btnToggle.setOnClickListener {
            val newVal = !expanded.contains(id)
            if (newVal) expanded.add(id) else expanded.remove(id)
            notifyItemChanged(position)
            if (newVal && cache[id] == null) loadDetails(holder, id)
        }

        if (isExpanded) ensureDetailsAdapter(holder, id)
    }

    private fun requestShipmentForOrder(ctx: android.content.Context, orderId: Long, onFinally: () -> Unit) {
        scope.launch {
            try {
                val tm = cl.shoppersa.api.TokenManager(ctx)
                val profileId = tm.getProfileId() ?: kotlin.runCatching { cl.shoppersa.api.RetrofitClient.authService(ctx).me().id }.getOrNull()
                val phone = tm.getProfilePhone()?.trim()
                val address = tm.getProfileAddress()?.trim()
                if (profileId == null) {
                    android.widget.Toast.makeText(ctx, "Sesión inválida. Inicia sesión nuevamente.", android.widget.Toast.LENGTH_SHORT).show()
                    return@launch
                }
                if (phone.isNullOrBlank() || address.isNullOrBlank()) {
                    // Dialogo para completar datos
                    val inputPhone = android.widget.EditText(ctx).apply {
                        hint = "Teléfono"
                        setText(phone ?: "")
                        inputType = android.text.InputType.TYPE_CLASS_PHONE
                        setPadding(16, 24, 16, 24)
                    }
                    val inputAddress = android.widget.EditText(ctx).apply {
                        hint = "Dirección"
                        setText(address ?: "")
                        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
                        setPadding(16, 24, 16, 24)
                    }
                    val container = android.widget.LinearLayout(ctx).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        setPadding(24, 12, 24, 0)
                        addView(inputPhone)
                        addView(inputAddress)
                    }
                    android.app.AlertDialog.Builder(ctx)
                        .setTitle("Datos de envío")
                        .setMessage("Completa tu teléfono y dirección")
                        .setView(container)
                        .setNegativeButton("Cancelar") { dlg, _ ->
                            dlg.dismiss(); onFinally()
                        }
                        .setPositiveButton("Guardar y solicitar") { dlg, _ ->
                            val newPhone = inputPhone.text?.toString()?.trim().orEmpty()
                            val newAddress = inputAddress.text?.toString()?.trim().orEmpty()
                            if (newPhone.isBlank() || newAddress.isBlank()) {
                                android.widget.Toast.makeText(ctx, "Teléfono y dirección son obligatorios", android.widget.Toast.LENGTH_SHORT).show()
                                onFinally(); return@setPositiveButton
                            }
                            scope.launch {
                                try {
                                    cl.shoppersa.api.UserUpdateHelper.updateFlexible(ctx, profileId, mapOf("phone" to newPhone, "address" to newAddress))
                                    // Reflejar en caché local
                                    tm.saveProfile(
                                        id = profileId,
                                        name = tm.getProfileName(),
                                        email = tm.getProfileEmail(),
                                        lastName = tm.getProfileLastName(),
                                        phone = newPhone,
                                        address = newAddress,
                                        role = tm.getProfileRole(),
                                        status = tm.getProfileStatus()
                                    )
                                    cl.shoppersa.api.RetrofitClient.orderService(ctx).update(orderId, mapOf("shipment_requested" to true))
                                    android.widget.Toast.makeText(ctx, "Solicitud de envío enviada", android.widget.Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(ctx, e.message ?: "No se pudo solicitar envío", android.widget.Toast.LENGTH_SHORT).show()
                                } finally { onFinally() }
                            }
                            dlg.dismiss()
                        }
                        .setCancelable(false)
                        .show()
                    return@launch
                } else {
                    kotlin.runCatching { cl.shoppersa.api.UserUpdateHelper.updateFlexible(ctx, profileId, mapOf("phone" to phone, "address" to address)) }
                }
                requestShipmentFlexible(ctx, orderId)
            } catch (e: Exception) {
                android.widget.Toast.makeText(ctx, e.message ?: "No se pudo solicitar envío", android.widget.Toast.LENGTH_SHORT).show()
            } finally { onFinally() }
        }
    }

    // Copia de util flexible desde el carrito: soporta /user vs /users y PUT vs PATCH
    // Eliminado: se usa UserUpdateHelper.updateFlexible

    private fun loadDetails(holder: VH, orderId: Long) {
        // Evitar duplicar carga y respetar TTL
        val now = System.currentTimeMillis()
        val last = detailsLastFetched[orderId] ?: 0L
        if (inflightDetails.contains(orderId)) {
            ensureDetailsAdapter(holder, orderId)
            return
        }
        if (cache[orderId] != null && now - last < DETAILS_TTL_MS) {
            ensureDetailsAdapter(holder, orderId)
            return
        }

        inflightDetails.add(orderId)
        scope.launch {
            try {
                val svc = RetrofitClient.orderProductService(holder.itemView.context)
                // Mostrar primero detalles en caché si existen
                val cached = CacheStore.loadOrderDetails(holder.itemView.context, orderId)
                if (cached.isNotEmpty()) {
                    cache[orderId] = cached
                        .filter { it.order_id == orderId }
                        .filter { (it.quantity ?: 0) > 0 }
                        .distinctBy { it.id ?: it.product_id }
                    ensureDetailsAdapter(holder, orderId)
                }
                var items = svc.list(orderId = orderId)
                if (items.isEmpty()) {
                    items = kotlin.runCatching { svc.listByOrder(order = orderId) }.getOrDefault(emptyList())
                }
                val filtered = items
                    .filter { it.order_id == orderId }
                    .filter { (it.quantity ?: 0) > 0 }
                    .distinctBy { it.id ?: it.product_id }
                cache[orderId] = filtered
                CacheStore.saveOrderDetails(holder.itemView.context, orderId, filtered)
                detailsLastFetched[orderId] = System.currentTimeMillis()
                ensureDetailsAdapter(holder, orderId)
            } catch (_: Exception) {
                // ignorar errores aquí para no bloquear la UI
            } finally {
                inflightDetails.remove(orderId)
            }
        }
    }

    // Enviar solicitud de envío con fallbacks (PATCH y PUT, variantes de campo)
    private suspend fun requestShipmentFlexible(ctx: android.content.Context, orderId: Long) {
        val svc = RetrofitClient.orderService(ctx)
        // 1) Intentar marcar la orden como SOLICITADA usando el campo 'status'
        try {
            svc.update(orderId, mapOf("status" to "REQUESTED"))
            android.widget.Toast.makeText(ctx, "Solicitud de envío enviada", android.widget.Toast.LENGTH_SHORT).show()
            return
        } catch (e: retrofit2.HttpException) {
            if (e.code() == 429) {
                android.widget.Toast.makeText(ctx, "Demasiadas peticiones, intenta en unos segundos", android.widget.Toast.LENGTH_SHORT).show()
                throw e
            }
        } catch (_: Exception) { /* Fallback abajo */ }

        // 2) Fallback: PUT con el cuerpo completo de la orden y status=REQUESTED
        try {
            val order = svc.getById(orderId)
            val body = mapOf(
                "status" to "REQUESTED",
                "total" to (order.total ?: 0.0),
                "user_id" to (order.user_id ?: 0L)
            )
            svc.updatePut(orderId, body)
            android.widget.Toast.makeText(ctx, "Solicitud de envío enviada", android.widget.Toast.LENGTH_SHORT).show()
        } catch (e: retrofit2.HttpException) {
            val msg = when (e.code()) {
                429 -> "Demasiadas peticiones, intenta en unos segundos"
                500 -> "Error del servidor al solicitar envío"
                else -> "No se pudo solicitar envío (HTTP ${e.code()})"
            }
            android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
            throw e
        }
    }

    private fun ensureDetailsAdapter(holder: VH, orderId: Long) {
        val ctx = holder.itemView.context
        holder.list?.layoutManager = LinearLayoutManager(ctx)
        val data = cache[orderId].orEmpty()
        holder.list?.adapter = DetailsAdapter(scope, data) { pid -> resolveProductName(pid) }
        // Actualizar cantidad total de productos (suma de cantidades) en el encabezado
        val totalQty = data.sumOf { it.quantity ?: 0 }
        holder.txtCount?.text = "Productos: $totalQty"
    }

    private fun formatPrettyDate(raw: String): String {
        // API 26+: usar java.time
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                val odt = java.time.OffsetDateTime.parse(raw)
                val zoned = odt.atZoneSameInstant(java.time.ZoneId.systemDefault())
                return zoned.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            } catch (_: Exception) {
                try {
                    val inst = java.time.Instant.parse(raw)
                    val zoned = java.time.ZonedDateTime.ofInstant(inst, java.time.ZoneId.systemDefault())
                    return zoned.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                } catch (_: Exception) { /* continuar abajo */ }
            }
        }
        // API <26: usar SimpleDateFormat cuando sea posible
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX", // ISO con offset
            "yyyy-MM-dd'T'HH:mm:ss'Z'", // UTC Z
            "yyyy-MM-dd" // sólo fecha
        )
        for (p in patterns) {
            try {
                val sdf = java.text.SimpleDateFormat(p, java.util.Locale.getDefault())
                sdf.timeZone = java.util.TimeZone.getDefault()
                val date = sdf.parse(raw)
                if (date != null) {
                    val out = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
                    return out.format(date)
                }
            } catch (_: Exception) { }
        }
        // Fallback simple: primeros 10 caracteres YYYY-MM-DD
        return raw.take(10).let { s ->
            if (s.count { it == '-' } == 2) {
                val parts = s.split('-')
                if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else s
            } else s
        }
    }
}

private class DetailsAdapter(
    private val scope: CoroutineScope,
    private val items: List<OrderProduct>,
    private val resolveProductName: suspend (Long) -> String?
) : RecyclerView.Adapter<DetailsAdapter.VH>() {
    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val txtName: TextView = itemView.findViewById(R.id.txtName)
        val txtQty: TextView = itemView.findViewById(R.id.txtQty)
        val txtUnit: TextView = itemView.findViewById(R.id.txtUnit)
        val txtDesc: TextView = itemView.findViewById(R.id.txtDesc)
        val txtTotal: TextView = itemView.findViewById(R.id.txtTotal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_order_user_detail, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]
        val pid = it.product_id ?: -1L
        val qty = it.quantity ?: 0
        val unit = it.price ?: 0.0
        holder.txtQty.text = "Cantidad: $qty"
        holder.txtUnit.text = "Precio unidad: ${Money.formatCLP(unit)}"
        holder.txtTotal.text = "Precio suma total: ${Money.formatCLP(unit * qty)}"
        holder.txtName.text = "Producto #$pid"
        holder.txtDesc.text = ""
        scope.launch {
            val name = resolveProductName(pid)
            if (!name.isNullOrBlank()) holder.txtName.text = name
            // Intentar desde caché primero
            val cachedP = CacheStore.getProduct(holder.itemView.context, pid)
            if (cachedP != null) {
                holder.txtDesc.text = cachedP.descripcion ?: ""
                val realUnit = (it.price ?: cachedP.precio)
                holder.txtUnit.text = "Precio unidad: ${Money.formatCLP(realUnit)}"
                holder.txtTotal.text = "Precio suma total: ${Money.formatCLP(realUnit * qty)}"
                return@launch
            }
            // Si no está en caché, pedir y guardar
            runCatching { RetrofitClient.productService(holder.itemView.context).getById(pid) }
                .onSuccess { p ->
                    CacheStore.saveProduct(holder.itemView.context, p)
                    holder.txtDesc.text = p.descripcion ?: ""
                    val realUnit = (it.price ?: p.precio)
                    holder.txtUnit.text = "Precio unidad: ${Money.formatCLP(realUnit)}"
                    holder.txtTotal.text = "Precio suma total: ${Money.formatCLP(realUnit * qty)}"
                }
                .onFailure { /* ignorar */ }
        }
    }
}