package com.homelab.panel

import org.json.JSONObject
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
 *
 * Y una cosa más, esta sacada de su documentación y no de una prueba propia:
 *
 *  - **El 403 es la comprobación del nombre de equipo**, no un problema de permisos ni de
 *    clave. SABnzbd solo atiende peticiones cuyo «Host» esté en su `host_whitelist`, que es
 *    su defensa contra el secuestro de DNS, y contesta «Access denied - Hostname
 *    verification failed». Salta al llegar por un nombre en vez de por la dirección IP, que
 *    es justo lo que hace esta aplicación cuando el servidor se define con nombre. Tiene
 *    mensaje propio porque «ha respondido con el código 403» manda a mirar la clave API,
 *    que no tiene nada que ver, y el ajuste está escondido en Config › Special.
 */
object SabnzbdClient : Conector {

    override suspend fun entregar(destino: DatosDeEntrega, enlace: String): Entrega {
        val apiKey = destino.clave
        if (apiKey.isBlank()) return Entrega.FaltaClaveApi

        val direccion = buildString {
            append(destino.base.trimEnd('/'))
            append("/api?mode=addurl&output=json")
            append("&name=").append(URLEncoder.encode(enlace, "UTF-8"))
            append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
        }

        val conexion = Http.abrir(direccion)
        val respuesta = Http.leer(conexion)
        conexion.disconnect()

        return contestacion(respuesta.codigo, respuesta.cuerpo.trim())
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
    override suspend fun subir(destino: DatosDeEntrega, fichero: DownloadFile): Entrega {
        val apiKey = destino.clave
        if (apiKey.isBlank()) return Entrega.FaltaClaveApi

        val direccion = buildString {
            append(destino.base.trimEnd('/'))
            append("/api?mode=addfile&output=json")
            append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
        }

        val frontera = "----AtrioHomelab${System.currentTimeMillis()}"

        val conexion = Http.abrir(
            direccion,
            "POST",
            mapOf("Content-Type" to "multipart/form-data; boundary=$frontera")
        )

        // El campo se llama `name`, que es como lo espera SABnzbd para el fichero.
        val cuerpoEnviado = buildString {
            append("--").append(frontera).append("\r\n")
            append("Content-Disposition: form-data; name=\"name\"; filename=\"")
            append(fichero.name.replace('"', '_')).append("\"\r\n")
            append("Content-Type: application/x-nzb\r\n\r\n")
            append(fichero.content).append("\r\n")
            append("--").append(frontera).append("--\r\n")
        }

        conexion.outputStream.use { it.write(cuerpoEnviado.toByteArray(Charsets.UTF_8)) }

        val respuesta = Http.leer(conexion)
        conexion.disconnect()

        return contestacion(respuesta.codigo, respuesta.cuerpo.trim())
    }

    /** SABnzbd contesta igual a un enlace que a un fichero. */
    private fun contestacion(codigo: Int, cuerpo: String): Entrega = when {
        codigo == 403 -> Entrega.NombreDeEquipoNoPermitido
        codigo !in 200..299 -> Entrega.CodigoHttp(codigo)
        cuerpo.contains("API Key", ignoreCase = true) -> Entrega.ClaveApiMala
        !cuerpo.startsWith("{") -> Entrega.RespuestaIninteligible
        else -> interpretar(cuerpo)
    }

    private fun interpretar(cuerpo: String): Entrega {
        val json = runCatching { JSONObject(cuerpo) }.getOrNull()
            ?: return Entrega.RespuestaIninteligible

        if (!json.optBoolean("status", false)) {
            return Entrega.Rechazada(json.optString("error").ifBlank { null })
        }

        return Entrega.Hecha
    }
}
