package com.notibridge.app

/**
 * Identifica la billetera/banco a partir del nombre de la app y le asigna
 * un color de marca (para la insignia del historial de pagos).
 */
object Wallets {

    data class WalletInfo(
        val name: String,
        val color: Int,
        val letter: Char
    )

    fun detect(appName: String): WalletInfo {
        val n = appName.lowercase()
        return when {
            n.contains("yape") -> WalletInfo("Yape", 0xFF7A29E9.toInt(), 'Y')
            n.contains("plin") -> WalletInfo("Plin", 0xFF00A9E0.toInt(), 'P')
            n.contains("sip") -> WalletInfo("SIP", 0xFF00B04F.toInt(), 'S')
            n.contains("bim") -> WalletInfo("bIM", 0xFF00A0B8.toInt(), 'B')
            n.contains("interbank") -> WalletInfo("Interbank", 0xFF0B6EC9.toInt(), 'I')
            n.contains("bcp") -> WalletInfo("BCP", 0xFF002878.toInt(), 'B')
            n.contains("bbva") -> WalletInfo("BBVA", 0xFF004481.toInt(), 'B')
            n.contains("scotiabank") -> WalletInfo("Scotiabank", 0xFFEC1111.toInt(), 'S')
            n.contains("caja") || n.contains("caja arequipa") || n.contains("caja huancayo") ->
                WalletInfo(appName.take(12), 0xFF6D28D9.toInt(), 'C')
            n.contains("mibanco") -> WalletInfo("MiBanco", 0xFFD51317.toInt(), 'M')
            n.contains("pichincha") -> WalletInfo("Pichincha", 0xFFE30613.toInt(), 'P')
            n.contains("ripley") -> WalletInfo("Ripley", 0xFF5C2D91.toInt(), 'R')
            n.contains("santander") -> WalletInfo("Santander", 0xFFEA1D25.toInt(), 'S')
            else -> WalletInfo(
                appName.take(12).ifBlank { "App" },
                colorFromName(appName),
                appName.firstOrNull { !it.isWhitespace() } ?: '•'
            )
        }
    }

    /** Color determinista para apps desconocidas. */
    private fun colorFromName(name: String): Int {
        var h = 0
        for (c in name) h = (h * 31 + c.code) % 360
        val sat = 0.45f
        val light = 0.45f
        val alpha = (light * 255).toInt()
        // HSV -> ARGB sin android.graphics (usable en cualquier hilo)
        val c = hsvColor(h.toFloat(), sat, light)
        return (alpha shl 24) or c
    }

    private fun hsvColor(h: Float, s: Float, v: Float): Int {
        val i = (h / 60f).toInt() % 6
        val f = h / 60f - (h / 60f).toInt()
        val p = v * (1 - s)
        val q = v * (1 - s * f)
        val t = v * (1 - s * (1 - f))
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }
}
