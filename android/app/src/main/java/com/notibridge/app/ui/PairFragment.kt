package com.notibridge.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.notibridge.app.db.AppDatabase
import com.notibridge.app.PairingInfo
import com.notibridge.app.PairingParser
import com.notibridge.app.R
import com.notibridge.app.SecureStore
import com.notibridge.app.Sender
import com.notibridge.app.WebhookClient
import com.notibridge.app.db.PairingEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Emparejamiento con la web escaneando el código QR (o pegando el código
 * manualmente). La clave AES y el token viajan DENTRO del QR, nunca por el
 * servidor: eso es lo que hace que el canal sea de extremo a extremo.
 */
class PairFragment : Fragment(R.layout.fragment_pair) {

    private lateinit var statusText: TextView
    private lateinit var detailsText: TextView
    private lateinit var btnTest: Button
    private lateinit var btnUnpair: Button

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(::onCodeScanned)
    }

    private val cameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startScan()
            else Toast.makeText(requireContext(), R.string.camera_needed, Toast.LENGTH_LONG).show()
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        statusText = view.findViewById(R.id.pair_status)
        detailsText = view.findViewById(R.id.pair_details)
        btnTest = view.findViewById(R.id.btn_send_test)
        btnUnpair = view.findViewById(R.id.btn_unpair)

        view.findViewById<Button>(R.id.btn_scan).setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    requireContext(), Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            ) startScan()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }

        btnTest.setOnClickListener { sendTest() }
        btnUnpair.setOnClickListener { unpair() }

        view.findViewById<Button>(R.id.btn_manual).setOnClickListener {
            val raw = view.findViewById<EditText>(R.id.edit_manual).text?.toString().orEmpty()
            if (raw.isNotBlank()) onCodeScanned(raw)
        }

        updateUi()
    }

    private fun startScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats("QR_CODE")
            setPrompt(getString(R.string.scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(true)
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
        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.get(appCtx).pairing().put(
                PairingEntity(
                    serverUrl = info.serverUrl,
                    channelId = info.channelId,
                    pairedAt = System.currentTimeMillis()
                )
            )
            SecureStore.savePairing(appCtx, info.keyB64, info.writeTokenB64)
            val reachable = WebhookClient.ping(info.serverUrl)
            if (isAdded) {
                Toast.makeText(
                    requireContext(),
                    if (reachable) R.string.pair_ok else R.string.pair_warn,
                    Toast.LENGTH_LONG
                ).show()
                updateUi()
            }
        }
    }

    private fun sendTest() {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val payload = JSONObject().apply {
                put("v", 1)
                put("type", "test")
                put("device", Build.MODEL)
                put("app", "NotiBridge")
                put("title", getString(R.string.test_title))
                put("text", getString(R.string.test_text))
                put("time", System.currentTimeMillis())
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

    private fun unpair() {
        val appCtx = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            AppDatabase.get(appCtx).pairing().clear()
            SecureStore.clear(appCtx)
            if (isAdded) {
                Toast.makeText(requireContext(), R.string.unpaired, Toast.LENGTH_SHORT).show()
                updateUi()
            }
        }
    }

    private fun updateUi() {
        val appCtx = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val pairing = AppDatabase.get(appCtx).pairing().get()
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (pairing != null) {
                    statusText.text = getString(R.string.paired)
                    detailsText.text = getString(
                        R.string.pair_details,
                        pairing.serverUrl,
                        pairing.channelId
                    )
                    btnTest.visibility = View.VISIBLE
                    btnUnpair.visibility = View.VISIBLE
                } else {
                    statusText.text = getString(R.string.not_paired)
                    detailsText.text = getString(R.string.not_paired_help)
                    btnTest.visibility = View.GONE
                    btnUnpair.visibility = View.GONE
                }
            }
        }
    }
}
