package com.homelab.panel

import android.net.http.SslError
import android.os.Build
import java.security.MessageDigest

/**
 * Excepciones de certificado que el usuario ha aceptado expresamente.
 *
 * Los certificados autofirmados son lo normal en un servidor doméstico, así que hay que
 * poder aceptarlos. Lo que no se hace nunca es aceptarlos en silencio: se corta la
 * carga, se le muestra al usuario de quién dice ser el certificado y su huella, y solo
 * si acepta se continúa. Además la excepción queda atada a esa huella concreta: si el
 * certificado del servidor cambia, se vuelve a preguntar.
 *
 * Aceptar sin preguntar, aparte de dejar al usuario expuesto a que le suplanten un
 * servicio, es motivo de rechazo en la revisión de Google Play.
 */
object CertTrust {

    data class Info(
        val host: String,
        val issuedTo: String,
        val issuedBy: String,
        val fingerprint: String
    )

    fun describe(error: SslError): Info {
        val cert = error.certificate
        val host = hostOf(error.url)

        val paraQuien = cert?.issuedTo?.cName?.takeIf { it.isNotBlank() }
            ?: cert?.issuedTo?.dName.orEmpty()
        val porQuien = cert?.issuedBy?.cName?.takeIf { it.isNotBlank() }
            ?: cert?.issuedBy?.dName.orEmpty()

        return Info(
            host = host,
            issuedTo = paraQuien,
            issuedBy = porQuien,
            fingerprint = huella(error)
        )
    }

    /** True si el usuario ya aceptó este mismo certificado para este equipo. */
    fun isTrusted(config: PanelConfig, info: Info): Boolean =
        info.host.isNotBlank() && config.trustedCerts[info.host] == info.fingerprint

    fun trust(config: PanelConfig, info: Info): PanelConfig =
        config.copy(trustedCerts = config.trustedCerts + (info.host to info.fingerprint))

    fun revoke(config: PanelConfig, host: String): PanelConfig =
        config.copy(trustedCerts = config.trustedCerts - host)

    /**
     * Huella SHA-256 del certificado. Solo se puede obtener el certificado completo
     * desde Android 10; en versiones anteriores se identifica por sus datos, que es
     * menos preciso pero sirve para detectar que ha cambiado.
     */
    private fun huella(error: SslError): String {
        val cert = error.certificate ?: return ""

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val x509 = runCatching { cert.x509Certificate }.getOrNull()
            if (x509 != null) {
                val bytes = runCatching { x509.encoded }.getOrNull()
                if (bytes != null) return sha256Hex(bytes)
            }
        }

        val datos = buildString {
            append(cert.issuedTo?.dName.orEmpty())
            append('|')
            append(cert.issuedBy?.dName.orEmpty())
            append('|')
            append(cert.validNotAfterDate?.time ?: 0L)
        }
        return sha256Hex(datos.toByteArray())
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(":") { "%02X".format(it) }
}
