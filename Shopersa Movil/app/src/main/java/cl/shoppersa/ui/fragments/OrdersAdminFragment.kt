package cl.shoppersa.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import cl.shoppersa.databinding.FragmentAdminOrdersBinding
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.model.Order
import cl.shoppersa.data.CacheStore
import cl.shoppersa.util.Money
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import cl.shoppersa.databinding.ItemOrderAdminBinding

class OrdersAdminFragment : Fragment() {
    private var _binding: FragmentAdminOrdersBinding? = null
    private val binding get() = _binding!!
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private var rateLimitedUntilMs: Long = 0
    private var lastToast429At: Long = 0

    private lateinit var adapter: OrdersAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminOrdersBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Índice de productos para mostrar nombres/precios en detalles
        val productIndex: Map<Long, cl.shoppersa.model.Product> =
            cl.shoppersa.data.CacheStore.loadProducts(requireContext())
                .associateBy { it.id ?: -1L }

        adapter = OrdersAdapter(
            scope = scope,
            productIndex = productIndex,
            onAccept = { id -> updateOrder(id, true) },
            onReject = { id -> updateOrder(id, false) },
            onShip = { id -> shipOrder(id) }
        )

        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.swipe.setOnRefreshListener { fetchOrders() }
        // Mostrar caché persistente primero para evitar petición inicial
        val cached = CacheStore.loadOrders(requireContext())
        if (cached.isNotEmpty()) {
            val items = cached
                .filter { (it.status?.uppercase() ?: "PENDING") in setOf("PENDING", "ACCEPTED") }
                .map { OrderItem(
                    id = it.id ?: -1L,
                    userId = it.user_id ?: -1L,
                    totalFormatted = formatTotal(it.total),
                    status = (it.status?.uppercase() ?: "PENDING")
                ) }
            adapter.submitList(items)
            binding.stateEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
        fetchOrders()
    }

    private fun fetchOrders() {
        binding.stateLoading.visibility = View.VISIBLE
        binding.stateError.visibility = View.GONE
        binding.stateEmpty.visibility = View.GONE
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                if (now < rateLimitedUntilMs) {
                    if (now - lastToast429At > 1500) {
                        android.widget.Toast.makeText(requireContext(), "Demasiadas peticiones, intenta en unos segundos", android.widget.Toast.LENGTH_SHORT).show()
                        lastToast429At = now
                    }
                    return@launch
                }
                val all = RetrofitClient.orderService(requireContext()).list()
                // Guardar caché persistente
                CacheStore.saveOrders(requireContext(), all)
                val items = all
                    .filter { (it.status?.uppercase() ?: "PENDING") in setOf("PENDING", "REQUESTED", "ACCEPTED") }
                    .map { OrderItem(
                        id = it.id ?: -1L,
                        userId = it.user_id ?: -1L,
                        totalFormatted = formatTotal(it.total),
                        status = (it.status?.uppercase() ?: "PENDING")
                    ) }
                adapter.submitList(items)
                binding.stateEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 429) {
                    rateLimitedUntilMs = System.currentTimeMillis() + 10_000
                    if (System.currentTimeMillis() - lastToast429At > 1500) {
                        android.widget.Toast.makeText(requireContext(), "Estás refrescando muy rápido. Reintenta en ~10s.", android.widget.Toast.LENGTH_SHORT).show()
                        lastToast429At = System.currentTimeMillis()
                    }
                } else {
                    binding.stateError.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                binding.stateError.visibility = View.VISIBLE
            } finally {
                binding.stateLoading.visibility = View.GONE
                binding.swipe.isRefreshing = false
            }
        }
    }

    private fun updateOrder(id: Long, accept: Boolean) {
        scope.launch {
            try {
                val svc = RetrofitClient.orderService(requireContext())
                if (accept) {
                    svc.update(id, mapOf("status" to "ACCEPTED"))
                    android.widget.Toast.makeText(requireContext(), "Pedido #$id aceptado", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    svc.update(id, mapOf("status" to "REJECTED"))
                    android.widget.Toast.makeText(requireContext(), "Pedido #$id rechazado", android.widget.Toast.LENGTH_SHORT).show()
                }
                // Invalida caché y refresca una vez
                CacheStore.invalidateOrders(requireContext())
                fetchOrders()
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), e.message ?: "No se pudo actualizar el pedido", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

    }

    private fun shipOrder(id: Long) {
        scope.launch {
            try {
                RetrofitClient.orderService(requireContext()).update(id, mapOf("status" to "SHIPPED"))
                android.widget.Toast.makeText(requireContext(), "Pedido #$id marcado como enviado", android.widget.Toast.LENGTH_SHORT).show()
                CacheStore.invalidateOrders(requireContext())
                fetchOrders()
            } catch (e: Exception) {
                android.widget.Toast.makeText(requireContext(), e.message ?: "No se pudo marcar como enviado", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun formatTotal(total: Double?): String = Money.formatCLP(total)

    override fun onDestroyView() {
        super.onDestroyView()
        job.cancel()
        _binding = null
    }
}

// Adaptador con detalles expandibles
private class OrdersAdapter(
    val scope: kotlinx.coroutines.CoroutineScope,
    val productIndex: Map<Long, cl.shoppersa.model.Product>,
    val onAccept: (Long) -> Unit,
    val onReject: (Long) -> Unit,
    val onShip: (Long) -> Unit
) : androidx.recyclerview.widget.ListAdapter<OrderItem, androidx.recyclerview.widget.RecyclerView.ViewHolder>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<OrderItem>() {
        override fun areItemsTheSame(oldItem: OrderItem, newItem: OrderItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: OrderItem, newItem: OrderItem) = oldItem == newItem
    }
) {
    private val expanded = mutableSetOf<Long>()
    private val detailsCache = mutableMapOf<Long, List<cl.shoppersa.model.OrderProduct>>()
    private val userCache = mutableMapOf<Long, cl.shoppersa.model.User>()
    private val buyerExpanded = mutableSetOf<Long>()

    private suspend fun fetchUserFlexible(ctx: android.content.Context, id: Long): cl.shoppersa.model.User? {
        val svc = cl.shoppersa.api.RetrofitClient.userService(ctx)
        return try {
            // 1) /user/{id}
            svc.getById(id)
        } catch (e1: retrofit2.HttpException) {
            if (e1.code() == 404) {
                try {
                    // 2) /users/{id}
                    svc.getByIdPlural(id)
                } catch (e2: retrofit2.HttpException) {
                    if (e2.code() == 404) {
                        try {
                            // 3) /auth/user/{id}
                            svc.getAuthById(id)
                        } catch (e3: retrofit2.HttpException) {
                            if (e3.code() == 404) {
                                // 4) /auth/users/{id}
                                runCatching { svc.getAuthByIdPlural(id) }.getOrNull()
                            } else null
                        }
                    } else null
                }
            } else null
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
        val b = ItemOrderAdminBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(b.root) {}
    }
    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val b = ItemOrderAdminBinding.bind(holder.itemView)
        b.txtInfo.text = holder.itemView.context.getString(cl.shoppersa.R.string.orders_admin_header_fmt, item.id ?: -1L, item.totalFormatted ?: "")
        val ctx = holder.itemView.context
        val st = item.status?.uppercase().orEmpty()
        b.txtStatus.text = when (st) {
            "REQUESTED" -> ctx.getString(cl.shoppersa.R.string.status_requested)
            "ACCEPTED" -> ctx.getString(cl.shoppersa.R.string.status_accepted)
            "SHIPPED"  -> ctx.getString(cl.shoppersa.R.string.status_shipped)
            "REJECTED" -> ctx.getString(cl.shoppersa.R.string.status_rejected)
            else -> ctx.getString(cl.shoppersa.R.string.status_pending)
        }
        // Color distintivo por estado (alineado con vista usuario)
        val bgColor = when (st) {
            "REQUESTED" -> android.graphics.Color.parseColor("#616161")
            "ACCEPTED" -> android.graphics.Color.parseColor("#1976D2")
            "SHIPPED"  -> android.graphics.Color.parseColor("#388E3C")
            "REJECTED" -> android.graphics.Color.parseColor("#D32F2F")
            else        -> android.graphics.Color.parseColor("#616161")
        }
        b.txtStatus.chipBackgroundColor = ColorStateList.valueOf(bgColor)
        b.txtStatus.setTextColor(android.graphics.Color.WHITE)
        // Chips complementarios como en vista de usuario
        val acceptedText = if (st == "ACCEPTED" || st == "SHIPPED") ctx.getString(cl.shoppersa.R.string.chip_product_accepted) else ctx.getString(cl.shoppersa.R.string.chip_product_pending)
        val acceptedColor = if (st == "ACCEPTED" || st == "SHIPPED") android.graphics.Color.parseColor("#1976D2") else android.graphics.Color.parseColor("#616161")
        b.txtAcceptedState.text = acceptedText
        b.txtAcceptedState.chipBackgroundColor = ColorStateList.valueOf(acceptedColor)
        b.txtAcceptedState.setTextColor(android.graphics.Color.WHITE)

        val shippingText = if (st == "SHIPPED") ctx.getString(cl.shoppersa.R.string.chip_product_shipped) else ctx.getString(cl.shoppersa.R.string.chip_waiting)
        val shippingColor = if (st == "SHIPPED") android.graphics.Color.parseColor("#6A1B9A") else android.graphics.Color.parseColor("#616161")
        b.txtShippingState.text = shippingText
        b.txtShippingState.chipBackgroundColor = ColorStateList.valueOf(shippingColor)
        b.txtShippingState.setTextColor(android.graphics.Color.WHITE)
        b.btnAccept.setOnClickListener { onAccept(item.id) }
        b.btnReject.setOnClickListener { onReject(item.id) }
        b.btnShip.setOnClickListener { onShip(item.id) }

        when (item.status) {
            "PENDING", "REQUESTED" -> {
                b.btnAccept.visibility = View.VISIBLE
                b.btnReject.visibility = View.VISIBLE
                b.btnShip.visibility = View.GONE
            }
            "ACCEPTED" -> {
                b.btnAccept.visibility = View.GONE
                b.btnReject.visibility = View.GONE
                b.btnShip.visibility = View.VISIBLE
            }
            else -> {
                b.btnAccept.visibility = View.GONE
                b.btnReject.visibility = View.GONE
                b.btnShip.visibility = View.GONE
            }
        }

        // Toggle de detalles
        val isExpanded = expanded.contains(item.id)
        b.btnToggleDetails.text = if (isExpanded) ctx.getString(cl.shoppersa.R.string.toggle_hide) else ctx.getString(cl.shoppersa.R.string.toggle_show_details)
        b.containerDetails.visibility = if (isExpanded) View.VISIBLE else View.GONE
        b.btnClientInfo.visibility = if (isExpanded) View.VISIBLE else View.GONE
        b.btnClientInfo.text = if (buyerExpanded.contains(item.id)) "Ocultar información del cliente" else "Información del cliente"
        b.btnToggleDetails.setOnClickListener {
            val nowExpanded = expanded.contains(item.id)
            if (nowExpanded) {
                expanded.remove(item.id)
                b.containerDetails.visibility = View.GONE
                b.btnToggleDetails.text = ctx.getString(cl.shoppersa.R.string.toggle_show_details)
                b.btnClientInfo.visibility = View.GONE
            } else {
                expanded.add(item.id)
                b.containerDetails.visibility = View.VISIBLE
                b.btnToggleDetails.text = ctx.getString(cl.shoppersa.R.string.toggle_hide)
                // Render según estado de info de cliente
                val ctx = holder.itemView.context
                val cached = detailsCache[item.id]
                if (cached != null) {
                    if (buyerExpanded.contains(item.id)) {
                        renderDetailsWithBuyer(ctx, b, item.userId, cached)
                    } else {
                        renderDetails(ctx, b, cached)
                    }
                } else {
                    bindDetails(holder.itemView.context, b, item)
                }
                // Mostrar botón de info de cliente al expandir
                b.btnClientInfo.visibility = View.VISIBLE
                b.btnClientInfo.text = if (buyerExpanded.contains(item.id)) "Ocultar información del cliente" else "Información del cliente"
            }
        }

        // Abrir diálogo de información del cliente
        b.btnClientInfo.setOnClickListener {
            showClientInfoDialog(holder.itemView.context, item.userId)
        }
    }

    private fun bindDetails(ctx: android.content.Context, b: ItemOrderAdminBinding, item: OrderItem) {
        // Si ya tenemos los detalles, renderizar directamente
        val details = detailsCache[item.id]
        if (details != null) {
            if (buyerExpanded.contains(item.id)) {
                renderDetailsWithBuyer(ctx, b, item.userId, details)
            } else {
                renderDetails(ctx, b, details)
            }
            return
        }
        // Estado de carga simple: no eliminar vistas requeridas por el binding
        // Sólo actualizamos el encabezado para indicar carga
        b.containerDetails.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtProductsHeaderAdmin)?.text = "Cargando productos…"
        // Opcionalmente limpiar el adaptador de la lista mientras carga
        b.containerDetails.findViewById<androidx.recyclerview.widget.RecyclerView>(cl.shoppersa.R.id.listDetailsAdmin)?.adapter = null
        // Mostrar primero desde caché persistente si existe
        runCatching { cl.shoppersa.data.CacheStore.loadOrderDetails(ctx, item.id) }
            .onSuccess { cached ->
                if (cached.isNotEmpty()) {
                    detailsCache[item.id] = cached
                    renderDetails(ctx, b, cached)
                }
            }
        scope.launch {
            try {
                val svc = cl.shoppersa.api.RetrofitClient.orderProductService(ctx)
                var list = svc.list(orderId = item.id)
                if (list.isEmpty()) {
                    // Fallback: algunos backends usan 'order' en vez de 'order_id'
                    list = kotlin.runCatching { svc.listByOrder(order = item.id) }.getOrDefault(emptyList())
                }
                // Filtrar por la orden actual y limpiar entradas vacías o duplicadas
                val filtered = list
                    .filter { it.order_id == item.id }
                    .filter { (it.quantity ?: 0) > 0 }
                    .distinctBy { it.id ?: it.product_id }
                detailsCache[item.id] = filtered
                // Persistir detalles en caché
                cl.shoppersa.data.CacheStore.saveOrderDetails(ctx, item.id, filtered)
                renderDetails(ctx, b, filtered)
            } catch (_: Exception) {
                b.containerDetails.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtProductsHeaderAdmin)?.text = "No se pudieron cargar los detalles"
            }
        }
    }

    private fun showClientInfoDialog(ctx: android.content.Context, userId: Long) {
        if (userId <= 0) return
        val inflater = android.view.LayoutInflater.from(ctx)
        val v = inflater.inflate(cl.shoppersa.R.layout.dialog_client_info, null)
        val txtName = v.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtName)
        val txtRole = v.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtRole)
        val txtStatus = v.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtStatus)
        val txtEmail = v.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtEmail)
        val txtPhone = v.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtPhone)
        val txtAddress = v.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtAddress)
        val btnCall = v.findViewById<android.widget.Button>(cl.shoppersa.R.id.btnCall)
        val btnSms = v.findViewById<android.widget.Button>(cl.shoppersa.R.id.btnSms)
        val avatarView = v.findViewById<android.widget.TextView>(cl.shoppersa.R.id.avatarView)

        fun populate(u: cl.shoppersa.model.User) {
            val fullName = listOfNotNull(u.name, u.lastName).joinToString(" ").ifBlank { "Usuario #${u.id ?: "-"}" }
            txtName.text = fullName
            txtRole.text = (u.role ?: "-")
            txtStatus.text = (u.status ?: "-")
            txtEmail.text = u.email ?: "-"
            val phone = u.phone?.trim().orEmpty()
            txtPhone.text = if (phone.isBlank()) "-" else phone
            txtAddress.text = u.address ?: "-"

            val firstChar = fullName.trim().firstOrNull()?.uppercaseChar()
            avatarView.text = firstChar?.toString() ?: "?"

            val safePhone = phone
            btnCall.isEnabled = safePhone.isNotBlank()
            btnSms.isEnabled = safePhone.isNotBlank()
            btnCall.setOnClickListener {
                if (safePhone.isNotBlank()) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$safePhone"))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            }
            btnSms.setOnClickListener {
                if (safePhone.isNotBlank()) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$safePhone"))
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }
            }
        }

        txtName.text = "Cargando…"
        txtRole.text = "Cargando…"
        txtStatus.text = "Cargando…"
        txtEmail.text = "Cargando…"
        txtPhone.text = "Cargando…"
        txtAddress.text = "Cargando…"
        btnCall.isEnabled = false
        btnSms.isEnabled = false
        avatarView.text = "?"

        val dialog = androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setView(v)
            .setPositiveButton("Cerrar", null)
            .create()
        dialog.show()

        val cached = userCache[userId]
        if (cached != null) {
            populate(cached)
        } else {
            scope.launch {
                val u = fetchUserFlexible(ctx, userId)
                if (u != null) {
                    userCache[userId] = u
                    populate(u)
                } else {
                    txtName.text = "No disponible"
                    txtRole.text = "-"
                    txtStatus.text = "-"
                    txtEmail.text = "-"
                    txtPhone.text = "-"
                    txtAddress.text = "-"
                    avatarView.text = "?"
                }
            }
        }
    }

    private fun renderDetailsWithBuyer(
        ctx: android.content.Context,
        b: ItemOrderAdminBinding,
        userId: Long,
        items: List<cl.shoppersa.model.OrderProduct>
    ) {
        // Mantener el diseño del contenedor y sólo poblar la lista de productos
        setDetailsList(ctx, b, items)
    }

    private fun buildBuyerText(u: cl.shoppersa.model.User): String {
        val fullName = listOfNotNull(u.name, u.lastName).joinToString(" ").ifBlank { "Usuario #${u.id ?: "-"}" }
        val email = u.email ?: "-"
        val phone = u.phone ?: "-"
        val address = u.address ?: "-"
        return "Comprador: $fullName • Email: $email • Tel: $phone • Dirección: $address"
    }

    private fun renderDetails(ctx: android.content.Context, b: ItemOrderAdminBinding, items: List<cl.shoppersa.model.OrderProduct>) {
        setDetailsList(ctx, b, items)
    }

    private fun setDetailsList(ctx: android.content.Context, b: ItemOrderAdminBinding, items: List<cl.shoppersa.model.OrderProduct>) {
        val listView = b.containerDetails.findViewById<androidx.recyclerview.widget.RecyclerView>(cl.shoppersa.R.id.listDetailsAdmin)
        val headerView = b.containerDetails.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtProductsHeaderAdmin)
        val totalView = b.containerDetails.findViewById<android.widget.TextView>(cl.shoppersa.R.id.txtOrderTotalAdmin)

        headerView?.text = ctx.getString(cl.shoppersa.R.string.orders_admin_products_header)
        listView?.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx)
        listView?.adapter = AdminDetailsAdapter(scope, items, productIndex)

        var orderTotal = 0.0
        items.forEach { op ->
            val qty = op.quantity ?: 0
            val prod = productIndex[op.product_id ?: -1L]
            val unit = (op.price ?: 0.0).takeIf { it > 0 } ?: run {
                if (prod?.oferta == true) (prod.precioOferta ?: prod.precio ?: 0.0) else (prod?.precio ?: 0.0)
            }
            orderTotal += unit * qty
        }
        totalView?.text = ctx.getString(cl.shoppersa.R.string.orders_admin_total_fmt, Money.formatCLP(orderTotal))
    }

    // Adapter para mostrar tarjetas de producto como en vista de usuario
    private class AdminDetailsAdapter(
        val scope: kotlinx.coroutines.CoroutineScope,
        val items: List<cl.shoppersa.model.OrderProduct>,
        val productIndex: Map<Long, cl.shoppersa.model.Product>
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<AdminDetailsAdapter.VH>() {
        class VH(v: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {
            val txtName: android.widget.TextView = v.findViewById(cl.shoppersa.R.id.txtName)
            val txtQty: android.widget.TextView = v.findViewById(cl.shoppersa.R.id.txtQty)
            val txtUnit: android.widget.TextView = v.findViewById(cl.shoppersa.R.id.txtUnit)
            val txtDesc: android.widget.TextView = v.findViewById(cl.shoppersa.R.id.txtDesc)
            val txtTotal: android.widget.TextView = v.findViewById(cl.shoppersa.R.id.txtTotal)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = android.view.LayoutInflater.from(parent.context).inflate(cl.shoppersa.R.layout.item_order_user_detail, parent, false)
            return VH(v)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val op = items[position]
            val prod = productIndex[op.product_id ?: -1L]
            val name = prod?.nombre ?: "Producto #${op.product_id ?: 0}"
            val desc = prod?.descripcion ?: ""
            val qty = op.quantity ?: 0
            val unit = (op.price ?: 0.0).takeIf { it > 0 } ?: run {
                if (prod?.oferta == true) (prod.precioOferta ?: prod.precio ?: 0.0) else (prod?.precio ?: 0.0)
            }
            val sum = unit * qty

            holder.txtName.text = name
            holder.txtDesc.text = desc
            holder.txtQty.text = "Cantidad: $qty"
            holder.txtUnit.text = "Precio unidad: ${Money.formatCLP(unit)}"
            holder.txtTotal.text = "Precio suma total: ${Money.formatCLP(sum)}"
        }
    }
}

// Modelo mínimo para la lista
private data class OrderItem(
    val id: Long,
    val userId: Long,
    val totalFormatted: String,
    val status: String
)