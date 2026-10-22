package com.notibridge.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.Prefs
import com.notibridge.app.R
import com.notibridge.app.SecureStore
import kotlinx.coroutines.launch

/**
 * Ajustes generales. El filtro OTP NO es configurable: está SIEMPRE activo
 * por decisión de diseño (NotiBridge nunca reenvía códigos de un solo uso).
 */
class SettingsFragment : Fragment(R.layout.fragment_settings) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = Prefs.get(requireContext())
        val db = AppDatabase.get(requireContext())

        val swForward: MaterialSwitch = view.findViewById(R.id.sw_forward)
        val swOngoing: MaterialSwitch = view.findViewById(R.id.sw_ongoing)
        val swHistory: MaterialSwitch = view.findViewById(R.id.sw_history)

        swForward.isChecked = prefs.getBoolean(Prefs.FORWARD, true)
        swOngoing.isChecked = prefs.getBoolean(Prefs.IGNORE_ONGOING, true)
        swHistory.isChecked = prefs.getBoolean(Prefs.SAVE_HISTORY, true)

        swForward.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.FORWARD, checked).apply()
        }
        swOngoing.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.IGNORE_ONGOING, checked).apply()
        }
        swHistory.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(Prefs.SAVE_HISTORY, checked).apply()
        }

        view.findViewById<Button>(R.id.btn_battery).setOnClickListener {
            try {
                // Pide al sistema eximir a VerificaPE de la optimización de batería
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:" + requireContext().packageName)
                    )
                )
            } catch (e: Exception) {
                // Si el fabricante bloquea el diálogo, abrir la lista de apps
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        view.findViewById<Button>(R.id.btn_open_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        view.findViewById<Button>(R.id.btn_clear_local).setOnClickListener {
            val appCtx = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                db.logs().clear()
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.history_cleared, Toast.LENGTH_SHORT).show()
                }
            }
        }
        view.findViewById<Button>(R.id.btn_unpair_settings).setOnClickListener {
            val appCtx = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch {
                db.pairing().clear()
                SecureStore.clearAllPairings(appCtx)
                if (isAdded) {
                    Toast.makeText(requireContext(), R.string.unpaired, Toast.LENGTH_SHORT).show()
                }
            }
        }

        view.findViewById<TextView>(R.id.otp_fixed_note).text =
            getString(R.string.otp_fixed_note)
    }
}
