package cl.shoppersa.ui.adapter


import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import cl.shoppersa.databinding.ItemImagePreviewBinding
import coil.load


class ImagePreviewAdapter(
    private val context: Context,
    private val uris: List<Uri>
) : RecyclerView.Adapter<ImagePreviewAdapter.VH>() {


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemImagePreviewBinding.inflate(LayoutInflater.from(context), parent, false)
        return VH(binding)
    }


    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(uris[position])
    override fun getItemCount(): Int = uris.size


    inner class VH(private val b: ItemImagePreviewBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(uri: Uri) { b.img.load(uri) }
    }
}