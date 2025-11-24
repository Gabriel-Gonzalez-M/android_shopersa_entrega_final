package cl.shoppersa.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import cl.shoppersa.R
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.data.CartManager
import cl.shoppersa.data.CartCache
import cl.shoppersa.databinding.FragmentCartBinding
import cl.shoppersa.model.CartItem
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import retrofit2.HttpException
import cl.shoppersa.util.Money
import cl.shoppersa.api.TokenManager

private data class CartProductPreview(val nombre: String, val imageUrl: String?, val precio: Double)

class CartFragment : Fragment() {
    private var _binding: FragmentCartBinding? = null
    private val binding get() = _binding!!
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private var refreshJob: Job? = null
    private var rateLimitedUntilMs: Long = 0
    private var lastToast429At: Long = 0
    private var isFetching: Boolean = false
    private var lastFetchMs: Long = 0

    private val productCache = mutableMapOf<Long, CartProductPreview>()

    // Cola de operaciones para serializar peticiones de borrado y evitar ráfagas
    private val opQueue: java.util.ArrayDeque<suspend () -> Unit> = java.util.ArrayDeque()
    private var opRunner: Job? = null

    private fun enqueue(op: suspend () -> Unit) {
        opQueue.addLast(op)
        val running = opRunner?.isActive ?: false
        if (!running) {
            opRunner = scope.launch {
                while (opQueue.isNotEmpty()) {
                    val task = opQueue.removeFirst()
                    try { task.invoke() } catch (_: Exception) {}
                    // Pequeña pausa para evitar bursts hacia Xano
                    kotlinx.coroutines.delay(150)
                }
            }
        }
    }

    private val adapter = CartAdapter(
        scope,
        resolveProduct = { pid -> resolveProductPreview(pid) },
        onChanged = { scheduleRefresh() },
        onInstantRecompute = { recomputeTotalFromAdapter() },
        onDeleteQueued = { id -> requestDelete(id) }
    )

    // CLP formateado con util común Money

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCartBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.swipe.setOnRefreshListener { fetchItems() }
        binding.btnCheckout.setOnClickListener { checkout() }
        // El envío se solicita desde "Mis compras"; ocultamos el botón en el carrito
        kotlin.runCatching { binding.btnRequestShipment.visibility = View.GONE }
        fetchItems()
        // Revisar si hay orden pendiente para notificar cuando se valide
        watchPendingOrderIfAny()
    }

    private fun fetchItems() {
        binding.stateLoading.visibility = View.VISIBLE
        binding.stateError.visibility = View.GONE
        binding.stateEmpty.visibility = View.GONE
        scope.launch {
            try {
                // Evitar concurrencia y llamadas demasiado seguidas al cambiar de pestañas
                if (isFetching) return@launch
                val nowStart = System.currentTimeMillis()
                if (nowStart - lastFetchMs < 1200) {
                    // Usar caché si existe y evitar golpear el backend de inmediato
                    val cartIdCached = CartManager(requireContext()).getCartId()
                    val cachedItems = cartIdCached?.let { CartCache(requireContext()).load(it) }
                    if (cachedItems != null) {
                        adapter.submitList(cachedItems)
                        recomputeTotalFromAdapter()
                        binding.stateLoading.visibility = View.GONE
                        binding.stateEmpty.visibility = if (cachedItems.isEmpty()) View.VISIBLE else View.GONE
                    }
                    return@launch
                }
                isFetching = true
                val now = System.currentTimeMillis()
                if (now < rateLimitedUntilMs) {
                    // Mostrar datos desde caché para evitar que el usuario quede bloqueado
                    val cartIdCached = CartManager(requireContext()).getCartId()
                    val cachedItems = cartIdCached?.let { CartCache(requireContext()).load(it) }
                    if (cachedItems != null) {
                        adapter.submitList(cachedItems)
                        recomputeTotalFromAdapter()
                        binding.stateLoading.visibility = View.GONE
                        binding.stateEmpty.visibility = if (cachedItems.isEmpty()) View.VISIBLE else View.GONE
                    }
                    if (now - lastToast429At > 3000) {
                        Toast.makeText(requireContext(), "Demasiadas peticiones, intenta en unos segundos", Toast.LENGTH_SHORT).show()
                        lastToast429At = now
                    }
                    return@launch
                }
                val cartId = CartManager(requireContext()).getCartId()
                if (cartId == null) {
                    adapter.submitList(emptyList())
                    binding.stateEmpty.visibility = View.VISIBLE
                    binding.txtTotal.text = "Total: ${Money.formatCLP(0.0)}"
                } else {
                    val items = RetrofitClient.cartItemService(requireContext()).list(cartId = cartId)
                    adapter.submitList(items)
                    // Persistir en caché para lecturas rápidas y resiliencia ante 429
                    kotlin.runCatching { CartCache(requireContext()).save(cartId, items) }
                    // Prefetch de precios faltantes para evitar total = 0 cuando no hay precio almacenado
                    val missingPids = items.mapNotNull { ci ->
                        val pid = ci.product_id
                        val hasPrice = (ci.final_price != null) || (ci.unit_price != null)
                        if (!hasPrice && pid != null && !productCache.containsKey(pid)) pid else null
                    }.distinct()
                    for (pid in missingPids) {
                        try {
                            resolveProductPreview(pid)
                            kotlinx.coroutines.delay(250)
                        } catch (_: Exception) { /* ignorar */ }
                    }
                    // Calcular total usando final_price o unit_price; si faltaran, usar cache de producto
                    val total = items.sumOf { ci ->
                        val qty = ci.quantity ?: 1
                        val price = (ci.final_price ?: ci.unit_price) ?: run {
                            val pid = ci.product_id
                            if (pid != null) productCache[pid]?.precio ?: 0.0 else 0.0
                        }
                        price * qty
                    }
                    binding.txtTotal.text = "Total: ${Money.formatCLP(total)}"
                    binding.stateEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 429) {
                    val retryHeader = e.response()?.headers()?.get("Retry-After")
                    val retrySec = retryHeader?.toLongOrNull()
                    val backoffMs = ((retrySec ?: 10) * 1000L)
                    rateLimitedUntilMs = System.currentTimeMillis() + backoffMs
                    if (System.currentTimeMillis() - lastToast429At > 1500) {
                        Toast.makeText(requireContext(), "Estás refrescando muy rápido. Reintenta en ~${retrySec ?: 10}s.", Toast.LENGTH_SHORT).show()
                        lastToast429At = System.currentTimeMillis()
                    }
                    // Mostrar caché si existe
                    val cartIdCached = CartManager(requireContext()).getCartId()
                    val cachedItems = cartIdCached?.let { CartCache(requireContext()).load(it) }
                    if (cachedItems != null) {
                        adapter.submitList(cachedItems)
                        recomputeTotalFromAdapter()
                        binding.stateEmpty.visibility = if (cachedItems.isEmpty()) View.VISIBLE else View.GONE
                    }
                } else {
                    binding.stateError.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                binding.stateError.visibility = View.VISIBLE
            } finally {
                lastFetchMs = System.currentTimeMillis()
                isFetching = false
                binding.stateLoading.visibility = View.GONE
                binding.swipe.isRefreshing = false
            }
        }
    }

    private fun scheduleRefresh(delayMs: Long = 800) {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            kotlinx.coroutines.delay(delayMs)
            fetchItems()
        }
    }

    private fun recomputeTotalFromAdapter() {
        try {
            val items = adapter.currentList
            var total = 0.0
            items.forEach { ci ->
                val qty = ci.quantity ?: 1
                val unit = (ci.final_price ?: ci.unit_price) ?: run {
                    val pid = ci.product_id
                    if (pid != null) productCache[pid]?.precio ?: 0.0 else 0.0
                }
                total += unit * qty
            }
            binding.txtTotal.text = "Total: ${Money.formatCLP(total)}"
        } catch (_: Exception) { /* ignorar */ }
    }

    // Encolar borrado de un ítem para procesarlo en serie con manejo de 429
    private fun requestDelete(itemId: Long) {
        enqueue {
            try {
                val svc = RetrofitClient.cartItemService(requireContext())
                try {
                    svc.delete(itemId)
                } catch (e: HttpException) {
                    if (e.code() == 429) {
                        rateLimitedUntilMs = System.currentTimeMillis() + 10_000
                        kotlinx.coroutines.delay(1000)
                        kotlin.runCatching { svc.delete(itemId) }
                    }
                }
            } finally {
                // Refrescar con un pequeño debounce
                scheduleRefresh(600)
            }
        }
    }

    // Vaciar el carrito con borrado secuencial y pausas entre items
    private fun clearCartQueued() {
        scope.launch {
            try {
                val cm = CartManager(requireContext())
                val cartId = cm.getCartId() ?: return@launch
                val svc = RetrofitClient.cartItemService(requireContext())
                val items = kotlin.runCatching { svc.list(cartId = cartId) }.getOrElse { emptyList() }
                kotlin.runCatching { binding.btnCheckout.isEnabled = false }
                for (ci in items) {
                    val id = ci.id ?: continue
                    try {
                        svc.delete(id)
                    } catch (e: HttpException) {
                        if (e.code() == 429) {
                            rateLimitedUntilMs = System.currentTimeMillis() + 10_000
                            kotlinx.coroutines.delay(1000)
                            kotlin.runCatching { svc.delete(id) }
                        }
                    }
                    kotlinx.coroutines.delay(150)
                }
                kotlin.runCatching { CartCache(requireContext()).clear(cartId) }
                // Eliminar el carrito completo y resetear cart_id para evitar residuos
                kotlin.runCatching { CartManager(requireContext()).clear(requireContext()) }
                fetchItems()
                Toast.makeText(requireContext(), "Carrito vaciado", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "No se pudo vaciar el carrito", Toast.LENGTH_SHORT).show()
            } finally {
                kotlin.runCatching { binding.btnCheckout.isEnabled = true }
            }
        }
    }

    // Confirmación suspendida cuando el total cambia antes de pagar
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun confirmProceedTotalChange(localTotal: Double, finalTotal: Double): Boolean {
        return kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            try {
                val ctx = requireContext()
                val msg = "El total cambió de ${Money.formatCLP(localTotal)} a ${Money.formatCLP(finalTotal)}. ¿Deseas continuar?"
                android.app.AlertDialog.Builder(ctx)
                    .setTitle("Total actualizado")
                    .setMessage(msg)
                    .setCancelable(false)
                    .setNegativeButton("Cancelar") { dlg, _ ->
                        dlg.dismiss()
                        if (cont.isActive) cont.resume(false, onCancellation = { _ -> })
                    }
                    .setPositiveButton("Continuar") { dlg, _ ->
                        dlg.dismiss()
                        if (cont.isActive) cont.resume(true, onCancellation = { _ -> })
                    }
                    .show()
            } catch (_: Exception) {
                // Si falla el diálogo, continuar por defecto
                if (cont.isActive) cont.resume(true, onCancellation = { _ -> })
            }
        }
    }

    private suspend fun resolveProductPreview(pid: Long): CartProductPreview? {
        productCache[pid]?.let { return it }
        return kotlin.runCatching {
            val p = RetrofitClient.productService(requireContext()).getById(pid)
            val preview = CartProductPreview(
                nombre = p.nombre ?: "",
                imageUrl = p.imagenes?.firstOrNull()?.url,
                precio = p.precio
            )
            productCache[pid] = preview
            preview
        }.getOrNull()
    }

    private fun checkout() {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                if (now < rateLimitedUntilMs) {
                    if (now - lastToast429At > 1500) {
                        Toast.makeText(requireContext(), "Estás temporalmente limitado. Intenta en ~10s.", Toast.LENGTH_SHORT).show()
                        lastToast429At = now
                    }
                    return@launch
                }
                kotlin.runCatching { binding.btnCheckout.isEnabled = false }
                val cm = CartManager(requireContext())
                val cartId = cm.getCartId() ?: run {
                    Toast.makeText(requireContext(), "Carrito vacío", Toast.LENGTH_SHORT).show(); return@launch
                }
                val items = RetrofitClient.cartItemService(requireContext()).list(cartId = cartId)
                if (items.isEmpty()) {
                    Toast.makeText(requireContext(), "Carrito vacío", Toast.LENGTH_SHORT).show(); return@launch
                }
                // Reconciliar precios con backend SIEMPRE (evita desajustes)
                val ps = RetrofitClient.productService(requireContext())
                val serverPrices = items.associate { ci ->
                    val pid = ci.product_id
                    val price = run {
                        val stored = ci.final_price ?: ci.unit_price
                        if (stored != null) stored else if (pid != null) kotlin.runCatching { ps.getById(pid).precio }.getOrElse { 0.0 } else 0.0
                    }
                    (ci.id ?: -1L) to price
                }
                val finalTotal = items.sumOf { ci ->
                    val qty = ci.quantity ?: 1
                    val price = serverPrices[ci.id ?: -1L] ?: 0.0
                    price * qty
                }
                // Actualizar visual para reflejar el total reconciliado
                binding.txtTotal.text = "Total: ${Money.formatCLP(finalTotal)}"

                // Comparar contra el total local previo
                val localTotal = items.sumOf { ci ->
                    val qty = ci.quantity ?: 1
                    val unit = (ci.final_price ?: ci.unit_price) ?: run {
                        val pid = ci.product_id
                        if (pid != null) productCache[pid]?.precio ?: 0.0 else 0.0
                    }
                    unit * qty
                }
                val changed = kotlin.math.abs(finalTotal - localTotal) > 0.5
                if (changed) {
                    val ok = confirmProceedTotalChange(localTotal, finalTotal)
                    if (!ok) {
                        kotlin.runCatching { binding.btnCheckout.isEnabled = true }
                        return@launch
                    }
                }

                val order = RetrofitClient.orderService(requireContext()).create(
                    mapOf(
                        "status" to "PENDING",
                        "total" to finalTotal,
                        // Asociar explícitamente al usuario autenticado
                        "user_id" to (TokenManager(requireContext()).getProfileId() ?: kotlin.runCatching { RetrofitClient.authService(requireContext()).me().id }.getOrNull())
                        // No solicitar envío automáticamente; el usuario lo hará desde "Mis compras"
                    )
                )
                val orderId = order.id ?: throw IllegalStateException("Orden sin ID")
                val cartSvc = RetrofitClient.cartItemService(requireContext())
                var createdTotal = 0.0
                var hadFailures = false
                val createdItemIds = mutableListOf<Long>()
                items.forEach { it ->
                    val priceForItem = serverPrices[it.id ?: -1L] ?: (it.final_price ?: it.unit_price)
                    try {
                        val pid = it.product_id ?: return@forEach
                        val p = kotlin.runCatching { RetrofitClient.productService(requireContext()).getById(pid) }.getOrNull()
                        val unitLogical = run {
                            val base = if (p?.oferta == true) (p.precioOferta ?: p.precio) else (p?.precio ?: (priceForItem ?: 0.0))
                            base
                        }
                        val mainImage = p?.imagenes?.firstOrNull()
                        RetrofitClient.orderProductService(requireContext()).create(
                            mapOf(
                                // Duplicamos claves para compatibilidad con distintos esquemas de Xano
                                "order_id" to orderId,
                                "order" to orderId,
                                "product_id" to pid,
                                "product" to pid,
                                "quantity" to (it.quantity ?: 1),
                                // Campos opcionales soportados por tu backend
                                "name" to (it.name ?: p?.nombre ?: productCache[pid]?.nombre),
                                "final_price" to unitLogical,
                                "main_image" to (it.main_image ?: mainImage),
                                // Compatibilidad: algunas tablas usan price/unit_price
                                "price" to unitLogical,
                                "unit_price" to unitLogical
                            )
                        )
                        // Evitar ráfaga de peticiones contra el backend
                        kotlinx.coroutines.delay(120)
                        val qty = it.quantity ?: 1
                        val unit = unitLogical
                        createdTotal += unit * qty
                        it.id?.let { id -> createdItemIds.add(id) }
                    } catch (he: retrofit2.HttpException) {
                        if (he.code() == 429) {
                            rateLimitedUntilMs = System.currentTimeMillis() + 10_000
                            Toast.makeText(requireContext(), "Rate limit durante checkout. Reintentaremos luego.", Toast.LENGTH_SHORT).show()
                        }
                        // Tratar 409/422 como falta de stock o conflicto
                        if (he.code() == 409 || he.code() == 422) {
                            hadFailures = true
                            val name = it.product_id?.let { pid -> productCache[pid]?.nombre } ?: "Producto"
                            Toast.makeText(requireContext(), "Sin stock para $name", Toast.LENGTH_SHORT).show()
                        } else {
                            hadFailures = true
                            Toast.makeText(requireContext(), "Error al agregar línea (${he.code()})", Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) {
                        hadFailures = true
                        Toast.makeText(requireContext(), "Error al agregar línea", Toast.LENGTH_SHORT).show()
                    }
                }

                // Ajustar total de la orden a lo realmente creado
                kotlin.runCatching { RetrofitClient.orderService(requireContext()).update(orderId, mapOf("total" to createdTotal)) }

                if (hadFailures) {
                    // No limpiar todo el carrito; eliminar solo ítems que sí se agregaron a la orden
                    createdItemIds.forEach { id -> kotlin.runCatching { cartSvc.delete(id) } }
                    Toast.makeText(requireContext(), "Algunos productos no pudieron comprarse por stock.", Toast.LENGTH_LONG).show()
                    savePendingOrder(orderId)
                    updateShipmentButtonVisibility()
                    watchOrderStatus(orderId)
                    fetchItems()
                } else {
                    // Eliminar ítems del carrito usando los IDs ya cargados, evitando un GET extra
                    items.forEach { ci ->
                        val id = ci.id ?: return@forEach
                        try {
                            cartSvc.delete(id)
                        } catch (he: retrofit2.HttpException) {
                            if (he.code() == 429) {
                                val retryHeader = he.response()?.headers()?.get("Retry-After")
                                val retrySec = retryHeader?.toLongOrNull()
                                val backoffMs = ((retrySec ?: 10) * 1000L)
                                rateLimitedUntilMs = System.currentTimeMillis() + backoffMs
                                kotlinx.coroutines.delay(backoffMs.coerceAtMost(15_000))
                                kotlin.runCatching { cartSvc.delete(id) }
                            }
                        }
                        // Pequeña pausa para evitar ráfagas
                        kotlinx.coroutines.delay(120)
                    }
                    // Limpiar caché del carrito
                    kotlin.runCatching { CartCache(requireContext()).clear(cartId) }
                    // Borrar el carrito completo y resetear el cart_id para evitar que queden productos
                    kotlin.runCatching { CartManager(requireContext()).clear(requireContext()) }
                    Toast.makeText(requireContext(), "¡Gracias por tu compra! El administrador confirmará tu pedido.", Toast.LENGTH_SHORT).show()
                    savePendingOrder(orderId)
                    watchOrderStatus(orderId)
                    fetchItems()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), e.message ?: "No se pudo pagar", Toast.LENGTH_SHORT).show()
            } finally {
                kotlin.runCatching { binding.btnCheckout.isEnabled = true }
            }
        }
    }

    private fun savePendingOrder(orderId: Long) {
        val prefs = requireContext().getSharedPreferences("order_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().putLong("last_order_id", orderId).apply()
    }

    private fun watchPendingOrderIfAny() {
        val prefs = requireContext().getSharedPreferences("order_prefs", android.content.Context.MODE_PRIVATE)
        val id = prefs.getLong("last_order_id", -1L)
        if (id > 0L) {
            watchOrderStatus(id)
        }
    }

    private fun clearPendingOrder() {
        val prefs = requireContext().getSharedPreferences("order_prefs", android.content.Context.MODE_PRIVATE)
        prefs.edit().remove("last_order_id").apply()
        updateShipmentButtonVisibility()
    }

    private fun watchOrderStatus(orderId: Long) {
        scope.launch {
            try {
                // Polling simple cada 10 segundos
                var acceptedNotified = false
                while (true) {
                    val order = RetrofitClient.orderService(requireContext()).getById(orderId)
                    when (order.status?.uppercase()) {
                        "ACCEPTED" -> {
                            if (!acceptedNotified) {
                                showNotification(
                                    title = "Compra validada",
                                    text = "Tu pedido #$orderId fue validado",
                                    channelId = "orders_channel"
                                )
                                acceptedNotified = true
                            }
                            // continuar esperando envío
                            updateShipmentButtonVisibility()
                        }
                        "SHIPPED" -> {
                            showNotification(
                                title = "Pedido enviado",
                                text = "Tu pedido #$orderId fue marcado como enviado",
                                channelId = "orders_channel"
                            )
                            clearPendingOrder()
                            break
                        }
                        "REJECTED" -> {
                            showNotification(
                                title = "Compra rechazada",
                                text = "Tu pedido #$orderId fue rechazado",
                                channelId = "orders_channel"
                            )
                            clearPendingOrder()
                            break
                        }
                        else -> { /* sigue pendiente */ }
                    }
                    kotlinx.coroutines.delay(10_000)
                }
            } catch (_: Exception) { /* ignorar errores de red */ }
        }
    }

    private fun updateShipmentButtonVisibility() { binding.btnRequestShipment.visibility = View.GONE }

    // requestShipment() eliminado: la solicitud de envío se gestiona en "Mis compras"

    // Intenta actualizar el usuario con las variantes disponibles: /user vs /users y PUT vs PATCH
    // updateUserFlexible() eliminado: usar cl.shoppersa.api.UserUpdateHelper.updateFlexible

    private fun showNotification(title: String, text: String, channelId: String) {
        // Crear canal si es necesario (Android O+)
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
        NotificationManagerCompat.from(requireContext()).notify(1001, notif)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        job.cancel()
        _binding = null
    }
}

private class CartAdapter(
    private val scope: CoroutineScope,
    private val resolveProduct: suspend (Long) -> CartProductPreview?,
    private val onChanged: () -> Unit,
    private val onInstantRecompute: () -> Unit,
    private val onDeleteQueued: (Long) -> Unit
) : androidx.recyclerview.widget.ListAdapter<CartItem, androidx.recyclerview.widget.RecyclerView.ViewHolder>(
    object : androidx.recyclerview.widget.DiffUtil.ItemCallback<CartItem>() {
        override fun areItemsTheSame(oldItem: CartItem, newItem: CartItem) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: CartItem, newItem: CartItem) = oldItem == newItem
    }
) {
    private val mutatingIds = mutableSetOf<Long>()
    // Debounce por ítem: cantidad pendiente y job
    private val pendingQty = mutableMapOf<Long, Int>()
    private val pendingJobs = mutableMapOf<Long, kotlinx.coroutines.Job>()
    private data class ViewRefs(
        val img: ImageView,
        val name: TextView,
        val price: TextView,
        val txtQty: TextView,
        val btnMinus: TextView,
        val btnPlus: TextView,
        val btnDel: TextView
    )
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding((16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
            layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
            }
            background = androidx.core.content.ContextCompat.getDrawable(ctx, cl.shoppersa.R.drawable.bg_card)
            elevation = 4f
        }
        val img = ImageView(ctx).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams((96 * density).toInt(), (96 * density).toInt())
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
        }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins((12 * density).toInt(), 0, 0, 0) }
        }
        val name = TextView(ctx).apply {
            textSize = 16f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, cl.shoppersa.R.color.brand_on_surface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setMaxLines(2)
        }
        val price = TextView(ctx).apply {
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, cl.shoppersa.R.color.text_muted))
        }

        val controls = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, (8 * ctx.resources.displayMetrics.density).toInt(), 0, 0) }
        }
        fun roundedBg(color: Int): android.graphics.drawable.GradientDrawable = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = (12 * density)
            setColor(color)
        }
        fun makeBtn(label: String, bgColor: Int): TextView = TextView(ctx).apply {
            text = label
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            setTextColor(android.graphics.Color.WHITE)
            background = roundedBg(bgColor)
            textSize = 14f
        }
        val btnMinus = makeBtn("−", androidx.core.content.ContextCompat.getColor(ctx, cl.shoppersa.R.color.brand_primary))
        val txtQty   = TextView(ctx).apply {
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            textSize = 14f
            setTextColor(androidx.core.content.ContextCompat.getColor(ctx, cl.shoppersa.R.color.brand_on_surface))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = (8 * density)
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((1 * density).toInt(), androidx.core.content.ContextCompat.getColor(ctx, cl.shoppersa.R.color.brand_border))
            }
        }
        val btnPlus  = makeBtn("+", androidx.core.content.ContextCompat.getColor(ctx, cl.shoppersa.R.color.brand_primary))
        val btnDel   = TextView(ctx).apply {
            text = "✕"
            setPadding((16 * density).toInt(), (8 * density).toInt(), (16 * density).toInt(), (8 * density).toInt())
            setTextColor(android.graphics.Color.WHITE)
            textSize = 14f
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = (8 * density)
                setColor(androidx.core.content.ContextCompat.getColor(ctx, cl.shoppersa.R.color.brand_error))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins((8 * density).toInt(), 0, 0, 0) }
        }
        controls.addView(btnMinus)
        controls.addView(txtQty)
        controls.addView(btnPlus)
        controls.addView(btnDel)

        texts.addView(name)
        texts.addView(price)
        container.addView(img)
        container.addView(texts)
        texts.addView(controls)
        container.tag = ViewRefs(img, name, price, txtQty, btnMinus, btnPlus, btnDel)
        return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(container) {}
    }
    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context
        val refs = holder.itemView.tag as ViewRefs
        val img = refs.img
        val name = refs.name
        val price = refs.price
        val txtQty = refs.txtQty
        val btnMinus = refs.btnMinus
        val btnPlus = refs.btnPlus
        val btnDel = refs.btnDel

        txtQty.text = (item.quantity ?: 1).toString()

        scope.launch {
            try {
                val pid = item.product_id ?: return@launch
                val preview = resolveProduct(pid)
                val priceVal = (item.final_price ?: item.unit_price) ?: preview?.precio ?: 0.0
                name.text = item.name ?: preview?.nombre ?: "Producto"
                val thumbUrl = item.main_image?.url ?: preview?.imageUrl
                if (thumbUrl.isNullOrBlank()) {
                    img.load(R.drawable.placeholder_rect)
                } else {
                    img.load(thumbUrl) { crossfade(true) }
                }
                price.text = Money.formatCLP(priceVal)
            } catch (_: Exception) {
                name.text = "Producto"
                img.load(R.drawable.placeholder_rect)
                price.text = "$"
            }
        }

        // Acciones: actualizar cantidad y eliminar
        val svc = RetrofitClient.cartItemService(ctx)
        btnMinus.setOnClickListener {
            val id = item.id ?: return@setOnClickListener
            if (mutatingIds.contains(id)) return@setOnClickListener
            // Leer la cantidad visible para evitar quedarnos pegados en 2
            val qVisible = txtQty.text?.toString()?.trim()?.toIntOrNull()
            val q = qVisible ?: (item.quantity ?: 1)
            if (q > 1) {
                scope.launch {
                    try {
                        mutatingIds.add(id)
                        btnMinus.isEnabled = false; btnPlus.isEnabled = false; btnDel.isEnabled = false
                        // Feedback inmediato
                        val newQ = (q - 1).coerceAtLeast(1)
                        txtQty.text = newQ.toString()
                        // Actualizar la lista del adapter para que el total se recalcule con la nueva cantidad
                        kotlin.runCatching {
                            val current = this@CartAdapter.currentList.toMutableList()
                            current[position] = item.copy(quantity = newQ)
                            this@CartAdapter.submitList(current)
                        }
                        onInstantRecompute()
                        // Debounce: enviar actualización al backend solo una vez transcurridos 600ms sin más cambios
                        pendingJobs[id]?.cancel()
                        pendingQty[id] = newQ
                        pendingJobs[id] = scope.launch {
                            kotlinx.coroutines.delay(600)
                            val sendQ = pendingQty.remove(id) ?: newQ
                            try {
                                svc.update(id, mapOf("quantity" to sendQ))
                            } catch (e: retrofit2.HttpException) {
                                // Fallback: algunos workspaces requieren PUT con cuerpo completo
                                val unit = (item.final_price ?: item.unit_price) ?: kotlin.runCatching {
                                    val pid = item.product_id
                                    if (pid != null) cl.shoppersa.api.RetrofitClient.productService(ctx).getById(pid).precio else 0.0
                                }.getOrDefault(0.0)
                                val fullBody = mutableMapOf<String, Any?>().apply {
                                    put("cart_id", item.cart_id)
                                    put("product_id", item.product_id)
                                    put("quantity", sendQ)
                                    put("unit_price", unit)
                                    put("final_price", unit)
                                }
                                kotlin.runCatching { svc.updatePut(id, fullBody) }
                            } finally {
                                onChanged()
                                pendingJobs.remove(id)
                            }
                        }
                    } catch (_: retrofit2.HttpException) { /* 409/429 etc. */ } catch (_: Exception) { }
                    finally {
                        mutatingIds.remove(id)
                        btnMinus.isEnabled = true; btnPlus.isEnabled = true; btnDel.isEnabled = true
                    }
                    // No disparamos refresh inmediato aquí; el pending job enviará actualización y refrescará
                }
            }
        }
        btnPlus.setOnClickListener {
            val id = item.id ?: return@setOnClickListener
            if (mutatingIds.contains(id)) return@setOnClickListener
            // Leer la cantidad visible para evitar quedarnos pegados en 2
            val qVisible = txtQty.text?.toString()?.trim()?.toIntOrNull()
            val q = qVisible ?: (item.quantity ?: 1)
            scope.launch {
                try {
                    mutatingIds.add(id)
                    btnMinus.isEnabled = false; btnPlus.isEnabled = false; btnDel.isEnabled = false
                    // Feedback inmediato
                    val newQ = q + 1
                    txtQty.text = newQ.toString()
                    // Actualizar la lista del adapter para que el total se recalcule con la nueva cantidad
                    kotlin.runCatching {
                        val current = this@CartAdapter.currentList.toMutableList()
                        current[position] = item.copy(quantity = newQ)
                        this@CartAdapter.submitList(current)
                    }
                    onInstantRecompute()
                    // Debounce: enviar actualización al backend solo una vez transcurridos 600ms sin más cambios
                    pendingJobs[id]?.cancel()
                    pendingQty[id] = newQ
                    pendingJobs[id] = scope.launch {
                        kotlinx.coroutines.delay(600)
                        val sendQ = pendingQty.remove(id) ?: newQ
                        try {
                            svc.update(id, mapOf("quantity" to sendQ))
                        } catch (e: retrofit2.HttpException) {
                            // Fallback: algunos workspaces requieren PUT con cuerpo completo
                            val unit = (item.final_price ?: item.unit_price) ?: kotlin.runCatching {
                                val pid = item.product_id
                                if (pid != null) cl.shoppersa.api.RetrofitClient.productService(ctx).getById(pid).precio else 0.0
                            }.getOrDefault(0.0)
                            val fullBody = mutableMapOf<String, Any?>().apply {
                                put("cart_id", item.cart_id)
                                put("product_id", item.product_id)
                                put("quantity", sendQ)
                                put("unit_price", unit)
                                put("final_price", unit)
                            }
                            kotlin.runCatching { svc.updatePut(id, fullBody) }
                        } finally {
                            onChanged()
                            pendingJobs.remove(id)
                        }
                    }
                } catch (_: retrofit2.HttpException) { /* 409/429 etc. */ } catch (_: Exception) { }
                finally {
                    mutatingIds.remove(id)
                    btnMinus.isEnabled = true; btnPlus.isEnabled = true; btnDel.isEnabled = true
                }
                // No disparamos refresh inmediato aquí; el pending job enviará actualización y refrescará
            }
        }
        btnDel.setOnClickListener {
            val id = item.id ?: return@setOnClickListener
            scope.launch {
                try {
                    mutatingIds.add(id)
                    btnMinus.isEnabled = false; btnPlus.isEnabled = false; btnDel.isEnabled = false
                    // Encolar el borrado para procesarlo secuencialmente en el fragmento
                    onDeleteQueued(id)
                } catch (_: Exception) { }
                finally {
                    mutatingIds.remove(id)
                    btnMinus.isEnabled = true; btnPlus.isEnabled = true; btnDel.isEnabled = true
                }
                // El fragmento encolará el borrado y refrescará con debounce
            }
        }
    }
}
