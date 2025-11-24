package cl.shoppersa.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cl.shoppersa.model.Product
import cl.shoppersa.model.User
import cl.shoppersa.model.Order

object CacheStore {
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("cache_store", Context.MODE_PRIVATE)

    private val gson = Gson()

    // Productos
    fun saveProducts(ctx: Context, list: List<Product>) {
        val json = gson.toJson(list)
        prefs(ctx).edit()
            .putString("products", json)
            .putLong("products_at", System.currentTimeMillis())
            .apply()
    }

    fun loadProducts(ctx: Context): List<Product> {
        val json = prefs(ctx).getString("products", null) ?: return emptyList()
        val type = object : TypeToken<List<Product>>() {}.type
        return runCatching { gson.fromJson<List<Product>>(json, type) }.getOrDefault(emptyList())
    }

    fun invalidateProducts(ctx: Context) {
        prefs(ctx).edit().remove("products").remove("products_at").apply()
    }

    // Producto individual (merge dentro de la lista persistida)
    fun saveProduct(ctx: Context, p: Product) {
        val current = loadProducts(ctx).toMutableList()
        val idx = current.indexOfFirst { (it.id ?: -1L) == (p.id ?: -2L) }
        if (idx >= 0) current[idx] = p else current.add(p)
        saveProducts(ctx, current)
    }

    fun getProduct(ctx: Context, id: Long): Product? =
        loadProducts(ctx).firstOrNull { (it.id ?: -1L) == id }

    // Usuarios
    fun saveUsers(ctx: Context, list: List<User>) {
        val json = gson.toJson(list)
        prefs(ctx).edit()
            .putString("users", json)
            .putLong("users_at", System.currentTimeMillis())
            .apply()
    }

    fun loadUsers(ctx: Context): List<User> {
        val json = prefs(ctx).getString("users", null) ?: return emptyList()
        val type = object : TypeToken<List<User>>() {}.type
        return runCatching { gson.fromJson<List<User>>(json, type) }.getOrDefault(emptyList())
    }

    fun invalidateUsers(ctx: Context) {
        prefs(ctx).edit().remove("users").remove("users_at").apply()
    }

    // Órdenes
    fun saveOrders(ctx: Context, list: List<Order>) {
        val json = gson.toJson(list)
        prefs(ctx).edit()
            .putString("orders", json)
            .putLong("orders_at", System.currentTimeMillis())
            .apply()
    }

    fun loadOrders(ctx: Context): List<Order> {
        val json = prefs(ctx).getString("orders", null) ?: return emptyList()
        val type = object : TypeToken<List<Order>>() {}.type
        return runCatching { gson.fromJson<List<Order>>(json, type) }.getOrDefault(emptyList())
    }

    fun invalidateOrders(ctx: Context) {
        prefs(ctx).edit().remove("orders").remove("orders_at").apply()
    }

    // Detalles de orden (OrderProduct[] por ID de orden)
    fun saveOrderDetails(ctx: Context, orderId: Long, list: List<cl.shoppersa.model.OrderProduct>) {
        val json = gson.toJson(list)
        prefs(ctx).edit()
            .putString("order_items_$orderId", json)
            .putLong("order_items_${orderId}_at", System.currentTimeMillis())
            .apply()
    }

    fun loadOrderDetails(ctx: Context, orderId: Long): List<cl.shoppersa.model.OrderProduct> {
        val key = "order_items_$orderId"
        val json = prefs(ctx).getString(key, null) ?: return emptyList()
        val type = object : TypeToken<List<cl.shoppersa.model.OrderProduct>>() {}.type
        return runCatching { gson.fromJson<List<cl.shoppersa.model.OrderProduct>>(json, type) }
            .getOrDefault(emptyList())
    }

    fun invalidateOrderDetails(ctx: Context, orderId: Long) {
        prefs(ctx).edit().remove("order_items_$orderId").remove("order_items_${orderId}_at").apply()
    }
}