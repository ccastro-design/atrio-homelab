package com.homelab.panel

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Entrega de ficheros `.nzb` a SABnzbd, que descarga de Usenet.
 *
 * Es el conector más sencillo de los tres: una sola petición, sin sesión ni cookies. En
 * vez de usuario y contraseña usa una **clave API**, que el usuario copia de la pantalla
 * de ajustes de su SABnzbd; por eso la ficha del destino cambia de aspecto para este tipo.
 *
 * Comprobado contra un SABnzbd 5.0.4 real:
 *
 *  - `mode=addurl` contesta `{"status":true,"nzo_ids":[…]}` **en cuanto encola el
 *    encargo**, sin haber descargado ni mirado el fichero. Que el `.nzb` sea válido o que
 *    haya proveedor de Usenet configurado se sabe después, en su propia cola: aquí no hay
 *    forma de enterarse, ni falta, porque eso ya es asunto suyo.
 *  - Con la clave equivocada responde **texto pelado** («API Key Incorrect»), no un JSON.
 *    Hay que contar con las dos formas o el error acaba siendo «respuesta ininteligible».
 */
object SabnzbdClient {

    private const val TAG = "Panel"
    private const val TIMEOUT = 15_000

    suspend fun send(
        context: Context,
        baseUrl: String,
        apiKey: String,
        link: String,
        targetName: String
    ): SendResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext SendResult.Failed(
                context.getString(R.string.send_no_api_key, targetName)
            )
        }

        val direccion = buildString {
            append(baseUrl.trimEnd('/'))
            append("/api?mode=addurl&output=json")
            append("&name=").append(URLEncoder.encode(link, "UTF-8"))
            append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
        }

        try {
            val conexion = (URL(direccion).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "GET"
            }

            val codigo = conexion.responseCode
            val flujo = if (codigo >= 400) conexion.errorStream else conexion.inputStream
            val cuerpo = flujo?.use { String(it.readBytes()) }.orEmpty().trim()
            conexion.disconnect()

            when {
                codigo !in 200..299 -> SendResult.Failed(
                    context.getString(R.string.send_http_error, targetName, codigo)
                )

                cuerpo.contains("API Key", ignoreCase = true) -> SendResult.Failed(
                    context.getString(R.string.send_bad_api_key, targetName)
                )

                !cuerpo.startsWith("{") -> SendResult.Failed(
                    context.getString(R.string.send_bad_answer, targetName)
                )

                else -> interpretar(context, cuerpo, targetName)
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
     * Sube un fichero `.nzb` entero, en vez de decirle a SABnzbd de dónde bajarlo.
     *
     * Hace falta para las webs que **arman el fichero en el propio móvil**: Binsearch lo
     * genera con JavaScript y lo ofrece como un `blob:`, que es una dirección que solo
     * existe dentro de esa pestaña. Pasársela a SABnzbd no sirve de nada —desde el
     * servidor no hay nada ahí— y la rechaza sin poder decir por qué.
     *
     * Es `mode=addfile`, que va por POST y con el fichero dentro del cuerpo en formato
     * multipart, escrito a mano aquí porque es lo único que se necesita de él.
     */
    suspend fun sendFile(
        context: Context,
        baseUrl: String,
        apiKey: String,
        fileName: String,
        content: String,
        targetName: String
    ): SendResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            return@withContext SendResult.Failed(
                context.getString(R.string.send_no_api_key, targetName)
            )
        }

        val direccion = buildString {
            append(baseUrl.trimEnd('/'))
            append("/api?mode=addfile&output=json")
            append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
        }

        val frontera = "----AtrioHomelab${System.currentTimeMillis()}"

        try {
            val conexion = (URL(direccion).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT
                readTimeout = TIMEOUT
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$frontera")
            }

            // El campo se llama `name`, que es como lo espera SABnzbd para el fichero.
            val cuerpoEnviado = buildString {
                append("--").append(frontera).append("\r\n")
                append("Content-Disposition: form-data; name=\"name\"; filename=\"")
                append(fileName.replace('"', '_')).append("\"\r\n")
                append("Content-Type: application/x-nzb\r\n\r\n")
                append(content).append("\r\n")
                append("--").append(frontera).append("--\r\n")
            }

            conexion.outputStream.use { it.write(cuerpoEnviado.toByteArray(Charsets.UTF_8)) }

            val codigo = conexion.responseCode
            val flujo = if (codigo >= 400) conexion.errorStream else conexion.inputStream
            val respuesta = flujo?.use { String(it.readBytes()) }.orEmpty().trim()
            conexion.disconnect()

            when {
                codigo !in 200..299 -> SendResult.Failed(
                    context.getString(R.string.send_http_error, targetName, codigo)
                )

                respuesta.contains("API Key", ignoreCase = true) -> SendResult.Failed(
                    context.getString(R.string.send_bad_api_key, targetName)
                )

                !respuesta.startsWith("{") -> SendResult.Failed(
                    context.getString(R.string.send_bad_answer, targetName)
                )

                else -> interpretar(context, respuesta, targetName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallo subiendo el fichero a «$targetName»", e)
            SendResult.Failed(
                context.getString(
                    R.string.send_unreachable,
                    targetName,
                    e.message ?: e.javaClass.simpleName
                )
            )
        }
    }

    private fun interpretar(context: Context, cuerpo: String, targetName: String): SendResult {
        val json = runCatching { JSONObject(cuerpo) }.getOrNull()
            ?: return SendResult.Failed(context.getString(R.string.send_bad_answer, targetName))

        if (!json.optBoolean("status", false)) {
            val motivo = json.optString("error").ifBlank {
                context.getString(R.string.send_reason_unknown)
            }
            return SendResult.Failed(
                context.getString(R.string.send_refused, targetName, motivo)
            )
        }

        Log.i(TAG, "Enlace entregado a «$targetName»")
        return SendResult.Ok(targetName)
    }
}
