package com.notibridge.app.ui

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.materialswitch.MaterialSwitch
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.R
import com.notibridge.app.db.MonitoredAppEntity
import android.provider.Telephony
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Lista de TODAS las apps instaladas con un interruptor para decidir
 * app por app si se leen sus notificaciones. Por defecto TODO está
 * desactivado. La app de SMS aparece bloqueada (filtro OTP permanente).
 */
class AppsFragment : Fragment(R.layout.fragment_apps) {

    private lateinit var adapter: AppsAdapter
    private var allRows: List<AppRow> = emptyList()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = AppsAdapter { pkg, enabled ->
            val appCtx = requireContext().applicationContext
            lifecycleScope.launch {
                AppDatabase.get(appCtx).apps().setEnabled(pkg, enabled)
            }
        }
        view.findViewById<RecyclerView>(R.id.apps_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AppsFragment.adapter
        }
        view.findViewById<EditText>(R.id.app_search).addTextChangedListener(
            object : TextWatcher {
                override fun afterTextChanged(s: Editable?) { applyFilter(s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            }
        )
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        val appCtx = requireContext().applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = appCtx.packageManager
            val smsPkg = try { Telephony.Sms.getDefaultSmsPackage(appCtx) } catch (e: Exception) { null }
            val db = AppDatabase.get(appCtx)

            // 1) Sincronizar la lista con las apps realmente instaladas
            val installed = pm.getInstalledApplications(0)
                .filter { it.packageName != appCtx.packageName }
                .map {
                    MonitoredAppEntity(
                        packageName = it.packageName,
                        appName = it.loadLabel(pm).toString(),
                        enabled = false,
                        smsProvider = it.packageName == smsPkg
                    )
                }
            if (installed.isNotEmpty()) {
                db.runInTransaction {
                    db.apps().pruneNotIn(installed.map { it.packageName })
                    val enabledMap = db.apps().getAllSync().associate { it.packageName to it.enabled }
                    db.apps().upsertAllSync(installed.map { it.copy(enabled = enabledMap[it.packageName] ?: false) })
                }
            }

            // 2) Leer la lista definitiva (conservando los interruptores)
            val rows = db.apps().getAllSync()
                .sortedBy { it.appName.lowercase() }
                .map { entity ->
                    val icon: Drawable? = try {
                        pm.getApplicationIcon(entity.packageName)
                    } catch (e: Exception) { null }
                    AppRow(entity.packageName, entity.appName, icon, entity.smsProvider, entity.enabled)
                }

            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                allRows = rows
                applyFilter(view?.findViewById<EditText>(R.id.app_search)?.text?.toString().orEmpty())
            }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        val filtered = if (q.isEmpty()) allRows
        else allRows.filter { it.name.lowercase().contains(q) || it.pkg.lowercase().contains(q) }
        adapter.submit(filtered)
    }
}

data class AppRow(
    val pkg: String,
    val name: String,
    val icon: Drawable?,
    val smsProvider: Boolean,
    val enabled: Boolean
)

class AppsAdapter(
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<AppsAdapter.VH>() {

    private val items = mutableListOf<AppRow>()

    fun submit(rows: List<AppRow>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.app_icon)
        val name: TextView = view.findViewById(R.id.app_name)
        val pkg: TextView = view.findViewById(R.id.app_pkg)
        val badge: TextView = view.findViewById(R.id.app_badge_sms)
        val toggle: MaterialSwitch = view.findViewById(R.id.app_switch)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        holder.name.text = row.name
        holder.pkg.text = row.pkg
        if (row.icon != null) holder.icon.setImageDrawable(row.icon)
        else holder.icon.setImageResource(android.R.drawable.ic_dialog_info)

        holder.toggle.setOnCheckedChangeListener(null)
        if (row.smsProvider) {
            // App de SMS: bloqueada siempre (filtro OTP)
            holder.toggle.isChecked = false
            holder.toggle.isEnabled = false
            holder.badge.visibility = View.VISIBLE
        } else {
            holder.toggle.isEnabled = true
            holder.toggle.isChecked = row.enabled
            holder.badge.visibility = View.GONE
            holder.toggle.setOnCheckedChangeListener { _, checked -> onToggle(row.pkg, checked) }
        }
    }
}
