package com.notibridge.app.ui

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.notibridge.app.ExcelExporter
import com.notibridge.app.R
import com.notibridge.app.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Diálogo para EXPORTAR A EXCEL con rango de fechas elegible:
 * Hoja 1 con cada pago (fecha, hora, nombre, monto, billetera, app) y
 * Hoja 2 con el resumen por billetera + total general. Con colores.
 */
class ExportarDialog : BottomSheetDialogFragment() {

    private var desde: LocalDate = LocalDate.now().withDayOfMonth(1)
    private var hasta: LocalDate = LocalDate.now()
    private lateinit var tvDesde: android.widget.TextView
    private lateinit var tvHasta: android.widget.TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.dialog_exportar, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        tvDesde = view.findViewById(R.id.exp_desde)
        tvHasta = view.findViewById(R.id.exp_hasta)
        pintarFechas()

        tvDesde.setOnClickListener { elegirFecha(desde) { desde = it; pintarFechas() } }
        tvHasta.setOnClickListener { elegirFecha(hasta) { hasta = it; pintarFechas() } }

        view.findViewById<android.widget.Button>(R.id.exp_exportar).setOnClickListener {
            if (desde.isAfter(hasta)) {
                Toast.makeText(requireContext(), R.string.exp_rango_mal, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            exportar()
        }
    }

    private fun pintarFechas() {
        val f = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
        tvDesde.text = getString(R.string.exp_desde_fmt, f.format(desdeMs()))
        tvHasta.text = getString(R.string.exp_hasta_fmt, f.format(hastaMs()))
    }

    private fun elegirFecha(actual: LocalDate, alElegir: (LocalDate) -> Unit) {
        val cal = Calendar.getInstance()
        cal.time = java.util.Date.from(
            actual.atStartOfDay(ZoneId.systemDefault()).toInstant()
        )
        DatePickerDialog(
            requireContext(),
            { _, y, m, d -> alElegir(LocalDate.of(y, m + 1, d)) },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun desdeMs(): Long =
        java.util.Date.from(desde.atStartOfDay(ZoneId.systemDefault()).toInstant()).time

    private fun hastaMs(): Long =
        java.util.Date.from(hasta.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant()).time - 1

    private fun exportar() {
        val appCtx = requireContext().applicationContext
        val desdeMs = desdeMs()
        val hastaMs = hastaMs()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pagos = AppDatabase.get(appCtx).logs().paymentsEntre(desdeMs, hastaMs, 5000)
                if (pagos.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (isAdded) Toast.makeText(requireContext(), R.string.export_empty, Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val archivo = ExcelExporter.generar(appCtx, pagos, desdeMs, hastaMs)
                val uri = FileProvider.getUriForFile(
                    appCtx, "${appCtx.packageName}.fileprovider", archivo
                )
                withContext(Dispatchers.Main) {
                    if (!isAdded) return@withContext
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.export_title)))
                    dismiss()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAdded) Toast.makeText(requireContext(), R.string.export_error, Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
