package cl.shoppersa.ui

import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.tabs.TabLayoutMediator
import cl.shoppersa.R
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.databinding.ActivityProductDetailBinding
import cl.shoppersa.model.Product
import cl.shoppersa.model.ProductImage
import cl.shoppersa.ui.adapter.ImageSliderAdapter
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

class ProductDetailActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProductDetailBinding

    // Formateador CLP
    private val clLocale: Locale = Locale.forLanguageTag("es-CL")
    private val clp: NumberFormat by lazy {
        NumberFormat.getCurrencyInstance(clLocale).apply {
            currency = Currency.getInstance("CLP")
            maximumFractionDigits = 0
            minimumFractionDigits = 0
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProductDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // TOP BAR: igual a Home (logo en XML, sin título, sin flecha, sin insets)
        setSupportActionBar(binding.topAppBar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.topAppBar.logo = null
        binding.topAppBar.navigationIcon = null
        binding.topAppBar.setContentInsetsRelative(0, 0)
        binding.topAppBar.setContentInsetsAbsolute(0, 0)
        binding.topAppBar.setPadding(0, 0, 0, 0)

        // BOTTOM BAR
        binding.bottomNavDetail.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.menu_products -> { finish(); true }

                R.id.menu_cart     -> { goHome("cart"); finish(); true }
                R.id.menu_profile  -> { goHome("profile"); finish(); true }
                else -> false
            }
        }

        val productId = intent.getLongExtra("product_id", -1L)
        if (productId <= 0L) { finish(); return }

        loadProduct(productId)

        binding.btnAddToCart.setOnClickListener {
            addToCart(productId)
        }
    }

    private fun goHome(dest: String) {
        val i = Intent(this, HomeActivity::class.java).apply {
            putExtra("dest", dest)
            // Quitar CLEAR_TOP y SINGLE_TOP para no reusar la instancia
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(i)
    }

    private fun loadProduct(id: Long) {
        setLoading(true)
        lifecycleScope.launch {
            try {
                val service = RetrofitClient.productService(this@ProductDetailActivity)
                val product = service.getById(id)
                bindProduct(product)
            } catch (e: HttpException) {
                Toast.makeText(this@ProductDetailActivity, "Error ${e.code()}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ProductDetailActivity, e.message ?: "Error de red", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun addToCart(productId: Long) {
        lifecycleScope.launch {
            try {
                cl.shoppersa.data.CartManager(this@ProductDetailActivity).addItem(
                    this@ProductDetailActivity, productId, 1
                )
                Toast.makeText(this@ProductDetailActivity, "Agregado al carrito", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@ProductDetailActivity, e.message ?: "No se pudo agregar", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun bindProduct(p: Product) {
        // Badges
        binding.badgeOffer.visibility = if (p.oferta) View.VISIBLE else View.GONE
        binding.badgeNew.visibility   = if (p.novedad) View.VISIBLE else View.GONE

        // Nombre
        binding.txtName.text = p.nombre ?: "—"

        // Colores
        val brand = ContextCompat.getColor(this, R.color.brand_primary)
        val gray  = ContextCompat.getColor(this, android.R.color.darker_gray)

        // Precios
        val isOffer    = p.oferta
        val offerPrice = p.precioOferta
        if (isOffer && offerPrice != null && offerPrice > 0.0) {
            // Oferta: precio oferta morado + original gris tachado
            binding.txtPriceOffer.visibility = View.VISIBLE
            binding.txtPriceOffer.text = clp.format(offerPrice)
            binding.txtPriceOffer.setTextColor(brand)

            binding.txtPriceOriginal.text = clp.format(p.precio)
            binding.txtPriceOriginal.setTextColor(gray)
            binding.txtPriceOriginal.paintFlags =
                binding.txtPriceOriginal.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            // Sin oferta: mostrar solo precio normal en morado
            binding.txtPriceOffer.visibility = View.GONE
            binding.txtPriceOriginal.text   = clp.format(p.precio)
            binding.txtPriceOriginal.paintFlags =
                binding.txtPriceOriginal.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            binding.txtPriceOriginal.setTextColor(brand)
        }

        // Descripción
        binding.txtDesc.text = p.descripcion?.ifBlank { "Sin descripción" } ?: "Sin descripción"

        // Slider
        val images: List<ProductImage> = p.imagenes ?: emptyList()
        binding.pager.adapter = ImageSliderAdapter(images)
        TabLayoutMediator(binding.tabs, binding.pager) { _, _ -> }.attach()
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.content.visibility  = if (loading) View.INVISIBLE else View.VISIBLE
    }
}

