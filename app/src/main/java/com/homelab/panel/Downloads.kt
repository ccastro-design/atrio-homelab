package com.homelab.panel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Entrega de enlaces a los servicios de descarga del usuario.
 *
 * La aplicación no descarga nada por su cuenta ni busca contenido en ninguna parte: es
 * un mando a distancia de programas que el usuario ya tiene funcionando en su servidor.
 */
object Links {

    /** Reconoce de qué tipo es un enlace. */
    fun detect(text: String): LinkKind? {
        val t = text.trim()

        // La extensión se mira en la ruta, no en la dirección entera: muchos sitios
        // sirven el fichero con parámetros detrás («…/x.torrent?descarga=1») y así
        // acababa tomándose por un enlace directo cualquiera.
        val ruta = t.substringBefore('?').substringBefore('#')

        return when {
            t.startsWith("ed2k://", true) -> LinkKind.ED2K
            t.startsWith("magnet:", true) -> LinkKind.MAGNET
            ruta.endsWith(".torrent", true) -> LinkKind.TORRENT
            ruta.endsWith(".nzb", true) -> LinkKind.NZB
            // Una dirección web a secas no es un enlace de descarga: es una página.
            else -> null
        }
    }

    /**
     * Todos los enlaces reconocibles de un texto, sin repetidos.
     *
     * Las webs de enlaces enseñan un cuadro con varios, uno por línea, y quien los copia
     * los quiere todos. Un enlace nunca lleva espacios, así que separar por espacios y
     * saltos de línea vale para todos los tipos.
     */
    fun extractAll(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()

        return text.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { detect(it) != null }
            .distinct()
    }

    /** Saca el primer enlace reconocible de un texto compartido. */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val directo = text.trim()
        if (detect(directo) != null) return directo

        // Al compartir desde un navegador suele llegar «título http://…»
        return directo.split(Regex("\\s+")).firstOrNull { detect(it) != null }
    }
}

/**
 * Un fichero de descarga que ha armado la propia web dentro del móvil.
 *
 * No todas las webs sirven un enlace: Binsearch genera el `.nzb` con JavaScript y lo
 * ofrece como un `blob:`, una dirección que **solo existe dentro de esa pestaña**. No hay
 * nada que pasarle al programa de descargas, así que se lee el contenido ahí mismo y se
 * sube entero.
 */
data class DownloadFile(val name: String, val content: String, val kind: LinkKind)

/** Resultado de intentar entregar un enlace. */
sealed interface SendResult {
    data class Ok(val targetName: String) : SendResult
    data class Failed(val message: String) : SendResult
}

object LinkSender {

    private const val TAG = "Panel"

    /**
     * Entrega el enlace al destino indicado.
     *
     * @param baseUrl dirección del servicio de descarga, ya resuelta según el perfil.
     */
    suspend fun send(
        context: Context,
        target: DownloadTarget,
        baseUrl: String,
        link: String
    ): SendResult {
        if (baseUrl.isBlank()) {
            return SendResult.Failed(context.getString(R.string.send_no_address, target.name))
        }

        val password = SecureStore.read(context, SecureStore.targetKey(target.id)).orEmpty()

        return when (target.targetKind) {
            TargetKind.AMULE -> AmuleClient.send(context, baseUrl, password, link, target.name)

            // La clave API se guarda donde las contraseñas: cifrada y fuera de la copia
            // de seguridad, que es lo que toca para un secreto.
            TargetKind.SABNZBD -> SabnzbdClient.send(
                context = context,
                baseUrl = baseUrl,
                apiKey = password,
                link = link,
                targetName = target.name
            )

            TargetKind.QBITTORRENT -> QbittorrentClient.send(
                context = context,
                baseUrl = baseUrl,
                username = target.username,
                password = password,
                link = link,
                targetName = target.name
            )

            TargetKind.TRANSMISSION -> TransmissionClient.send(
                context = context,
                baseUrl = baseUrl,
                username = target.username,
                password = password,
                link = link,
                targetName = target.name
            )

            // Los demás conectores llegarán después; el enrutado ya está preparado para
            // ellos y cada uno solo tiene que implementar su forma de entregar.
            else -> SendResult.Failed(
                context.getString(R.string.send_target_not_supported, target.name)
            )
        }
    }

    /**
     * Entrega un fichero que la web armó en el móvil, en vez de un enlace.
     *
     * De momento solo SABnzbd, que es donde se ha visto el caso y donde está probado:
     * Transmission y qBittorrent admiten algo parecido, pero prometerlo sin haberlo
     * comprobado contra sus instalaciones sería fiarse de la documentación.
     */
    suspend fun sendFile(
        context: Context,
        target: DownloadTarget,
        baseUrl: String,
        file: DownloadFile
    ): SendResult {
        if (baseUrl.isBlank()) {
            return SendResult.Failed(context.getString(R.string.send_no_address, target.name))
        }

        val password = SecureStore.read(context, SecureStore.targetKey(target.id)).orEmpty()

        return when (target.targetKind) {
            TargetKind.SABNZBD -> SabnzbdClient.sendFile(
                context = context,
                baseUrl = baseUrl,
                apiKey = password,
                fileName = file.name,
                content = file.content,
                targetName = target.name
            )

            else -> SendResult.Failed(
                context.getString(R.string.send_file_not_supported, target.name)
            )
        }
    }
}

/**
 * Entrega enlaces a aMule por su interfaz web, siguiendo el mismo camino que seguiría
 * una persona a mano:
 *
 *   1. GET  /            -> devuelve la cookie de sesión
 *   2. POST /            -> pass=<contraseña>&submit=Submit
 *   3. POST /footer.php  -> ed2klink=<enlace>&selectcat=all&Submit=Download link
 */
object AmuleClient {

    private const val TAG = "Panel"
    private const val TIMEOUT = 15_000

    suspend fun send(
        context: Context,
        baseUrl: String,
        password: String,
        link: String,
        targetName: String
    ): SendResult = withContext(Dispatchers.IO) {
        if (password.isEmpty()) {
            return@withContext SendResult.Failed(
                context.getString(R.string.send_no_password, targetName)
            )
        }

        val base = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        try {
            val cookie = login(base, password)
                ?: return@withContext SendResult.Failed(
                    context.getString(R.string.send_bad_password, targetName)
                )

            val cuerpo = buildString {
                append("ed2klink=").append(URLEncoder.encode(link, "UTF-8"))
                append("&selectcat=all")
                append("&Submit=").append(URLEncoder.encode("Download link", "UTF-8"))
            }

            val codigo = post("${base}footer.php", cuerpo, cookie)

            if (codigo in 200..299) {
                Log.i(TAG, "Enlace entregado a «$targetName»")
                SendResult.Ok(targetName)
            } else {
                SendResult.Failed(context.getString(R.string.send_http_error, targetName, codigo))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallo entregando el enlace a «$targetName»", e)
            SendResult.Failed(
                context.getString(
                    R.string.send_unreachable,
                    targetName,
                    e.message ?: e.javaClass.simpleName
                )
            )
        }
    }

    /** Devuelve la cookie de sesión, o null si la contraseña no fue aceptada. */
    private fun login(base: String, password: String): String? {
        val inicial = open(base)
        val cookie = inicial.getHeaderField("Set-Cookie")?.substringBefore(';')
        inicial.inputStream.use { it.readBytes() }
        inicial.disconnect()

        if (cookie.isNullOrBlank()) return null

        val cuerpo = "pass=${URLEncoder.encode(password, "UTF-8")}&submit=Submit"
        val conexion = open(base, cookie)
        conexion.requestMethod = "POST"
        conexion.doOutput = true
        conexion.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        OutputStreamWriter(conexion.outputStream).use { it.write(cuerpo) }

        val respuesta = conexion.inputStream.use { String(it.readBytes()) }
        conexion.disconnect()

        // Si sigue mostrando el campo de contraseña, no entró.
        return if (respuesta.contains("name=\"pass\"")) null else cookie
    }

    private fun post(url: String, cuerpo: String, cookie: String): Int {
        val conexion = open(url, cookie)
        conexion.requestMethod = "POST"
        conexion.doOutput = true
        conexion.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

        OutputStreamWriter(conexion.outputStream).use { it.write(cuerpo) }

        val codigo = conexion.responseCode
        conexion.inputStream.use { it.readBytes() }
        conexion.disconnect()
        return codigo
    }

    private fun open(url: String, cookie: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            instanceFollowRedirects = true
            cookie?.let { setRequestProperty("Cookie", it) }
        }
}
