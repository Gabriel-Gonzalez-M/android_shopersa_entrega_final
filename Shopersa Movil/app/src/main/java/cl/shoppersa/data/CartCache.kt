package cl.shoppersa.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import cl.shoppersa.model.CartItem

class CartCache(private val context: Context) {
    private val prefs = context.getSharedPreferences("cart_cache", Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(cartId: Long, items: List<CartItem>) {
        val json = gson.toJson(items)
        prefs.edit().putString(key(cartId), json).apply()
    }

    fun load(cartId: Long): List<CartItem>? {
        val json = prefs.getString(key(cartId), null) ?: return null
        return try {
            val type = object : TypeToken<List<CartItem>>() {}.type
            gson.fromJson<List<CartItem>>(json, type)
        } catch (_: Exception) {
            null
        }
    }

    fun clear(cartId: Long) {
        prefs.edit().remove(key(cartId)).apply()
    }

    private fun key(cartId: Long) = "cart_items_$cartId"
}