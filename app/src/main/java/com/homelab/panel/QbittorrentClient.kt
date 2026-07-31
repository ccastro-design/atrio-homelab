package com.homelab.panel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Entrega de enlaces a qBittorrent por su interfaz web (API v2).
 *
 * Son dos pasos: pedir una cookie de sesión con usuario y contraseña, y mandar el enlace
 * con ella. Comprobado contra un qBittorrent 5.2.3 real, donde salieron tres cosas que no
 * están en la documentación de siempre:
 *
 *  - El **nombre de la cookie lleva el puerto** (`QBT_SID_8085`), así que no vale buscar
 *    una llamada `SID`: se guarda entera tal como venga.
 *  - Un enlace **repetido responde 409**, no un error. No es un fallo: ya estaba.
 *  - Las versiones nuevas contestan al alta con un **JSON** que dice cuántos entraron
 *    (`success_count`, `failure_count`); las viejas contestaban `Ok.` a secas. Se admiten
 *    las dos formas, porque el usuario tendrá la versión que tenga.
 *
 * Se manda además la cabecera `Referer`. Esta versión no la exige, pero las 4.x sí, y
 * ponerla no cuesta nada: sin ella responden 403 sin explicar por qué.
 */
object QbittorrentClient {

    private const val TAG = "Panel"
    private const val TIMEOUT = 15_000

    /** Cookie de sesión por servidor. Vale para varios envíos seguidos. */
    private val cookies = mutableMapOf<String, String>()

    suspend fun send(
        context: Context,
        baseUrl: String,
        username: String,
        password: String,
        link: String,
        targetName: String
    ): SendResult = withContext(Dispatchers.IO) {
        val base = baseUrl.trimEnd('/')

        try {
            var cookie = cookies[base] ?: entrar(base, username, password)
                ?: return@withContext SendResult.Failed(
                    context.getString(R.string.send_bad_password, targetName)
                )

            var respuesta = anadir(base, cookie, link)

            // La sesión caduca por tiempo. Se vuelve a entrar y se reintenta una vez.
            if (respuesta.codigo == 401 || respuesta.codigo == 403) {
                cookie = entrar(base, username, password)
                    ?: return@withContext SendResult.Failed(
                        context.getString(R.string.send_bad_password, targetName)
                    )
                respuesta = anadir(base, cookie, link)
            }

            cookies[base] = cookie

            when {
                respuesta.codigo == 409 ->
                    SendResult.Failed(context.getString(R.string.send_duplicate, targetName))

                respuesta.codigo !in 200..299 -> SendResult.Failed(
                    context.getString(R.string.send_http_error, targetName, respuesta.codigo)
                )

                rechazado(respuesta.cuerpo) ->
                    SendResult.Failed(context.getString(R.string.send_not_accepted, targetName))

                else -> {
                    Log.i(TAG, "Enlace entregado a «$targetName»")
                    SendResult.Ok(targetName)
                }
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

    /** Devuelve la cookie de sesión, o null si no aceptó las credenciales. */
    private fun entrar(base: String, username: String, password: String): String? {
        val cuerpo = "username=${codificar(username)}&password=${codificar(password)}"
        val respuesta = peticion("$base/api/v2/auth/login", base, cuerpo, null)

        // Las versiones antiguas contestan 200 con «Fails.» en vez de un código de error.
        if (respuesta.codigo !in 200..299) return null
        if (respuesta.cuerpo.trim().equals("Fails.", ignoreCase = true)) return null

        return respuesta.cookie
    }

    private fun anadir(base: String, cookie: String, link: String): Respuesta {
        // «urls» vale tanto para un magnet como para la dirección de un .torrent: lo
        // descarga el propio qBittorrent.
        val cuerpo = "urls=${codificar(link)}"
        return peticion("$base/api/v2/torrents/add", base, cuerpo, cookie)
    }

    /** El alta ha ido bien salvo que la respuesta diga lo contrario. */
    private fun rechazado(cuerpo: String): Boolean {
        val texto = cuerpo.trim()

        if (texto.startsWith("{")) {
            val json = runCatching { JSONObject(texto) }.getOrNull() ?: return false
            return json.optInt("success_count", 1) == 0 && json.optInt("failure_count") > 0
        }

        return texto.equals("Fails.", ignoreCase = true)
    }

    private data class Respuesta(val codigo: Int, val cuerpo: String, val cookie: String?)

    private fun peticion(url: String, base: String, cuerpo: String, cookie: String?): Respuesta {
        val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            // Protección contra peticiones lanzadas desde otra web: la 4.x la exige.
            setRequestProperty("Referer", base)
            setRequestProperty("Origin", base)
            cookie?.let { setRequestProperty("Cookie", it) }
        }

        OutputStreamWriter(conexion.outputStream).use { it.write(cuerpo) }

        val codigo = conexion.responseCode
        val flujo = if (codigo >= 400) conexion.errorStream else conexion.inputStream
        val texto = flujo?.use { String(it.readBytes()) }.orEmpty()

        // El nombre de la cookie cambia con el puerto, así que se guarda tal cual venga.
        val nueva = conexion.getHeaderField("Set-Cookie")?.substringBefore(';')
        conexion.disconnect()

        return Respuesta(codigo, texto, nueva)
    }

    private fun codificar(valor: String): String = URLEncoder.encode(valor, "UTF-8")
}
