package com.notibridge.app.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.MainActivity
import com.notibridge.app.NotiAccess
import com.notibridge.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pantalla de inicio: estado del servicio, estado del emparejamiento,
 * contadores y las notificaciones más recientes.
 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private lateinit var serviceStatus: TextView
    private lateinit var pairStatus: TextView
    private lateinit var statToday: TextView
    private lateinit var statSent: TextView
    private lateinit var statOtp: TextView
    private lateinit var recentAdapter: NotificationAdapter
    private lateinit var emptyRecent: TextView

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        serviceStatus = view.findViewById(R.id.service_status)
        pairStatus = view.findViewById(R.id.pair_status)
        statToday = view.findViewById(R.id.stat_today)
        statSent = view.findViewById(R.id.stat_sent)
        statOtp = view.findViewById(R.id.stat_otp)

        recentAdapter = NotificationAdapter()
        emptyRecent = view.findViewById(R.id.empty_recent)
        view.findViewById<RecyclerView>(R.id.recent_list).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = recentAdapter
        }

        view.findViewById<Button>(R.id.btn_grant_access).setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        view.findViewById<Button>(R.id.btn_go_pair).setOnClickListener {
            (requireActivity() as MainActivity).navigateTo(R.id.nav_pair)
        }
        view.findViewById<Button>(R.id.btn_go_history).setOnClickListener {
            (requireActivity() as MainActivity).navigateTo(R.id.nav_history)
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val appCtx = requireContext().applicationContext
        val serviceOk = NotiAccess.isEnabled(appCtx)
        serviceStatus.text = if (serviceOk) {
            getString(R.string.service_on)
        } else {
            getString(R.string.service_off)
        }
        serviceStatus.setTextColor(
            resources.getColor(
                if (serviceOk) android.R.color.holo_green_dark
                else android.R.color.holo_orange_dark,
                requireContext().theme
            )
        )

        viewLifecycleOwner.lifecycleScope.launch {
            val db = AppDatabase.get(appCtx)
            val startOfDay = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            val channelCount = db.pairing().count()
            val today = db.logs().countSince(startOfDay)
            val sent = db.logs().countSent()
            val otp = db.logs().countIgnored()
            val recent = db.logs().recent(5)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                pairStatus.text = if (channelCount > 0) {
                    getString(R.string.paired_channels, channelCount)
                } else {
                    getString(R.string.not_paired)
                }
                statToday.text = today.toString()
                statSent.text = sent.toString()
                statOtp.text = otp.toString()
                recentAdapter.submit(recent)
                emptyRecent.visibility = if (recent.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
