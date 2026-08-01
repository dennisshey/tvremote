package com.sidephone.atvremote.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.sidephone.atvremote.databinding.ItemDeviceBinding
import com.sidephone.atvremote.remote.AppleTvDevice

/** Simple list of discovered/paired Apple TVs. */
class DeviceAdapter(
    private val onClick: (AppleTvDevice) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder>() {

    data class Row(val device: AppleTvDevice, val paired: Boolean)

    private val rows = mutableListOf<Row>()

    /** Replace the list, de-duplicating by device id while preserving order. */
    fun submit(newRows: List<Row>) {
        rows.clear()
        rows.addAll(newRows.distinctBy { it.device.id })
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DeviceViewHolder {
        val binding = ItemDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DeviceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: DeviceViewHolder, position: Int) {
        holder.bind(rows[position])
    }

    override fun getItemCount(): Int = rows.size

    inner class DeviceViewHolder(private val binding: ItemDeviceBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row) {
            binding.deviceName.text = row.device.name
            binding.deviceHost.text = row.device.id
            binding.pairedBadge.visibility = if (row.paired) android.view.View.VISIBLE else android.view.View.GONE
            binding.root.setOnClickListener { onClick(row.device) }
        }
    }
}
