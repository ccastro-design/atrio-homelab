package com.homelab.panel

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Las conexiones de los programas de descarga, todas hechas igual. Aquí solo va lo común:
 * lo que cada programa pide o contesta de particular se queda en su conector.
 */
internal object Http {

    /** Lo que se espera a un programa de descargas antes de darlo por perdido. */
    private const val ESPERA_MS = 15_000

    /** Abre una petición. Con [metodo] POST queda lista para escribir el cuerpo. */
    fun abrir(
        url: String,
        metodo: String = "GET",
        cabeceras: Map<String, String> = emptyMap()
    ): HttpURLConnection {
        Registro.traza("Red") { "→ $metodo $url" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = ESPERA_MS
            readTimeout = ESPERA_MS
            instanceFollowRedirects = true
            requestMethod = metodo
            if (metodo == "POST") doOutput = true
            cabeceras.forEach { (nombre, valor) -> setRequestProperty(nombre, valor) }
        }
    }

    fun escribir(conexion: HttpURLConnection, cuerpo: String) {
        OutputStreamWriter(conexion.outputStream).use { it.write(cuerpo) }
    }

    /**
     * Código y cuerpo de la respuesta. El cuerpo de un error viene por otro flujo
     * (`errorStream`) y se lee igual: muchos programas explican ahí qué ha fallado.
     */
    fun leer(conexion: HttpURLConnection): Respuesta {
        val codigo = conexion.responseCode
        val flujo = if (codigo >= 400) conexion.errorStream else conexion.inputStream
        val cuerpo = flujo?.use { String(it.readBytes()) }.orEmpty()
        Registro.traza("Red") { "← $codigo (${cuerpo.length} caracteres)" }
        return Respuesta(codigo, cuerpo)
    }

    data class Respuesta(val codigo: Int, val cuerpo: String)
}
