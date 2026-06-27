package com.vcam.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.vcam.R
import com.vcam.model.AppInfo

class AppListAdapter(
    private val onAppSelected: (AppInfo) -> Unit
) : ListAdapter<AppInfo, AppListAdapter.AppViewHolder>(AppDiffCallback()) {

    private var selectedPackage: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view, onAppSelected) { pkg ->
            val old = selectedPackage
            selectedPackage = pkg
            notifyItemChanged(currentList.indexOfFirst { it.packageName == old })
            notifyItemChanged(currentList.indexOfFirst { it.packageName == pkg })
        }
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(getItem(position), selectedPackage)
    }

    class AppViewHolder(
        itemView: View,
        private val onSelected: (AppInfo) -> Unit,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val ivIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
        private val tvName: TextView = itemView.findViewById(R.id.tv_app_name)
        private val tvPackage: TextView = itemView.findViewById(R.id.tv_package_name)
        private val tvCamera: TextView = itemView.findViewById(R.id.tv_camera_badge)
        private val cardView: CardView = itemView.findViewById(R.id.card_app)

        fun bind(app: AppInfo, selectedPackage: String?) {
            ivIcon.setImageDrawable(app.icon)
            tvName.text = app.appName
            tvPackage.text = app.packageName
            tvCamera.visibility = if (app.useCamera) View.VISIBLE else View.GONE

            val isSelected = app.packageName == selectedPackage
            cardView.setCardBackgroundColor(
                if (isSelected)
                    itemView.context.getColor(R.color.color_selected_card)
                else
                    itemView.context.getColor(R.color.color_card_bg)
            )

            itemView.setOnClickListener {
                onSelect(app.packageName)
                onSelected(app)
            }
        }
    }

    class AppDiffCallback : DiffUtil.ItemCallback<AppInfo>() {
        override fun areItemsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem.packageName == newItem.packageName

        override fun areContentsTheSame(oldItem: AppInfo, newItem: AppInfo): Boolean =
            oldItem == newItem
    }
}
