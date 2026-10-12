package com.homelab.panel

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Qué hacer cuando un servidor **acepta la conexión y cuelga sin contestar**. Es lo que hace
 * la web de aMule (`amuleweb`) cuando pierde su conexión interna con aMule: se cierra a
 * propósito y deja que lo que la vigila la vuelva a arrancar.
 *
 * La respuesta es la de los navegadores, la recarga automática de Chrome
 * (`NetErrorAutoReloader`), cortada pronto porque hay alguien mirando la pantalla. Y como
 * Chrome, nunca se reenvía un formulario.
 */
object Reintentos {

    /**
     * Esperas antes de cada repetición de un envío: las tres primeras de la recarga de Chrome.
     * La de 30 s, desde que el registro lo vio el 06/10 y el 09/10: aMule volvió entre 1 y 6 s,
     * y el 04/10 tardó más y se perdió un enlace. Mientras, se le dice al usuario por qué tarda
     * (ver [AvisoDeEspera]).
     */
    val ESPERAS_MS = listOf(1_000L, 5_000L, 30_000L)

    /**
     * Las de una pestaña, solo las dos primeras: si se rinde no se pierde nada, queda el error
     * con su botón de reintentar.
     */
    val ESPERAS_PESTANA_MS = ESPERAS_MS.take(2)

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
        hechos < ESPERAS_PESTANA_MS.size && colgoLaPestana(descripcion, hechos)

    /**
     * Conecta y después hace [resto]. Lo que falle después de conectar —salvo tardar
     * demasiado— sale como [ServidorColgo].
     *
     * Se distingue por el momento y no por el tipo de excepción, que depende de la versión de
     * Android: un servicio apagado falla **al conectar** (`ConnectException`), y colgar sin
     * contestar, siempre **al leer**.
     */
    inline fun <T> HttpURLConnection.conectarY(resto: HttpURLConnection.() -> T): T {
        // Aquí fallan «apagado» y «no responde», que no se repiten.
        try {
            connect()
        } catch (e: IOException) {
            Registro.traza("Red") { "✗ No conecta: ${Registro.motivo(e)}" }
            throw e
        }
        return try {
            resto()
        } catch (e: SocketTimeoutException) {
            // Conectó pero no contesta a tiempo: está vivo y ocupado, no reiniciándose.
            Registro.traza("Red") { "✗ Conecta pero no contesta a tiempo" }
            throw e
        } catch (e: IOException) {
            Registro.traza("Red") { "✗ Conecta y cuelga: ${Registro.motivo(e)}" }
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

        for ((n, espera) in ESPERAS_MS.withIndex()) {
            Registro.traza("Reintentos") { "Se repite en ${espera / 1000} s (${n + 1} de ${ESPERAS_MS.size})" }
            currentCoroutineContext()[AvisoDeEspera]?.alEsperar?.invoke(espera)
            esperar(espera)
            try {
                val hecho = intento()
                Registro.traza("Reintentos") { "Sale a la repetición ${n + 1}" }
                return hecho
            } catch (otro: ServidorColgo) {
                // Sigue colgando: a la siguiente espera.
            } catch (otro: ConnectException) {
                // Todavía arrancando: a la siguiente espera.
            }
        }

        Registro.traza("Reintentos") { "Se rinde tras ${ESPERAS_MS.size} repeticiones" }
        throw primero
    }
}

/** El servidor conectó y después colgó, o cortó a medias. Ver [Reintentos]. */
class ServidorColgo(causa: IOException) : IOException(causa)

/**
 * Para quien espera un envío y quiere saber que se está repitiendo, y decirle al usuario por
 * qué tarda: [alEsperar] recibe cada espera antes de hacerla.
 *
 * Va en el contexto de la corrutina y no como parámetro, para no cruzar los cuatro conectores,
 * que no saben nada de esto.
 */
class AvisoDeEspera(val alEsperar: (Long) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AvisoDeEspera>
}
