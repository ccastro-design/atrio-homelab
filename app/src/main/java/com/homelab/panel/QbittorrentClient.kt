package com.homelab.panel

import org.json.JSONObject
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
object QbittorrentClient : Conector {

    /** Cookie de sesión por servidor. Vale para varios envíos seguidos. */
    private val cookies = mutableMapOf<String, String>()

    override suspend fun entregar(destino: DatosDeEntrega, enlace: String): Entrega {
        val base = destino.base.trimEnd('/')

        var cookie = cookies[base] ?: entrar(base, destino.usuario, destino.clave)
            ?: return Entrega.ContrasenaMala

        var respuesta = anadir(base, cookie, enlace)

        // La sesión caduca por tiempo. Se vuelve a entrar y se reintenta una vez.
        if (respuesta.codigo == 401 || respuesta.codigo == 403) {
            cookie = entrar(base, destino.usuario, destino.clave) ?: return Entrega.ContrasenaMala
            respuesta = anadir(base, cookie, enlace)
        }

        cookies[base] = cookie

        return when {
            respuesta.codigo == 409 -> Entrega.YaEstaba
            respuesta.codigo !in 200..299 -> Entrega.CodigoHttp(respuesta.codigo)
            rechazado(respuesta.cuerpo) -> Entrega.NoAceptada
            else -> Entrega.Hecha
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
        val cabeceras = buildMap {
            put("Content-Type", "application/x-www-form-urlencoded")
            // Protección contra peticiones lanzadas desde otra web: la 4.x la exige.
            put("Referer", base)
            put("Origin", base)
            cookie?.let { put("Cookie", it) }
        }

        val conexion = Http.abrir(url, "POST", cabeceras)
        Http.escribir(conexion, cuerpo)

        val respuesta = Http.leer(conexion)

        // El nombre de la cookie cambia con el puerto, así que se guarda tal cual venga.
        val nueva = conexion.getHeaderField("Set-Cookie")?.substringBefore(';')
        conexion.disconnect()

        return Respuesta(respuesta.codigo, respuesta.cuerpo, nueva)
    }

    private fun codificar(valor: String): String = URLEncoder.encode(valor, "UTF-8")
}
