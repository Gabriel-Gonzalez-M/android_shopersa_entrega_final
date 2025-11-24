package cl.shoppersa.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import coil.load
import cl.shoppersa.R
import cl.shoppersa.databinding.ItemImageSliderBinding
import cl.shoppersa.model.ProductImage

class ImageSliderAdapter(private var items: List<ProductImage>) :
    RecyclerView.Adapter<ImageSliderAdapter.VH>() {

    fun submit(newItems: List<ProductImage>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemImageSliderBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        // Si no hay items, position==0 y mostramos placeholder
        val url = if (items.isEmpty()) null else items[position].url
        holder.bind(url)
    }

    override fun getItemCount(): Int = if (items.isEmpty()) 1 else items.size

    inner class VH(private val b: ItemImageSliderBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(url: String?) {
            if (url.isNullOrBlank()) {
                b.img.load(R.drawable.placeholder_rect)
            } else {
                b.img.load(url) {
                    placeholder(R.drawable.placeholder_rect)
                    error(R.drawable.placeholder_rect)
                    crossfade(true)
                }
            }
        }
    }
}
