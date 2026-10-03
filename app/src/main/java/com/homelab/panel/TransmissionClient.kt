package com.homelab.panel

import org.json.JSONObject
import java.util.Base64

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
object TransmissionClient : Conector {

    /** Identificador de sesión en curso. Vale para todos los envíos hasta que caduque. */
    private var sesion: String? = null

    override suspend fun entregar(destino: DatosDeEntrega, enlace: String): Entrega {
        val direccion = rpcUrl(destino.base)

        val peticion = JSONObject().apply {
            put("method", "torrent-add")
            // «filename» acepta tanto un magnet como la dirección de un .torrent: lo
            // descarga el propio Transmission, así que la aplicación nunca maneja ficheros.
            put("arguments", JSONObject().put("filename", enlace))
        }.toString()

        var respuesta = llamar(direccion, peticion, destino.usuario, destino.clave)

        // Sesión caducada o primera petición: se reintenta una vez con la nueva.
        if (respuesta.codigo == 409) {
            sesion = respuesta.sesion
            respuesta = llamar(direccion, peticion, destino.usuario, destino.clave)
        }

        return when {
            respuesta.codigo == 401 -> Entrega.ContrasenaMala
            respuesta.codigo == 403 -> Entrega.FueraDeLaListaBlanca
            respuesta.codigo !in 200..299 -> Entrega.CodigoHttp(respuesta.codigo)
            else -> interpretar(respuesta.cuerpo)
        }
    }

    /**
     * Lee la respuesta del RPC.
     *
     * Un enlace que ya estaba en la lista se contesta con `torrent-duplicate` y resultado
     * correcto. No es un fallo, pero decir «enviado» sin más haría pensar que se ha
     * añadido algo nuevo.
     */
    private fun interpretar(cuerpo: String): Entrega {
        val json = runCatching { JSONObject(cuerpo) }.getOrNull()
            ?: return Entrega.RespuestaIninteligible

        val resultado = json.optString("result")
        if (resultado != "success") return Entrega.Rechazada(resultado)

        val argumentos = json.optJSONObject("arguments")
        return if (argumentos?.has("torrent-duplicate") == true) Entrega.YaEstaba else Entrega.Hecha
    }

    private data class Respuesta(val codigo: Int, val cuerpo: String, val sesion: String?)

    private fun llamar(
        url: String,
        cuerpo: String,
        username: String,
        password: String
    ): Respuesta {
        val cabeceras = buildMap {
            put("Content-Type", "application/json")

            // Transmission puede funcionar sin usuario y contraseña; solo se manda la
            // autenticación si el usuario ha puesto alguna.
            if (username.isNotBlank() || password.isNotBlank()) {
                val credenciales = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
                put("Authorization", "Basic $credenciales")
            }

            sesion?.let { put("X-Transmission-Session-Id", it) }
        }

        val conexion = Http.abrir(url, "POST", cabeceras)
        Http.escribir(conexion, cuerpo)

        val respuesta = Http.leer(conexion)
        val nuevaSesion = conexion.getHeaderField("X-Transmission-Session-Id")
        conexion.disconnect()

        return Respuesta(respuesta.codigo, respuesta.cuerpo, nuevaSesion)
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
