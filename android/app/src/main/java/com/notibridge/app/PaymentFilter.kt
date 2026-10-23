package com.notibridge.app

/**
 * Detector de PAGOS REALES dentro de las notificaciones de billeteras
 * (Yape, Plin, SIP, bIM, bancos…). Evita que las promociones, préstamos
 * y publicidad cuenten como ingresos.
 *
 * Ejemplos reales que detecta:
 *  · Yape:  "Baru mir* te envió un pago por S/ 1. El cód. de seguridad es: 967"
 *           (a veces sin código: BCP → Yape)
 *  · SIP:   "Recibiste un pago \n Mirella Lucero Curi Romani re pagó S/1.00"
 *
 * REGLAS: una notificación es pago SI Y SOLO SI trae una palabra de recepción
 * ("te envió un pago", "recibiste un pago", "re pagó"…) Y un monto con "S/".
 * Las promos ("tienes un préstamo preaprobado", "descuento"…) no traen
 * recepción + monto, por lo que quedan descartadas.
 */
object PaymentFilter {

    data class Pago(
        val amount: Double,
        val payerName: String?   // null si no se pudo extraer el nombre
    )

    /** Montos: "S/1.00", "S/ 1", "S/ 1,200.50" … */
    private val MONTO = Regex("""S/\s*([0-9]{1,3}(?:[.,][0-9]{3})*(?:[.,][0-9]{1,2})?)""")

    /** Verbos de recepción (en minúsculas). */
    private val RECEPCION = listOf(
        "te envió un pago", "te envio un pago", "te envió dinero", "te envio dinero",
        "recibiste un pago", "recibiste un abono", "recibiste una transferencia",
        "re pagó", "repagó", "te pagó", "te pago", "te realizó un pago", "te realizo un pago",
        "te transfirió", "te transfirio", "recibiste s/", "abono a tu cuenta",
        "te yapeó", "te yapeo", "yapeaste dinero", "pago recibido"
    )

    /** Busca el primer monto "S/ x" en el texto. */
    fun extraerMonto(text: String): Double? {
        val m = MONTO.find(text) ?: return null
        val crudo = m.groupValues[1].replace(",", "")
        // Si hay varios puntos, el último es el decimal (p. ej. "1.234.567.00")
        val normalizado = if (crudo.count { it == '.' } > 1) {
            crudo.substringBeforeLast('.') .replace(".", "") + "." + crudo.substringAfterLast('.')
        } else crudo
        return normalizado.toDoubleOrNull()
    }

    /** ¿La notificación (título + texto) es un PAGO recibido? */
    fun esPago(title: String, text: String): Boolean {
        val todo = "$title $text".lowercase()
        val hayRecepcion = RECEPCION.any { todo.contains(it) }
        val monto = extraerMonto(text)
        return hayRecepcion && monto != null
    }

    /**
     * Extrae quién pagó: el texto que aparece ANTES del verbo de recepción
     * (última línea útil, sin "Recibiste un pago" ni separadores).
     */
    fun extraerPagador(text: String): String? {
        // Buscar el primer verbo de recepción (indistinto de mayúsculas)
        var mejorInicio: Int? = null
        for (verbo in RECEPCION) {
            val idx = text.indexOf(verbo, ignoreCase = true)
            if (idx >= 0 && (mejorInicio == null || idx < mejorInicio)) {
                mejorInicio = idx
            }
        }
        val inicio = mejorInicio ?: return null
        if (inicio == 0) return null

        // Quitar basura del fragmento anterior: líneas, pipes y palabras de recepción
        val trozo = text.substring(0, inicio)
        val lineas = trozo.split('\n', '|')
            .map { it.trim() }
            .filter {
                it.isNotBlank() &&
                !it.contains("recibiste", true) &&
                !it.contains("confirmación", true) &&
                !it.contains("confirmacion", true)
            }
        val nombre = lineas.lastOrNull() ?: return null
        return nombre.take(40).ifBlank { null }
    }

    /** Analiza una notificación de billetera; null si NO es pago. */
    fun analizar(title: String, text: String): Pago? {
        if (!esPago(title, text)) return null
        return Pago(
            amount = extraerMonto(text) ?: return null,
            payerName = extraerPagador(text)
        )
    }
}
