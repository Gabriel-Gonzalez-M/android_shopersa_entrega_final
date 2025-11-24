package cl.shoppersa.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cl.shoppersa.R
import cl.shoppersa.databinding.ItemProductGridBinding
import cl.shoppersa.model.Product
import coil.load
import cl.shoppersa.util.Money
import android.view.View

class ProductAdapter(
    private val onClick: (Product) -> Unit,
    private val showAddToCart: Boolean = true
) : RecyclerView.Adapter<ProductAdapter.VH>() {

    private val items = mutableListOf<Product>()

    fun submit(list: List<Product>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    fun append(list: List<Product>) {
        val start = items.size
        items.addAll(list)
        notifyItemRangeInserted(start, list.size)
    }

    fun clear() {
        items.clear()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemProductGridBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    inner class VH(private val b: ItemProductGridBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(p: Product) {
            // Nombre
            b.txtName.text = p.nombre.orEmpty()

            // Imagen principal
            val imageUrl = p.imagenes?.firstOrNull()?.url
            b.img.load(imageUrl) {
                crossfade(true)
                placeholder(R.drawable.placeholder_rect)
                error(R.drawable.placeholder_rect)
            }

            // Precio único (usa oferta válida o el normal)
            val finalPrice = if (p.oferta && (p.precioOferta ?: 0.0) > 0.0) p.precioOferta!! else p.precio
            b.txtPrice.text = Money.formatCLP(finalPrice)

            // Badges: mostrar solo si aplica
            b.badgeOffer.visibility = if (p.oferta) View.VISIBLE else View.GONE
            b.badgeNew.visibility   = if (p.novedad) View.VISIBLE else View.GONE

            // Click al detalle
            b.root.setOnClickListener { onClick(p) }
        }
    }
}
