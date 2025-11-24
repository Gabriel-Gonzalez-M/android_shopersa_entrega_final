package cl.shoppersa.util

import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

object Money {
    private val clLocale: Locale = Locale.forLanguageTag("es-CL")
    private val clp: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(clLocale).apply {
            currency = Currency.getInstance("CLP")
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }
    }

    fun formatCLP(amount: Double?): String = clp.format(amount ?: 0.0)
    fun formatCLP(amount: Double): String = clp.format(amount)
}