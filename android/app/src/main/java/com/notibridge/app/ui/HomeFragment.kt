package com.notibridge.app.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notibridge.app.GuardianService
import com.notibridge.app.NotiAccess
import com.notibridge.app.NotiListenerService
import com.notibridge.app.Prefs
import com.notibridge.app.R
import com.notibridge.app.Sender
import com.notibridge.app.Wallets
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.db.NotificationLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pantalla de inicio FINANCIERA (glassmorphism):
 *  - Tarjeta de INGRESOS DE HOY (total S/ y cantidad de pagos), EN VIVO
 *  - Historial de pagos AGRUPADO POR DÍA con monto, pagador, hora y billetera
 *  - Filtro por fechas (Hoy / 7 días / Mes / Todo) y EXPORTACIÓN CSV (Excel)
 *  - Botón circular para dormir/despertar la escucha (con reenganche forzado)
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var powerBtn: ImageButton
    private lateinit var listeningStatus: TextView
    private lateinit var incomeTotal: TextView
    private lateinit var incomeCount: TextView
    private lateinit var statusLine: TextView
    private lateinit var pagosAdapter: PagosAdapter

    private var filtroActual = FILTRO_HOY
    private var listaPagos: List<NotificationLogEntity> = emptyList()
    private var colectorPagos: Job? = null

    private val moneyFormat: NumberFormat =
        NumberFormat.getNumberInstance(Locale("es", "PE")).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 2
        }

    // ─────────────────── ciclo de vida ───────────────────

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        powerBtn = view.findViewById(R.id.btn_power)
        listeningStatus = view.findViewById(R.id.listening_status)
        incomeTotal = view.findViewById(R.id.income_total)
        incomeCount = view.findViewById(R.id.income_count)
        statusLine = view.findViewById(R.id.status_line)

        // Botón circular de encendido / dormir
        updateListeningUi()
        powerBtn.setOnClickListener {
            val prefs = Prefs.get(requireContext())
            val nowOn = !prefs.getBoolean(Prefs.LISTENING, true)
            prefs.edit().putBoolean(Prefs.LISTENING, nowOn).apply()
            updateListeningUi()
            // El Guardián acompaña la escucha: encendido = proceso vivo
            if (nowOn) {
                despertarServicio()
                GuardianService.start(requireContext())
            } else {
                GuardianService.stop(requireContext())
            }
            Toast.makeText(
                requireContext(),
                if (nowOn) R.string.listening_on_toast else R.string.listening_off_toast,
                Toast.LENGTH_LONG
            ).show()
        }

        // Historial de pagos agrupado por día
        pagosAdapter = PagosAdapter()
        view.findViewById<RecyclerView>(R.id.pagos_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = pagosAdapter
        }

        // Estado de acceso/notificaciones: tocar abre los ajustes del sistema
        statusLine.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }

        // Filtros de fecha
        listOf(
            R.id.chip_hoy to FILTRO_HOY,
            R.id.chip_7dias to FILTRO_7DIAS,
            R.id.chip_mes to FILTRO_MES,
            R.id.chip_todo to FILTRO_TODO
        ).forEach { (id, filtro) ->
            view.findViewById<TextView>(id).setOnClickListener {
                filtroActual = filtro
                pintarChips()
                observarPagos()
            }
        }
        pintarChips()

        view.findViewById<TextView>(R.id.btn_export).setOnClickListener { exportarCsv() }
        view.findViewById<TextView>(R.id.btn_test_pago).setOnClickListener { enviarPagoPrueba() }

        // Datos EN VIVO (Room Flow): llegan sin recargar la pantalla
        observarIngresos()
        observarPagos()
    }

    override fun onResume() {
        super.onResume()
        updateListeningUi()
        cargarStatus()
    }

    // ─────────────────── despertar con reenganche ───────────────────

    /**
     * FIX del bug "duermo y despierto y ya no escucha": al despertar forzamos
     * el reenganche del lector (componente off/on + requestRebind), que
     * cubre también el desenganche silencioso tras actualizar la app.
     */
    private fun despertarServicio() {
        com.notibridge.app.ListenerKick.kick(requireContext())
    }

    private fun updateListeningUi() {
        val on = Prefs.get(requireContext()).getBoolean(Prefs.LISTENING, true)
        powerBtn.isActivated = on
        listeningStatus.text = getString(
            if (on) R.string.listening_on_desc else R.string.listening_off_desc
        )
    }

    // ─────────────────── datos en vivo (Room Flow) ───────────────────

    private fun inicioDeHoy(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun rangoFiltro(): Long = when (filtroActual) {
        FILTRO_HOY -> inicioDeHoy()
        FILTRO_7DIAS -> System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        FILTRO_MES -> {
            val cal = Calendar.getInstance()
            cal.set(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
            cal.timeInMillis
        }
        else -> 0L
    }

    /** Ingresos de hoy (total + cantidad), en vivo. */
    private fun observarIngresos() {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.get(appCtx)
            launch {
                db.logs().incomeSinceFlow(inicioDeHoy()).collect { total ->
                    withContext(Dispatchers.Main) {
                        if (isAdded) incomeTotal.text = "S/ ${moneyFormat.format(total)}"
                    }
                }
            }
            launch {
                db.logs().paymentsCountSinceFlow(inicioDeHoy()).collect { n ->
                    withContext(Dispatchers.Main) {
                        if (isAdded) incomeCount.text = getString(R.string.income_count_fmt, n)
                    }
                }
            }
        }
    }

    /** Lista de pagos del filtro activo, en vivo (Room invalida el flujo). */
    private fun observarPagos() {
        colectorPagos?.cancel()
        val appCtx = requireContext().applicationContext
        colectorPagos = viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.get(appCtx).logs().paymentsSinceFlow(rangoFiltro(), 1000)
                .collectLatest { pagos ->
                    listaPagos = pagos
                    withContext(Dispatchers.Main) {
                        if (!isAdded) return@withContext
                        pagosAdapter.submit(pagos)
                        view?.findViewById<TextView>(R.id.pagos_empty)?.visibility =
                            if (pagos.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
        }
    }

    /** Línea de estado (acceso, canales, OTP): consulta puntual al reanudar. */
    private fun cargarStatus() {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.get(appCtx)
            val canales = db.pairing().count()
            val otp = db.logs().countIgnored()
            val acceso = NotiAccess.isEnabled(appCtx)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                statusLine.text = buildString {
                    append(if (acceso) "✓ Acceso" else "⚠ Sin acceso")
                    append(" · ")
                    append(getString(R.string.canal_count, canales))
                    append(" · ")
                    append(getString(R.string.otp_blocked_count, otp))
                }
            }
        }
    }

    private fun pintarChips() {
        listOf(
            R.id.chip_hoy to FILTRO_HOY,
            R.id.chip_7dias to FILTRO_7DIAS,
            R.id.chip_mes to FILTRO_MES,
            R.id.chip_todo to FILTRO_TODO
        ).forEach { (id, filtro) ->
            view?.findViewById<TextView>(id)?.isActivated = (filtro == filtroActual)
        }
    }

    // ─────────────────── CSV (Excel) ───────────────────

    private fun exportarCsv() {
        if (listaPagos.isEmpty()) {
            Toast.makeText(requireContext(), R.string.export_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val dir = File(appCtx.cacheDir, "exports").apply { mkdirs() }
                val archivo = File(
                    dir,
                    "verificape_pagos_${SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())}.csv"
                )
                archivo.writeText(generarCsv(), Charsets.UTF_8)
                val uri = FileProvider.getUriForFile(
                    appCtx, "${appCtx.packageName}.fileprovider", archivo
                )
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.export_title)))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) Toast.makeText(requireContext(), R.string.export_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun generarCsv(): String {
        val fFecha = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        val fHora = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("Fecha,Hora,Billetera,Pagador,Monto,App\n")
        for (p in listaPagos) {
            sb.append('"').append(fFecha.format(Date(p.loggedAt))).append("\",")
            sb.append('"').append(fHora.format(Date(p.loggedAt))).append("\",")
            sb.append('"').append(esc(p.wallet ?: p.appName)).append("\",")
            sb.append('"').append(esc(p.payerName ?: "")).append("\",")
            sb.append(moneyFormat.format(p.amount ?: 0.0)).append(",")
            sb.append('"').append(esc(p.appName)).append("\"\n")
        }
        return sb.toString()
    }

    private fun esc(s: String) = s.replace("\"", "\"\"")

    // ─────────────────── pago de prueba ───────────────────

    private fun enviarPagoPrueba() {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val payload = JSONObject().apply {
                put("v", 2)
                put("type", "payment")
                put("device", android.os.Build.MODEL)
                put("app", "VerificaPE")
                put("title", "Prueba")
                put("text", "Pago de prueba desde la app VerificaPE")
                put("time", System.currentTimeMillis())
                put("amount", 1.00)
                put("payer", "VerificaPE")
                put("wallet", "Test")
            }
            val ok = Sender.send(appCtx, payload)
            if (isAdded) {
                Toast.makeText(
                    requireContext(),
                    if (ok) R.string.test_ok else R.string.test_fail,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    companion object {
        private const val FILTRO_HOY = 0
        private const val FILTRO_7DIAS = 1
        private const val FILTRO_MES = 2
        private const val FILTRO_TODO = 3
    }
}

/* ══════════════════ Historial de pagos por días ══════════════════ */

sealed class FilaPago {
    data class Dia(val titulo: String, val total: Double) : FilaPago()
    data class Pago(val pago: NotificationLogEntity, val colorWallet: Int, val letra: Char) : FilaPago()
}

/**
 * Adaptador con DOS ViewHolders separados (encabezado de día y fila de pago):
 * el crash v1.3.1 ocurría porque un solo ViewHolder buscaba los IDs de la fila
 * de pago dentro del layout del encabezado (findViewById devolvía null).
 */
class PagosAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val filas = mutableListOf<FilaPago>()
    private val fHora = SimpleDateFormat("HH:mm", Locale.getDefault())

    fun submit(pagos: List<NotificationLogEntity>) {
        filas.clear()
        val fDiaKey = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val porDia = LinkedHashMap<String, MutableList<FilaPago.Pago>>()
        val etiquetas = HashMap<String, String>()
        for (p in pagos) {
            val key = fDiaKey.format(Date(p.loggedAt))
            val w = Wallets.detect(p.wallet ?: p.appName)
            porDia.getOrPut(key) { mutableListOf() }
                .add(FilaPago.Pago(p, w.color, w.letter))
            etiquetas[key] = etiquetaDia(p.loggedAt)
        }
        for ((key, items) in porDia) {
            val total = items.sumOf { it.pago.amount ?: 0.0 }
            filas.add(FilaPago.Dia(etiquetas[key] ?: key, total))
            filas.addAll(items)
        }
        notifyDataSetChanged()
    }

    private fun etiquetaDia(ms: Long): String {
        val cal = Calendar.getInstance()
        val hoy = Calendar.getInstance()
        cal.timeInMillis = ms
        return when {
            cal.get(Calendar.YEAR) == hoy.get(Calendar.YEAR) &&
                cal.get(Calendar.DAY_OF_YEAR) == hoy.get(Calendar.DAY_OF_YEAR) -> "Hoy"
            hoy.get(Calendar.DAY_OF_YEAR) - cal.get(Calendar.DAY_OF_YEAR) == 1 &&
                hoy.get(Calendar.YEAR) == cal.get(Calendar.YEAR) -> "Ayer"
            else -> SimpleDateFormat("EEEE d 'de' MMMM", Locale.getDefault()).format(Date(ms))
                .replaceFirstChar { it.uppercase(Locale.getDefault()) }
        }
    }

    /** Encabezado de día. */
    class DiaVH(view: View) : RecyclerView.ViewHolder(view) {
        val titulo: TextView = view.findViewById(R.id.dia_titulo)
        val total: TextView = view.findViewById(R.id.dia_total)
    }

    /** Fila de pago. */
    class PagoVH(view: View) : RecyclerView.ViewHolder(view) {
        val badge: TextView = view.findViewById(R.id.pago_badge)
        val icon: android.widget.ImageView = view.findViewById(R.id.pago_icon)
        val pagador: TextView = view.findViewById(R.id.pago_pagador)
        val hora: TextView = view.findViewById(R.id.pago_hora)
        val monto: TextView = view.findViewById(R.id.pago_monto)
        val walletName: TextView = view.findViewById(R.id.pago_wallet)
    }

    /** Caché de iconos reales por paquete (los logos verdaderos del teléfono). */
    private val iconCache = HashMap<String, android.graphics.drawable.Drawable?>()

    private fun iconoReal(context: android.content.Context, pkg: String?): android.graphics.drawable.Drawable? {
        if (pkg.isNullOrBlank()) return null
        if (iconCache.containsKey(pkg)) return iconCache[pkg]
        val d = try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) { null }
        iconCache[pkg] = d
        return d
    }

    override fun getItemViewType(position: Int): Int =
        if (filas[position] is FilaPago.Dia) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == 0) {
            DiaVH(inflater.inflate(R.layout.item_dia, parent, false))
        } else {
            PagoVH(inflater.inflate(R.layout.item_pago, parent, false))
        }
    }

    override fun getItemCount(): Int = filas.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val money = NumberFormat.getNumberInstance(Locale("es", "PE")).apply {
            maximumFractionDigits = 2; minimumFractionDigits = 2
        }
        when {
            holder is DiaVH && filas[position] is FilaPago.Dia -> {
                val fila = filas[position] as FilaPago.Dia
                holder.titulo.text = fila.titulo
                holder.total.text = "S/ ${money.format(fila.total)}"
            }
            holder is PagoVH && filas[position] is FilaPago.Pago -> {
                val fila = filas[position] as FilaPago.Pago
                holder.pagador.text = fila.pago.payerName
                    ?: holder.itemView.context.getString(R.string.pago_sin_nombre)
                holder.hora.text = fHora.format(Date(fila.pago.loggedAt))
                holder.monto.text = "S/ ${money.format(fila.pago.amount ?: 0.0)}"
                holder.walletName.text = fila.pago.wallet ?: fila.pago.appName

                // Icono REAL de la app (del teléfono); si falla, letra de color
                val icono = iconoReal(holder.itemView.context, fila.pago.packageName)
                if (icono != null) {
                    holder.badge.visibility = View.GONE
                    holder.icon.visibility = View.VISIBLE
                    holder.icon.setImageDrawable(icono)
                } else {
                    holder.badge.visibility = View.VISIBLE
                    holder.icon.visibility = View.GONE
                    holder.badge.text = fila.letra.toString()
                    holder.badge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(fila.colorWallet)
                }
            }
        }
    }
}
