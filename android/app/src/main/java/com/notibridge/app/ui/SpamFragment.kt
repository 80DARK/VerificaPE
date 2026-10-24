package com.notibridge.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notibridge.app.PaymentFilter
import com.notibridge.app.R
import com.notibridge.app.Wallets
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.db.NotificationLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Sección SPAM · diagnóstico de descartes:
 *  - Muestra TODO lo que la app capturó pero NO clasificó como pago
 *    (promos, mensajes genéricos, y también las bloqueadas por política OTP).
 *  - Botón "Probar lector": publica una notificación de prueba; si aparece
 *    aquí, el servicio de notificaciones está escuchando correctamente
 *    (si no aparece, el problema es el permiso/enlace del sistema).
 *  - "✓ Es pago": si el filtro se equivocó, el dueño corrige a mano y el
 *    pago pasa al historial y a los ingresos.
 */
class SpamFragment : Fragment(R.layout.fragment_spam) {

    private lateinit var adapter: SpamAdapter

    private val permNotifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) probarLector()
            else Toast.makeText(requireContext(), R.string.no_notif_permission, Toast.LENGTH_LONG).show()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = SpamAdapter(
            onMarcarPago = { marcarComoPago(it) },
            onEliminar = { eliminar(it) }
        )
        view.findViewById<RecyclerView>(R.id.spam_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@SpamFragment.adapter
        }
        view.findViewById<TextView>(R.id.btn_probar_lector).setOnClickListener { probarLector() }

        // EN VIVO: el flujo de Room repinta solo cuando llegan notificaciones
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.get(appCtx).logs().nonPaymentsFlow(300).collectLatest { lista ->
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    adapter.submit(lista)
                    view?.findViewById<TextView>(R.id.spam_empty)?.visibility =
                        if (lista.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    // ─────────── Probar lector: notificación de autoprueba ───────────

    private fun probarLector() {
        val ctx = requireContext()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            permNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        publicarPrueba(ctx)
    }

    private fun publicarPrueba(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("vpe_test", "Prueba del lector", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = NotificationCompat.Builder(context, "vpe_test")
            .setSmallIcon(R.drawable.ic_home)
            .setContentTitle("VerificaPE · Prueba del lector")
            .setContentText("Prueba [VPE-TEST]: si ves esto en Spam, el lector funciona ✔")
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(9999, n)
            Toast.makeText(context, R.string.test_lector_toast, Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            Toast.makeText(context, R.string.no_notif_permission, Toast.LENGTH_LONG).show()
        }
    }

    // ─────────── Correcciones del usuario ───────────

    private fun marcarComoPago(item: NotificationLogEntity) {
        val title = item.title ?: ""
        val text = item.text ?: ""
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.get(appCtx)
            // Intenta el análisis completo; si falla, al menos extrae el monto
            val pago = try {
                PaymentFilter.analizar(title, text)
            } catch (e: Exception) {
                null
            } ?: PaymentFilter.extraerMonto(text)?.let {
                PaymentFilter.Pago(it, PaymentFilter.extraerPagador(text))
            }

            if (pago == null) {
                withContext(Dispatchers.Main) {
                    if (isAdded) Toast.makeText(requireContext(), R.string.spam_no_amount, Toast.LENGTH_LONG).show()
                }
                return@launch
            }
            db.logs().markAsPayment(item.id, pago.amount, pago.payerName, item.wallet ?: item.appName)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(requireContext(), R.string.spam_marked, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun eliminar(item: NotificationLogEntity) {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(appCtx).logs().deleteById(item.id)
            // el flujo en vivo repinta solo
        }
    }
}

/** Adaptador de la lista de descartes. */
class SpamAdapter(
    private val onMarcarPago: (NotificationLogEntity) -> Unit,
    private val onEliminar: (NotificationLogEntity) -> Unit
) : RecyclerView.Adapter<SpamAdapter.VH>() {

    private val items = mutableListOf<NotificationLogEntity>()
    private val fHora = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    fun submit(lista: List<NotificationLogEntity>) {
        items.clear()
        items.addAll(lista)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val badge: TextView = view.findViewById(R.id.spam_badge)
        val icon: android.widget.ImageView = view.findViewById(R.id.spam_icon)
        val titulo: TextView = view.findViewById(R.id.spam_titulo)
        val texto: TextView = view.findViewById(R.id.spam_texto)
        val hora: TextView = view.findViewById(R.id.spam_hora)
        val btnPago: TextView = view.findViewById(R.id.spam_btn_pago)
        val btnBorrar: ImageButton = view.findViewById(R.id.spam_btn_borrar)
    }

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

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_spam, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val wallet = Wallets.detect(item.appName)

        // Icono REAL de la app (del teléfono); si falla, letra de color
        val icono = iconoReal(holder.itemView.context, item.packageName)
        if (icono != null) {
            holder.badge.visibility = View.GONE
            holder.icon.visibility = View.VISIBLE
            holder.icon.setImageDrawable(icono)
        } else {
            holder.badge.visibility = View.VISIBLE
            holder.icon.visibility = View.GONE
            holder.badge.text = wallet.letter.toString()
            holder.badge.backgroundTintList = android.content.res.ColorStateList.valueOf(wallet.color)
        }
        holder.hora.text = fHora.format(Date(item.loggedAt))

        if (item.ignoredReason != null) {
            holder.titulo.setText(R.string.spam_otp_locked)
            holder.texto.visibility = View.GONE
            holder.btnPago.visibility = View.GONE
        } else {
            holder.titulo.text = item.title ?: item.appName
            holder.texto.text = item.text ?: ""
            holder.texto.visibility = if (item.text.isNullOrBlank()) View.GONE else View.VISIBLE
            holder.btnPago.visibility = if (item.text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        holder.btnPago.setOnClickListener { onMarcarPago(item) }
        holder.btnBorrar.setOnClickListener { onEliminar(item) }
    }
}
