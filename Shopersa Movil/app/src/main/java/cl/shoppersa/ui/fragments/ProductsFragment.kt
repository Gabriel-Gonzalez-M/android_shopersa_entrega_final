package cl.shoppersa.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.databinding.FragmentProductsBinding
import cl.shoppersa.model.Product
import cl.shoppersa.ui.ProductDetailActivity
import cl.shoppersa.ui.adapter.ProductAdapter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.text.Normalizer

class ProductsFragment : Fragment() {

    private var _binding: FragmentProductsBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ProductAdapter

    // Lista completa en memoria
    private val allProducts = mutableListOf<Product>()

    // Búsqueda local (con debounce)
    private var currentQuery: String = ""
    private var searchJob: Job? = null

    // Rate limit por si el backend respondiera 429
    private var rateLimitedUntilMs: Long = 0
    private var lastToast429At: Long = 0
    private var lastFetchMs: Long = 0

    // Evitar descargas concurrentes
    private var isLoading = false

    // Cache simple (vive mientras el proceso viva)
    companion object {
        private var cachedProducts: List<Product>? = null
        fun invalidateCache() { cachedProducts = null }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProductsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Restaurar query (si rotó, etc.)
        currentQuery = savedInstanceState?.getString("q").orEmpty()

        adapter = ProductAdapter(onClick = { product ->
            startActivity(
                android.content.Intent(requireContext(), ProductDetailActivity::class.java)
                    .putExtra("product_id", product.id ?: -1L)
            )
        }, showAddToCart = false)

        binding.recyclerProducts.apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            setHasFixedSize(true)
            adapter = this@ProductsFragment.adapter
        }

        // Pull-to-refresh: recarga TODO desde el backend
        binding.swipe.setOnRefreshListener { fetchAll(force = true) }

        // Búsqueda local con debounce (NO llama al backend)
        binding.etSearch.setText(currentQuery)
        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            searchJob?.cancel()
            searchJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(300)
                currentQuery = text?.toString()?.trim().orEmpty()
                applyLocalFilter()
            }
        }

        // Mostrar cache inmediato en memoria o persistente para velocidad
        if (!cachedProducts.isNullOrEmpty()) {
            allProducts.clear()
            allProducts.addAll(cachedProducts!!)
            applyLocalFilter()
        } else {
            val persisted = cl.shoppersa.data.CacheStore.loadProducts(requireContext())
            if (persisted.isNotEmpty()) {
                allProducts.clear()
                allProducts.addAll(persisted)
                applyLocalFilter()
            }
        }
        // …pero SIEMPRE refrescar desde red para traer lo nuevo
        fetchAll(force = true)
    }

    override fun onResume() {
        super.onResume()
        // Al volver, refrescar solo si pasó tiempo suficiente para evitar spam
        val now = System.currentTimeMillis()
        if (now - lastFetchMs > 15_000) {
            fetchAll(force = true)
        }
    }

    /** Descarga TODOS los productos (o usa cache si no es 'force'). */
    private fun fetchAll(force: Boolean) {
        val now = System.currentTimeMillis()
        if (now < rateLimitedUntilMs) {
            if (now - lastToast429At > 1500) {
                Toast.makeText(
                    requireContext(),
                    "Demasiadas peticiones. Intenta nuevamente en unos segundos.",
                    Toast.LENGTH_SHORT
                ).show()
                lastToast429At = now
            }
            binding.swipe.isRefreshing = false
            return
        }

        if (isLoading) return
        isLoading = true
        binding.swipe.isRefreshing = true

        // Si no es force y ya tenemos caché, úsala
        if (!force && !cachedProducts.isNullOrEmpty()) {
            allProducts.clear()
            allProducts.addAll(cachedProducts!!)
            applyLocalFilter()
            binding.swipe.isRefreshing = false
            isLoading = false
            return
        }

        val service = RetrofitClient.productService(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val list = service.list()
                cachedProducts = list.toList()
                allProducts.clear()
                allProducts.addAll(list)
                applyLocalFilter()
                lastFetchMs = System.currentTimeMillis()
                // Guardar cache persistente
                cl.shoppersa.data.CacheStore.saveProducts(requireContext(), list)
            } catch (e: HttpException) {
                if (e.code() == 429) {
                    val retryAfterSec = e.response()?.headers()?.get("retry-after")
                        ?.toIntOrNull()?.coerceAtLeast(1) ?: 10
                    rateLimitedUntilMs = System.currentTimeMillis() + retryAfterSec * 1000L
                    if (System.currentTimeMillis() - lastToast429At > 1500) {
                        Toast.makeText(
                            requireContext(),
                            "Estás refrescando muy rápido. Reintenta en ~${retryAfterSec}s.",
                            Toast.LENGTH_SHORT
                        ).show()
                        lastToast429At = System.currentTimeMillis()
                    }
                } else {
                    Toast.makeText(
                        requireContext(),
                        "Error ${e.code()}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    requireContext(),
                    e.message ?: "Error de red",
                    Toast.LENGTH_SHORT
                ).show()
            } finally {
                binding.swipe.isRefreshing = false
                isLoading = false
            }
        }
    }

    /** Aplica la búsqueda local sobre [allProducts] (sin tocar el backend). */
    private fun applyLocalFilter() {
        val list = if (currentQuery.isBlank()) {
            allProducts
        } else {
            val q = foldSpanish(currentQuery)
            allProducts.filter { p ->
                val name = foldSpanish(p.nombre.orEmpty())
                name.contains(q, ignoreCase = true)
            }
        }
        adapter.submit(list)
    }

    // ==== util: soportar ñ/tildes en búsqueda local ====
    private fun foldSpanish(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        val noMarks = decomposed.replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
        return noMarks.replace('ñ', 'n').replace('Ñ', 'N')
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("q", currentQuery)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
