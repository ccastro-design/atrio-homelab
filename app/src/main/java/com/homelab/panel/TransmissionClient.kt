package com.homelab.panel

import android.content.Context
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Entrega de enlaces a Transmission por su control remoto (RPC).
 *
 * Todo pasa por una sola dirección, `/transmission/rpc`, con un JSON que dice qué hacer.
 * Tiene dos peculiaridades que hay que respetar, comprobadas contra un Transmission 4.1.3
 * de verdad:
 *
 *  - **El baile del 409.** La primera petición se rechaza siempre con un 409 que trae la
 *    cabecera `X-Transmission-Session-Id`; hay que repetirla con ese valor. Es su defensa
 *    contra peticiones lanzadas desde una web cualquiera. El identificador caduca, así que
 *    no basta con pedirlo una vez: cuando vuelva un 409, se repite con el nuevo.
 *  - **La lista blanca.** Transmission solo atiende a las direcciones de su `rpc-whitelist`
 *    y responde 403 al resto. Es el fallo más común al configurarlo, y por eso tiene
 *    mensaje propio: sin él, «no se pudo enviar» no dice dónde mirar.
 */
object TransmissionClient {

    private const val TAG = "Panel"
    private const val TIMEOUT = 15_000

    /** Identificador de sesión en curso. Vale para todos los envíos hasta que caduque. */
    private var sesion: String? = null

    suspend fun send(
        context: Context,
        baseUrl: String,
        username: String,
        password: String,
        link: String,
        targetName: String
    ): SendResult = withContext(Dispatchers.IO) {
        val direccion = rpcUrl(baseUrl)

        val peticion = JSONObject().apply {
            put("method", "torrent-add")
            // «filename» acepta tanto un magnet como la dirección de un .torrent: lo
            // descarga el propio Transmission, así que la aplicación nunca maneja ficheros.
            put("arguments", JSONObject().put("filename", link))
        }.toString()

        try {
            var respuesta = llamar(direccion, peticion, username, password)

            // Sesión caducada o primera petición: se reintenta una vez con la nueva.
            if (respuesta.codigo == 409) {
                sesion = respuesta.sesion
                respuesta = llamar(direccion, peticion, username, password)
            }

            when {
                respuesta.codigo == 401 ->
                    SendResult.Failed(context.getString(R.string.send_bad_password, targetName))

                respuesta.codigo == 403 ->
                    SendResult.Failed(context.getString(R.string.send_not_whitelisted, targetName))

                respuesta.codigo !in 200..299 -> SendResult.Failed(
                    context.getString(R.string.send_http_error, targetName, respuesta.codigo)
                )

                else -> interpretar(context, respuesta.cuerpo, targetName)
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

    /**
     * Lee la respuesta del RPC.
     *
     * Un enlace que ya estaba en la lista se contesta con `torrent-duplicate` y resultado
     * correcto. No es un fallo, pero decir «enviado» sin más haría pensar que se ha
     * añadido algo nuevo.
     */
    private fun interpretar(context: Context, cuerpo: String, targetName: String): SendResult {
        val json = runCatching { JSONObject(cuerpo) }.getOrNull()
            ?: return SendResult.Failed(
                context.getString(R.string.send_bad_answer, targetName)
            )

        val resultado = json.optString("result")
        if (resultado != "success") {
            return SendResult.Failed(
                context.getString(R.string.send_refused, targetName, resultado)
            )
        }

        val argumentos = json.optJSONObject("arguments")

        return if (argumentos?.has("torrent-duplicate") == true) {
            SendResult.Failed(context.getString(R.string.send_duplicate, targetName))
        } else {
            Log.i(TAG, "Enlace entregado a «$targetName»")
            SendResult.Ok(targetName)
        }
    }

    private data class Respuesta(val codigo: Int, val cuerpo: String, val sesion: String?)

    private fun llamar(
        url: String,
        cuerpo: String,
        username: String,
        password: String
    ): Respuesta {
        val conexion = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json")

            // Transmission puede funcionar sin usuario y contraseña; solo se manda la
            // autenticación si el usuario ha puesto alguna.
            if (username.isNotBlank() || password.isNotBlank()) {
                val credenciales = Base64.encodeToString(
                    "$username:$password".toByteArray(), Base64.NO_WRAP
                )
                setRequestProperty("Authorization", "Basic $credenciales")
            }

            sesion?.let { setRequestProperty("X-Transmission-Session-Id", it) }
        }

        OutputStreamWriter(conexion.outputStream).use { it.write(cuerpo) }

        val codigo = conexion.responseCode
        val flujo = if (codigo >= 400) conexion.errorStream else conexion.inputStream
        val texto = flujo?.use { String(it.readBytes()) }.orEmpty()
        val nuevaSesion = conexion.getHeaderField("X-Transmission-Session-Id")
        conexion.disconnect()

        return Respuesta(codigo, texto, nuevaSesion)
    }

    /**
     * Dirección del control remoto.
     *
     * El usuario escribe la del panel web (`http://192.168.1.254:9091`), que es la que
     * conoce; el camino del RPC lo pone la aplicación. Si ya lo hubiera escrito él, no se
     * repite.
     */
    private fun rpcUrl(baseUrl: String): String {
        val base = baseUrl.trimEnd('/')
        return if (base.endsWith("/transmission/rpc")) base else "$base/transmission/rpc"
    }
}
