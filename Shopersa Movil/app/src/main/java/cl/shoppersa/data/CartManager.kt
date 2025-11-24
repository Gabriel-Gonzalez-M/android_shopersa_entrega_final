package cl.shoppersa.data

import android.content.Context
import android.content.SharedPreferences
import cl.shoppersa.api.RetrofitClient

class CartManager(ctx: Context) {
    private val prefs: SharedPreferences =
        ctx.getSharedPreferences("cart_prefs", Context.MODE_PRIVATE)

    fun getCartId(): Long? = prefs.getLong("cart_id", -1L).let { if (it > 0) it else null }

    private fun saveCartId(id: Long) { prefs.edit().putLong("cart_id", id).apply() }

    suspend fun ensureCartId(context: Context): Long {
        val existing = getCartId()
        if (existing != null) return existing
        // Crear carrito asociado al usuario autenticado cuando sea posible
        val userId = cl.shoppersa.api.TokenManager(context).getProfileId()
            ?: kotlin.runCatching { cl.shoppersa.api.RetrofitClient.authService(context).me().id }.getOrNull()
        val created = RetrofitClient.cartService(context).create(
            if (userId != null) mapOf("user_id" to userId) else emptyMap()
        )
        val id = created.id ?: throw IllegalStateException("No se pudo crear carrito")
        saveCartId(id)
        return id
    }

    suspend fun addItem(context: Context, productId: Long, quantity: Int = 1) {
        val cartId = ensureCartId(context)
        // Resolver producto para fijar campos en el ítem (precio, nombre, imagen)
        val p = kotlin.runCatching { RetrofitClient.productService(context).getById(productId) }.getOrNull()
        val priceBase = p?.let {
            if (it.oferta) (it.precioOferta ?: it.precio) else it.precio
        } ?: kotlin.runCatching { RetrofitClient.productService(context).getById(productId).precio }.getOrElse { 0.0 }
        val mainImage = p?.imagenes?.firstOrNull()
        RetrofitClient.cartItemService(context).create(
            mapOf(
                "cart_id" to cartId,
                "product_id" to productId,
                "quantity" to quantity,
                // Compatibilidad: guardar tanto unit_price como final_price
                "unit_price" to priceBase,
                "final_price" to priceBase,
                // Campos descriptivos para evitar lookups posteriores
                "name" to (p?.nombre ?: "Producto #$productId"),
                "main_image" to mainImage
            )
        )
    }

    suspend fun clearItems(context: Context) {
        val id = getCartId() ?: return
        val svc = RetrofitClient.cartItemService(context)
        val items = kotlin.runCatching { svc.list(cartId = id) }.getOrElse { emptyList() }
        items.forEach { ci ->
            val cid = ci.id
            if (cid != null) kotlin.runCatching { svc.delete(cid) }
        }
    }

    suspend fun clear(context: Context) {
        val id = getCartId() ?: return
        try { RetrofitClient.cartService(context).delete(id) } catch (_: Exception) {}
        prefs.edit().remove("cart_id").apply()
    }
}