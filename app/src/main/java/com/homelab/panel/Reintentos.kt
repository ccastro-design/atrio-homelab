package com.homelab.panel

import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException

/**
 * Qué hacer cuando un servidor **acepta la conexión y cuelga sin contestar**.
 *
 * Es lo que hace la web de aMule (`amuleweb`) cuando pierde su conexión interna con aMule:
 * se cierra a propósito, a mitad de lo que estuviera sirviendo, y deja que lo que la vigila
 * —Docker, systemd— la vuelva a arrancar. Lo dice su propio código, en
 * `CRemoteConnect::OnLost`: «External Connection lost — exiting.». Desde fuera se ve como
 * `net::ERR_EMPTY_RESPONSE` en la pestaña y como un envío fallido, y un segundo intento poco
 * después funciona. Le pasaba al autor tras un rato sin usar su aMule (03/10/2026): la página
 * de entrada cargaba, y al meter la contraseña —lo primero que ya necesita a aMule de
 * verdad— colgaba.
 *
 * La respuesta es la de los navegadores: la recarga automática de Chrome
 * (`NetErrorAutoReloader`) vuelve a pedir la página a los 1 s, 5 s, 30 s, 1 min… Aquí se
 * usan solo las dos primeras esperas, porque hay alguien mirando la pantalla: si no basta, se
 * le enseña el error y decide él. Y como Chrome, nunca se reenvía un formulario: se vuelve a
 * empezar desde el principio.
 */
object Reintentos {

    /** Esperas antes de cada repetición: las dos primeras de la recarga de Chrome. */
    val ESPERAS_MS = listOf(1_000L, 5_000L)

    /**
     * Errores de la pestaña que quieren decir «conectó y colgó».
     *
     * Se mira el nombre del error de red (`net::ERR_…`, el mismo que enseña la pantalla de
     * error), que dice exactamente qué pasó; los códigos numéricos del WebView son unas pocas
     * categorías genéricas.
     */
    private val COLGO = listOf("ERR_EMPTY_RESPONSE", "ERR_CONNECTION_RESET", "ERR_CONNECTION_CLOSED")

    /**
     * Si el error con que acabó una carga de la pestaña es de un servidor que colgó, llevando
     * ya [hechos] repeticiones.
     *
     * Un «conexión rechazada» solo cuenta **dentro** de una tanda que empezó colgando: es el
     * puerto cerrado mientras el servidor vuelve a arrancar. Un servicio apagado de entrada se
     * avisa al momento, como siempre.
     */
    fun colgoLaPestana(descripcion: String?, hechos: Int): Boolean {
        if (descripcion == null) return false
        return COLGO.any { descripcion.contains(it) } ||
            (hechos > 0 && descripcion.contains("ERR_CONNECTION_REFUSED"))
    }

    /** Si la carga de una pestaña que acaba de fallar se repite sola. */
    fun repetirLaPestana(descripcion: String?, hechos: Int): Boolean =
        hechos < ESPERAS_MS.size && colgoLaPestana(descripcion, hechos)

    /**
     * Conecta y después hace [resto]. Lo que falle después de conectar —salvo tardar
     * demasiado— sale como [ServidorColgo].
     *
     * Se distingue por el momento y no por el tipo de excepción, que depende de la versión de
     * Android. Medido en el móvil con un servidor de mentira (03/10/2026): colgar sin contestar
     * da `IOException: unexpected end of stream` o `SocketException: Connection reset`, y
     * siempre **al leer**; un servicio apagado falla **al conectar** (`ConnectException`). Y
     * Android no repite nada por su cuenta: el servidor recibe una sola conexión.
     */
    inline fun <T> HttpURLConnection.conectarY(resto: HttpURLConnection.() -> T): T {
        // Aquí fallan «apagado» y «no responde», que no se repiten.
        connect()
        return try {
            resto()
        } catch (e: SocketTimeoutException) {
            // Conectó pero no contesta a tiempo: está vivo y ocupado, no reiniciándose.
            throw e
        } catch (e: IOException) {
            throw ServidorColgo(e)
        }
    }

    /**
     * Hace [intento] y, si el servidor colgó, lo repite tras cada espera de [ESPERAS_MS].
     *
     * Mientras el servidor vuelve a arrancar su puerto está cerrado y conectar falla: dentro de
     * una tanda eso cuenta como lo mismo. Si no sale ninguna, se lanza el primer
     * [ServidorColgo], que es lo que de verdad pasó. Cualquier otro fallo corta la tanda.
     */
    suspend fun <T> repetirSiCuelga(
        esperar: suspend (Long) -> Unit = { delay(it) },
        intento: () -> T
    ): T {
        val primero = try {
            return intento()
        } catch (e: ServidorColgo) {
            e
        }

        for (espera in ESPERAS_MS) {
            esperar(espera)
            try {
                return intento()
            } catch (otro: ServidorColgo) {
                // Sigue colgando: a la siguiente espera.
            } catch (otro: ConnectException) {
                // Todavía arrancando: a la siguiente espera.
            }
        }

        throw primero
    }
}

/** El servidor conectó y después colgó, o cortó a medias. Ver [Reintentos]. */
class ServidorColgo(causa: IOException) : IOException(causa)
