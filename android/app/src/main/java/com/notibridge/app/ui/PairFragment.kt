package com.notibridge.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.notibridge.app.PairingInfo
import com.notibridge.app.PairingParser
import com.notibridge.app.R
import com.notibridge.app.SecureStore
import com.notibridge.app.Sender
import com.notibridge.app.WebhookClient
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.db.PairingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Emparejamiento con la web: se pueden conectar VARIOS paneles (multi-QR).
 * Cada notificación se envía cifrada a TODOS los canales emparejados.
 * El escáner funciona en VERTICAL (sigue la rotación del teléfono).
 */
class PairFragment : Fragment(R.layout.fragment_pair) {

    private lateinit var pairingsTitle: TextView
    private lateinit var pairEmpty: TextView
    private lateinit var pairingsContainer: LinearLayout

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onCodeScanned)
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startScan()
            else Toast.makeText(requireContext(), R.string.camera_needed, Toast.LENGTH_LONG).show()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        pairingsTitle = view.findViewById(R.id.pairings_title)
        pairEmpty = view.findViewById(R.id.pair_empty)
        pairingsContainer = view.findViewById(R.id.pairings_container)

        view.findViewById<Button>(R.id.btn_scan).setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) startScan()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }

        view.findViewById<Button>(R.id.btn_manual).setOnClickListener {
            val raw = view.findViewById<EditText>(R.id.edit_manual).text?.toString().orEmpty()
            if (raw.isNotBlank()) onCodeScanned(raw)
        }

        reload()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun startScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats("QR_CODE")
            setPrompt(getString(R.string.scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)   // escaneo en VERTICAL/libre
        }
        scanLauncher.launch(options)
    }

    private fun onCodeScanned(raw: String) {
        val info: PairingInfo = PairingParser.parse(raw)
            ?: run {
                Toast.makeText(requireContext(), R.string.invalid_qr, Toast.LENGTH_LONG).show()
                return
            }
        connect(info)
    }

    private fun connect(info: PairingInfo) {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.get(appCtx)
            val existing = db.pairing().getByChannel(info.channelId)
            if (existing != null) {
                db.pairing().updateServer(info.channelId, info.serverUrl, System.currentTimeMillis())
            } else {
                db.pairing().insert(
                    PairingEntity(
                        serverUrl = info.serverUrl,
                        channelId = info.channelId,
                        pairedAt = System.currentTimeMillis()
                    )
                )
            }
            SecureStore.savePairing(appCtx, info.channelId, info.keyB64, info.writeTokenB64)
            val reachable = WebhookClient.ping(info.serverUrl)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(
                    requireContext(),
                    if (existing != null) R.string.pair_updated else R.string.pair_ok,
                    Toast.LENGTH_LONG
                ).show()
                if (!reachable) {
                    Toast.makeText(requireContext(), R.string.pair_warn, Toast.LENGTH_LONG).show()
                }
                reload()
            }
        }
    }

    private fun reload() {
        val appCtx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val list = AppDatabase.get(appCtx).pairing().getAll()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                renderList(list)
            }
        }
    }

    private fun renderList(list: List<PairingEntity>) {
        pairingsTitle.text = getString(R.string.pairings_count, list.size)
        pairEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        pairingsContainer.removeAllViews()

        for (pairing in list) {
            val item = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_pairing, pairingsContainer, false) as MaterialCardView

            item.findViewById<TextView>(R.id.pair_server).text = pairing.serverUrl
            item.findViewById<TextView>(R.id.pair_channel).text =
                getString(R.string.pair_channel_fmt, pairing.channelId)

            item.findViewById<Button>(R.id.btn_pair_test).setOnClickListener {
                sendTest(pairing.channelId)
            }
            item.findViewById<Button>(R.id.btn_pair_delete).setOnClickListener {
                deletePairing(pairing.channelId)
            }
            pairingsContainer.addView(item)
        }
    }

    private fun sendTest(channelId: String) {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val payload = JSONObject().apply {
                put("v", 1)
                put("type", "test")
                put("device", Build.MODEL)
                put("app", "VerificaPE")
                put("title", getString(R.string.test_title))
                put("text", getString(R.string.test_text))
                put("time", System.currentTimeMillis())
            }
            val ok = Sender.sendTo(appCtx, channelId, payload)
            if (isAdded) {
                Toast.makeText(
                    requireContext(),
                    if (ok) R.string.test_ok else R.string.test_fail,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun deletePairing(channelId: String) {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.get(appCtx).pairing().deleteByChannel(channelId)
            SecureStore.removePairing(appCtx, channelId)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                Toast.makeText(requireContext(), R.string.pair_removed, Toast.LENGTH_SHORT).show()
                reload()
            }
        }
    }
}
