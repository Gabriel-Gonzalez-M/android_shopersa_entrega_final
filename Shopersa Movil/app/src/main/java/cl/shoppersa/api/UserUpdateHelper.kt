package cl.shoppersa.api

import cl.shoppersa.model.User
import retrofit2.HttpException

object UserUpdateHelper {
    /**
     * Actualiza un usuario soportando variantes de API (/user vs /users) y PUT/PATCH.
     * - Intenta PATCH primero (parcial), luego PUT con merge si es necesario.
     */
    suspend fun updateFlexible(
        ctx: android.content.Context,
        id: Long,
        body: Map<String, @JvmSuppressWildcards Any?>
    ): User {
        val s = RetrofitClient.userService(ctx)
        // Compatibilidad: si viene 'address', duplicar como 'shipping_address'
        val mergedBody = if (body.containsKey("address")) {
            val addr = body["address"]
            body.toMutableMap().apply { put("shipping_address", addr) }
        } else body
        // PATCH-first
        kotlin.runCatching { return s.updatePatch(id, mergedBody) }
        kotlin.runCatching { return s.updatePluralPatch(id, mergedBody) }

        // Necesitamos PUT con cuerpo completo
        val existing: User = kotlin.runCatching { s.getById(id) }
            .getOrElse {
                kotlin.runCatching { s.getByIdPlural(id) }.getOrNull()
                    ?: throw it
            }

        val full = mutableMapOf<String, Any?>().apply {
            put("name", existing.name ?: "")
            put("last_name", existing.lastName ?: "")
            put("email", existing.email ?: "")
            put("phone", existing.phone ?: "")
            put("address", existing.address ?: "")
            // Duplicar shipping_address para compatibilidad
            put("shipping_address", existing.address ?: "")
            put("role", existing.role ?: "")
            put("status", existing.status ?: "")
        }
        for ((k, v) in mergedBody) full[k] = v
        // Si se pasa address en mergedBody, asegurar shipping_address también
        if (mergedBody.containsKey("address")) full["shipping_address"] = mergedBody["address"]

        kotlin.runCatching { return s.update(id, full) }
            .onFailure {
                kotlin.runCatching { return s.updatePlural(id, full) }
            }

        throw Exception("No se pudo actualizar el usuario: PATCH y PUT fallaron")
    }
}