package com.notibridge.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.R
import com.notibridge.app.db.NotificationLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Historial LOCAL de la app (su propia DB): todo lo capturado, incluidos
 * los bloqueos del filtro OTP (que se registran sin contenido).
 */
class HistoryFragment : Fragment(R.layout.fragment_history) {

    private lateinit var adapter: NotificationAdapter
    private var allLogs: List<NotificationLogEntity> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = NotificationAdapter()
        view.findViewById<RecyclerView>(R.id.history_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@HistoryFragment.adapter
        }
        view.findViewById<EditText>(R.id.history_search).addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            }
        )
        view.findViewById<Button>(R.id.btn_clear_history).setOnClickListener {
            val appCtx = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                AppDatabase.get(appCtx).logs().clear()
                if (isAdded) load()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    private fun load() {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val logs = AppDatabase.get(appCtx).logs().recent(500)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                allLogs = logs
                applyFilter(view?.findViewById<EditText>(R.id.history_search)?.text?.toString().orEmpty())
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allLogs
        else allLogs.filter {
            it.appName.lowercase().contains(q) ||
            (it.title ?: "").lowercase().contains(q) ||
            (it.text ?: "").lowercase().contains(q)
        }
        adapter.submit(filtered)
    }
}

/** Adaptador compartido para listas de notificaciones (inicio e historial). */
class NotificationAdapter : RecyclerView.Adapter<NotificationAdapter.VH>() {

    private val items = mutableListOf<NotificationLogEntity>()
    private val timeFormat = SimpleDateFormat("dd/MM/yy HH:mm:ss", Locale.getDefault())

    fun submit(list: List<NotificationLogEntity>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val app: TextView = view.findViewById(R.id.notif_app)
        val time: TextView = view.findViewById(R.id.notif_time)
        val title: TextView = view.findViewById(R.id.notif_title)
        val text: TextView = view.findViewById(R.id.notif_text)
        val status: TextView = view.findViewById(R.id.notif_status)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_notification, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val ctx = holder.itemView.context
        holder.app.text = item.appName
        holder.time.text = timeFormat.format(Date(item.postTime))

        if (item.ignoredReason != null) {
            holder.title.text = ctx.getString(R.string.blocked_title)
            holder.text.text = ctx.getString(
                if (item.ignoredReason == "APP_SMS") R.string.blocked_sms_app
                else R.string.blocked_otp
            )
            holder.status.text = ctx.getString(R.string.status_otp)
        } else {
            holder.title.text = item.title ?: ""
            holder.text.text = item.text ?: ""
            holder.status.text = when {
                item.sent -> ctx.getString(R.string.status_sent)
                else -> ctx.getString(R.string.status_local)
            }
        }
    }
}
