package tw.bard.carradar.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tw.bard.carradar.core.MileageRecord
import tw.bard.carradar.data.MileageStore
import tw.bard.carradar.databinding.ActivityMileageBinding
import tw.bard.carradar.databinding.ItemMileageBinding

class MileageActivity : BaseLiquidActivity() {

    private lateinit var binding: ActivityMileageBinding
    private lateinit var store: MileageStore
    private var records: List<MileageRecord> = emptyList()
    private lateinit var adapter: MileageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMileageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applySafeInsets(binding.rootContainer)

        store = MileageStore(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnClearAll.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("清空所有里程紀錄？")
                .setPositiveButton("確定") { _, _ ->
                    store.clearAll()
                    reload()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        adapter = MileageAdapter()
        binding.recyclerMileage.layoutManager = LinearLayoutManager(this)
        binding.recyclerMileage.adapter = adapter
        reload()
    }

    private fun reload() {
        records = store.listRecords()
        binding.tvTotalKm.text = "${"%.1f".format(store.totalKm())} km"
        binding.tvEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    inner class MileageAdapter : RecyclerView.Adapter<MileageAdapter.Holder>() {
        inner class Holder(val b: ItemMileageBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val b = ItemMileageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return Holder(b)
        }

        override fun getItemCount(): Int = records.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val r = records[position]
            holder.b.tvDate.text = r.date
            holder.b.tvTimeRange.text = "${r.startTime} ~ ${r.endTime}"
            holder.b.tvDistance.text = "${"%.2f".format(r.distanceKm)} km"
            holder.b.btnDelete.setOnClickListener {
                store.deleteRecord(r.id)
                reload()
            }
        }
    }
}
