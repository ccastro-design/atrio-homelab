package com.homelab.panel

import com.homelab.panel.Reintentos.conectarY
import java.net.URLEncoder

/**
 * Entrega enlaces a aMule por su interfaz web, siguiendo el mismo camino que seguiría
 * una persona a mano:
 *
 *   1. GET  /            -> devuelve la cookie de sesión
 *   2. POST /            -> pass=<contraseña>&submit=Submit
 *   3. POST /footer.php  -> ed2klink=<enlace>&selectcat=all&Submit=Download link
 */
object AmuleClient : Conector {

    override suspend fun entregar(destino: DatosDeEntrega, enlace: String): Entrega {
        if (destino.clave.isEmpty()) return Entrega.FaltaContrasena

        val base = if (destino.base.endsWith("/")) destino.base else "${destino.base}/"
        val codigo = entregarConReintentos(base, destino.clave, enlace)
            ?: return Entrega.ContrasenaMala

        return if (codigo in 200..299) Entrega.Hecha else Entrega.CodigoHttp(codigo)
    }

    /**
     * La entrega entera —entrar y mandar el enlace—, repetida si aMule cuelga a mitad.
     *
     * Se repite **entera** y no solo el paso que falló: cuando `amuleweb` cuelga es porque se
     * está cerrando, y al volver no recuerda ninguna sesión. Mandar dos veces el mismo enlace
     * no lo duplica: aMule ignora el que ya tiene. Ver [Reintentos].
     *
     * Aparte para poder probarla en el móvil contra un aMule de mentira.
     *
     * @return el código de la respuesta al enlace, o null si no aceptó la contraseña.
     */
    internal suspend fun entregarConReintentos(base: String, password: String, link: String): Int? =
        Reintentos.repetirSiCuelga { unaEntrega(base, password, link) }

    private fun unaEntrega(base: String, password: String, link: String): Int? {
        val cookie = login(base, password) ?: return null

        val cuerpo = buildString {
            append("ed2klink=").append(URLEncoder.encode(link, "UTF-8"))
            append("&selectcat=all")
            append("&Submit=").append(URLEncoder.encode("Download link", "UTF-8"))
        }

        return post("${base}footer.php", cuerpo, cookie)
    }

    /**
     * Devuelve la cookie de sesión, o null si la contraseña no fue aceptada.
     *
     * Cada petición pasa por `conectarY` hasta tener el código de respuesta: si aMule cuelga,
     * es ahí donde se nota.
     */
    private fun login(base: String, password: String): String? {
        val inicial = Http.abrir(base)
        inicial.conectarY { responseCode }
        val cookie = inicial.getHeaderField("Set-Cookie")?.substringBefore(';')
        inicial.inputStream.use { it.readBytes() }
        inicial.disconnect()

        if (cookie.isNullOrBlank()) return null

        val cuerpo = "pass=${URLEncoder.encode(password, "UTF-8")}&submit=Submit"
        val conexion = Http.abrir(base, "POST", formulario(cookie))

        conexion.conectarY {
            Http.escribir(this, cuerpo)
            responseCode
        }

        val respuesta = conexion.inputStream.use { String(it.readBytes()) }
        conexion.disconnect()

        // Si sigue mostrando el campo de contraseña, no entró.
        return if (respuesta.contains("name=\"pass\"")) null else cookie
    }

    private fun post(url: String, cuerpo: String, cookie: String): Int {
        val conexion = Http.abrir(url, "POST", formulario(cookie))

        val codigo = conexion.conectarY {
            Http.escribir(this, cuerpo)
            responseCode
        }
        conexion.inputStream.use { it.readBytes() }
        conexion.disconnect()
        return codigo
    }

    private fun formulario(cookie: String) = mapOf(
        "Cookie" to cookie,
        "Content-Type" to "application/x-www-form-urlencoded"
    )
}
