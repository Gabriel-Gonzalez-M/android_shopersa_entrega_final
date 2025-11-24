package cl.shoppersa.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import cl.shoppersa.databinding.FragmentAdminProductsBinding
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.ui.adapter.ProductAdapter
import cl.shoppersa.model.Product
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.core.widget.addTextChangedListener
import kotlinx.coroutines.delay

class ProductAdminFragment : Fragment() {
    private var _binding: FragmentAdminProductsBinding? = null
    private val binding get() = _binding!!
    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private val adapter = ProductAdapter(onClick = { product ->
        // En admin, abrir AddProductFragment en modo edición
        val frag = AddProductFragment.newInstance(product.id ?: -1)
        requireActivity().supportFragmentManager.beginTransaction()
            .replace(cl.shoppersa.R.id.fragmentContainer, frag)
            .addToBackStack(null)
            .commit()
    }, showAddToCart = false)

    private var allItems: List<Product> = emptyList()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAdminProductsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter
        binding.swipe.setOnRefreshListener { fetchProducts() }
        // Filtrado local: sin llamadas por cada tecla
        binding.editSearch.addTextChangedListener { text ->
            val q = text?.toString()?.trim().orEmpty()
            filterLocal(q)
        }
        // Mostrar caché persistente para evitar petición inicial
        val persisted = cl.shoppersa.data.CacheStore.loadProducts(requireContext())
        if (persisted.isNotEmpty()) {
            allItems = persisted
            adapter.submit(persisted)
        }
        fetchProducts()
    }

    private fun fetchProducts() {
        binding.stateLoading.visibility = View.VISIBLE
        binding.stateError.visibility = View.GONE
        binding.stateEmpty.visibility = View.GONE
        scope.launch {
            try {
                val items = RetrofitClient.productService(requireContext()).list()
                allItems = items
                adapter.submit(items)
                // Guardar en caché persistente para clientes y admin
                cl.shoppersa.data.CacheStore.saveProducts(requireContext(), items)
                binding.stateEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                binding.stateError.visibility = View.VISIBLE
            } finally {
                binding.stateLoading.visibility = View.GONE
                binding.swipe.isRefreshing = false
            }
        }
    }

    private var filterJob: Job? = null
    private fun filterLocal(q: String) {
        filterJob?.cancel()
        filterJob = scope.launch {
            delay(200)
            val filtered = if (q.isBlank()) allItems else allItems.filter {
                (it.nombre ?: "").contains(q, ignoreCase = true)
            }
            adapter.submit(filtered)
            binding.stateEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        job.cancel()
        _binding = null
    }
}