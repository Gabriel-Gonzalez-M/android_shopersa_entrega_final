package cl.shoppersa.ui.fragments

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import cl.shoppersa.api.ProductService
import cl.shoppersa.api.UploadService
import cl.shoppersa.api.RetrofitClient
import cl.shoppersa.data.CacheStore
import cl.shoppersa.databinding.FragmentAddProductBinding
import cl.shoppersa.ui.adapter.ImagePreviewAdapter
import cl.shoppersa.model.ProductImage
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class AddProductFragment : Fragment() {

    private val productService: ProductService by lazy {
        RetrofitClient.productService(requireContext())
    }
    private val uploadService: UploadService by lazy {
        RetrofitClient.uploadService(requireContext())
    }

    private var _binding: FragmentAddProductBinding? = null
    private val binding get() = _binding!!

    private val selectedUris = mutableListOf<Uri>()
    // 👇 NUEVO: lista de temporales creados para poder borrarlos
    private val tmpFiles = mutableListOf<File>()

    private val picker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            selectedUris.clear()
            uris.forEach { uri ->
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
            selectedUris.addAll(uris)
            binding.recyclerPreview.adapter = ImagePreviewAdapter(requireContext(), selectedUris)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddProductBinding.inflate(inflater, container, false)
        return binding.root
    }

    private var editingProductId: Long? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.recyclerPreview.layoutManager = LinearLayoutManager(
            requireContext(), LinearLayoutManager.HORIZONTAL, false
        )
        binding.recyclerPreview.adapter = ImagePreviewAdapter(requireContext(), selectedUris)

        binding.btnPick.setOnClickListener { picker.launch(arrayOf("image/*")) }
        binding.btnSend.setOnClickListener {
            if (editingProductId != null) updateProduct(editingProductId!!) else createProduct()
        }
        binding.btnDelete.setOnClickListener {
            editingProductId?.let { confirmDelete(it) }
        }

        // Si viene argumento de edición, cargar datos
        val pid = arguments?.getLong(ARG_PRODUCT_ID, -1L)
        if (pid != null && pid > 0) {
            editingProductId = pid
            prefillProduct(pid)
            binding.btnSend.text = "Actualizar producto"
            binding.btnDelete.visibility = View.VISIBLE
        }
    }

    private var existingImages: MutableList<ProductImage> = mutableListOf()

    private fun prefillProduct(id: Long) {
        binding.txtStatus.text = "Cargando producto…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val p = productService.getById(id)
                binding.inputName.setText(p.nombre ?: "")
                binding.inputDesc.setText(p.descripcion ?: "")
                binding.inputPrice.setText(p.precio.toString())
                binding.inputCategory.setText(p.categoria ?: "")
                binding.checkboxOffer.isChecked = p.oferta
                binding.checkboxNew.isChecked = p.novedad
                binding.inputOfferPrice.setText(p.precioOferta?.toString() ?: "")
                existingImages = (p.imagenes ?: emptyList()).toMutableList()
                binding.txtStatus.text = "Editando producto #$id"
            } catch (e: Exception) {
                binding.txtStatus.text = e.message ?: "Error cargando"
            }
        }
    }

    private fun confirmDelete(id: Long) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Eliminar producto")
            .setMessage("¿Seguro que quieres borrar el producto #$id?")
            .setPositiveButton("Sí") { _, _ -> deleteProduct(id) }
            .setNegativeButton("No", null)
            .show()
    }

    private fun deleteProduct(id: Long) {
        binding.txtStatus.text = "Eliminando…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                productService.delete(id)
                CacheStore.invalidateProducts(requireContext())
                binding.txtStatus.text = "Producto eliminado"
                requireActivity().onBackPressedDispatcher.onBackPressed()
            } catch (e: Exception) {
                binding.txtStatus.text = e.message ?: "Error eliminando"
            }
        }
    }

    private fun createProduct() {
        val name         = binding.inputName.text?.toString()?.trim().orEmpty()
        val desc         = binding.inputDesc.text?.toString()?.trim().orEmpty()
        val price        = binding.inputPrice.text?.toString()?.toDoubleOrNull() ?: 0.0
        val categoria    = binding.inputCategory.text?.toString()?.trim().orEmpty()
        val oferta       = binding.checkboxOffer.isChecked
        val precioOferta = binding.inputOfferPrice.text?.toString()?.toDoubleOrNull()
        val novedad      = binding.checkboxNew.isChecked

        if (name.isBlank() || desc.isBlank() || categoria.isBlank() || price <= 0.0) {
            binding.txtStatus.text = "Completa los campos obligatorios"
            return
        }

        binding.txtStatus.text = "Creando producto…"

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val parts = selectedUris.mapIndexedNotNull { idx, uri -> buildImagePart(uri, idx) }
                android.util.Log.d(
                    "ADD",
                    "enviando ${parts.size} imágenes: " +
                            parts.joinToString { it.headers?.get("Content-Disposition") ?: "?" }
                )
                val created = productService.createWithImages(
                    name.toRb(),
                    desc.toRb(),
                    price.toString().toRb(),
                    categoria.toRb(),
                    oferta.toString().toRb(),
                    novedad.toString().toRb(),
                    precioOferta?.toString()?.toRb(),
                    if (parts.isEmpty()) null else parts
                )

                android.util.Log.d("ADD", "creado id=${created.id}")
                binding.txtStatus.text = "Producto creado (ID: ${created.id})"
                CacheStore.invalidateProducts(requireContext())
                clearForm()
            } catch (e: Exception) {
                binding.txtStatus.text = e.message ?: "Error"
            } finally {
                // 👇 NUEVO: siempre limpiar temporales
                cleanupTmp()
            }
        }
    }

    private fun updateProduct(id: Long) {
        val name         = binding.inputName.text?.toString()?.trim().orEmpty()
        val desc         = binding.inputDesc.text?.toString()?.trim().orEmpty()
        val price        = binding.inputPrice.text?.toString()?.toDoubleOrNull() ?: 0.0
        val categoria    = binding.inputCategory.text?.toString()?.trim().orEmpty()
        val oferta       = binding.checkboxOffer.isChecked
        val precioOferta = binding.inputOfferPrice.text?.toString()?.toDoubleOrNull()
        val novedad      = binding.checkboxNew.isChecked

        if (name.isBlank() || desc.isBlank() || categoria.isBlank() || price <= 0.0) {
            binding.txtStatus.text = "Completa los campos obligatorios"
            return
        }

        binding.txtStatus.text = "Actualizando producto…"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val updated = if (selectedUris.isEmpty()) {
                    productService.update(
                        id,
                        mapOf(
                            "name" to name,
                            "description" to desc,
                            "price" to price,
                            "category" to categoria,
                            "offer" to oferta,
                            "novelty" to novedad,
                            "offer_price" to precioOferta
                        )
                    )
                } else {
                    val uploadParts = selectedUris.mapIndexedNotNull { idx, uri -> buildUploadPart(uri, idx) }
                    val newImages = uploadService.uploadImages(uploadParts)
                    val combined = (existingImages + newImages)
                        .filter { it.url != null }
                        .distinctBy { it.url }
                        .take(3) // tope por consistencia

                    productService.update(
                        id,
                        mapOf(
                            "name" to name,
                            "description" to desc,
                            "price" to price,
                            "category" to categoria,
                            "offer" to oferta,
                            "novelty" to novedad,
                            "offer_price" to precioOferta,
                            "images" to combined
                        )
                    )
                }
                binding.txtStatus.text = "Producto actualizado (ID: ${updated.id})"
                CacheStore.invalidateProducts(requireContext())
            } catch (e: Exception) {
                binding.txtStatus.text = e.message ?: "Error"
            } finally {
                cleanupTmp()
            }
        }
    }

    // Crea la parte de cada imagen y registra el temporal en tmpFiles
    private fun buildImagePart(uri: Uri, idx: Int): MultipartBody.Part? {
        return try {
            val resolver = requireContext().contentResolver
            val mime = resolver.getType(uri) ?: "image/*"
            val input = resolver.openInputStream(uri) ?: return null
            val tmp = File.createTempFile("upload_", ".bin", requireContext().cacheDir)
            input.use { ins -> tmp.outputStream().use { outs -> ins.copyTo(outs) } }
            tmpFiles += tmp  // 👈 NUEVO: guardar para borrar luego

            val body = tmp.asRequestBody(mime.toMediaTypeOrNull())
            val filename = queryDisplayName(uri) ?: tmp.name
            MultipartBody.Part.createFormData("image_files[$idx]", filename, body)
        } catch (_: Exception) {
            null
        }
    }

    // Partes para UploadService (clave content[])
    private fun buildUploadPart(uri: Uri, idx: Int): MultipartBody.Part? {
        return try {
            val resolver = requireContext().contentResolver
            val mime = resolver.getType(uri) ?: "image/*"
            val input = resolver.openInputStream(uri) ?: return null
            val tmp = File.createTempFile("upload_", ".bin", requireContext().cacheDir)
            input.use { ins -> tmp.outputStream().use { outs -> ins.copyTo(outs) } }
            tmpFiles += tmp
            val body = tmp.asRequestBody(mime.toMediaTypeOrNull())
            val filename = queryDisplayName(uri) ?: tmp.name
            MultipartBody.Part.createFormData("content[]", filename, body)
        } catch (_: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        val cursor: Cursor? = requireContext().contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && index >= 0) name = it.getString(index)
        }
        return name
    }

    private fun String.toRb(): RequestBody =
        this.toRequestBody("text/plain".toMediaTypeOrNull())

    private fun clearForm() {
        binding.inputName.text?.clear()
        binding.inputDesc.text?.clear()
        binding.inputPrice.text?.clear()
        binding.inputCategory.text?.clear()
        binding.inputOfferPrice.text?.clear()
        binding.checkboxOffer.isChecked = false
        binding.checkboxNew.isChecked = false
        selectedUris.clear()
        binding.recyclerPreview.adapter = ImagePreviewAdapter(requireContext(), selectedUris)
    }

    // 👇 NUEVO: helper para limpiar temporales
    private fun cleanupTmp() {
        tmpFiles.forEach { runCatching { it.delete() } }
        tmpFiles.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        cleanupTmp() // por si salimos antes de completar el upload
        _binding = null
    }

    companion object {
        private const val ARG_PRODUCT_ID = "product_id"
        fun newInstance(productId: Long): AddProductFragment {
            val f = AddProductFragment()
            f.arguments = Bundle().apply { putLong(ARG_PRODUCT_ID, productId) }
            return f
        }
    }
}
