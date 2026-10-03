package com.homelab.panel

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Cuándo se repite algo que el servidor dejó sin contestar. Ver [Reintentos].
 *
 * Aquí solo la decisión, que es cuenta pura y vale igual en el móvil. Lo que hace de verdad
 * el HttpURLConnection del móvil cuando el servidor cuelga se midió en el móvil, y la entrega a
 * aMule entera también, contra un aMule de mentira: ver `atrio-medidas`, fuera del repositorio.
 */
class ReintentosTest {

    private fun colgo() = ServidorColgo(IOException("unexpected end of stream"))

    /** Lo que sale en cada intento, en orden: un número es un éxito con ese código, una excepción se lanza. */
    private class Guion(vararg pasos: Any) {
        private val pendientes = pasos.toMutableList()
        var intentos = 0
        val esperas = mutableListOf<Long>()

        fun intento(): Int {
            intentos++
            val paso = pendientes.removeAt(0)
            if (paso is Exception) throw paso
            return paso as Int
        }
    }

    private suspend fun ejecutar(guion: Guion): Int =
        Reintentos.repetirSiCuelga({ guion.esperas += it }) { guion.intento() }

    @Test
    fun `si sale a la primera no espera nada`() = runTest {
        val guion = Guion(200)
        assertEquals(200, ejecutar(guion))
        assertEquals(emptyList<Long>(), guion.esperas)
    }

    /** El caso del aMule del autor: cuelga una vez y al segundo lo siguiente ya entra. */
    @Test
    fun `si cuelga, repite al segundo`() = runTest {
        val guion = Guion(colgo(), 200)
        assertEquals(200, ejecutar(guion))
        assertEquals(listOf(1_000L), guion.esperas)
    }

    /** Mientras vuelve a arrancar, el puerto está cerrado: eso no corta la tanda. */
    @Test
    fun `si al repetir aun esta arrancando, espera otra vez`() = runTest {
        val guion = Guion(colgo(), ConnectException("arrancando"), 200)
        assertEquals(200, ejecutar(guion))
        assertEquals(listOf(1_000L, 5_000L), guion.esperas)
    }

    /** Tres intentos como mucho, y lo que se cuenta al final es lo que pasó: que colgó. */
    @Test
    fun `si no deja de colgar, se rinde con el primer fallo`() = runTest {
        val primero = colgo()
        val guion = Guion(primero, colgo(), ConnectException("arrancando"))
        try {
            ejecutar(guion)
            fail("tenía que rendirse")
        } catch (e: ServidorColgo) {
            assertSame(primero, e)
        }
        assertEquals(3, guion.intentos)
        assertEquals(listOf(1_000L, 5_000L), guion.esperas)
    }

    /** Un servicio apagado de entrada se avisa al momento, como siempre. */
    @Test
    fun `apagado de entrada no se repite`() = runTest {
        val guion = Guion(ConnectException("apagado"), 200)
        try {
            ejecutar(guion)
            fail("no tenía que repetir")
        } catch (e: ConnectException) {
        }
        assertEquals(1, guion.intentos)
        assertEquals(emptyList<Long>(), guion.esperas)
    }

    /** Conecta pero no contesta a tiempo: está vivo y ocupado, y repetir solo alargaría la espera. */
    @Test
    fun `tardar demasiado no se repite`() = runTest {
        val guion = Guion(SocketTimeoutException("lento"), 200)
        try {
            ejecutar(guion)
            fail("no tenía que repetir")
        } catch (e: SocketTimeoutException) {
        }
        assertEquals(1, guion.intentos)
    }

    /** Y un fallo distinto a mitad de la tanda la corta, con ese fallo. */
    @Test
    fun `otro fallo a mitad de la tanda la corta`() = runTest {
        val guion = Guion(colgo(), SocketTimeoutException("lento"), 200)
        try {
            ejecutar(guion)
            fail("tenía que cortar")
        } catch (e: SocketTimeoutException) {
        }
        assertEquals(2, guion.intentos)
    }

    // -------------------------------------------------------------------------------
    // La pestaña, que decide por el nombre del error de red.
    // -------------------------------------------------------------------------------

    /** Lo que vio el autor en la pantalla de error de aMule. */
    @Test
    fun `la pestana repite el ERR_EMPTY_RESPONSE dos veces`() {
        assertTrue(Reintentos.repetirLaPestana("net::ERR_EMPTY_RESPONSE", 0))
        assertTrue(Reintentos.repetirLaPestana("net::ERR_EMPTY_RESPONSE", 1))
        assertFalse(Reintentos.repetirLaPestana("net::ERR_EMPTY_RESPONSE", 2))
    }

    @Test
    fun `cortar a medias cuenta como colgar`() {
        assertTrue(Reintentos.repetirLaPestana("net::ERR_CONNECTION_RESET", 0))
        assertTrue(Reintentos.repetirLaPestana("net::ERR_CONNECTION_CLOSED", 0))
    }

    /** Rechazada solo cuenta dentro de una tanda: de entrada es un servicio apagado. */
    @Test
    fun `rechazada solo se repite si ya habia colgado`() {
        assertFalse(Reintentos.repetirLaPestana("net::ERR_CONNECTION_REFUSED", 0))
        assertTrue(Reintentos.repetirLaPestana("net::ERR_CONNECTION_REFUSED", 1))
    }

    @Test
    fun `lo demas sale al momento`() {
        for (otro in listOf(
            "net::ERR_CONNECTION_TIMED_OUT",
            "net::ERR_NAME_NOT_RESOLVED",
            "net::ERR_ADDRESS_UNREACHABLE",
            "net::ERR_CERT_AUTHORITY_INVALID",
            "",
            null
        )) {
            assertFalse(otro.toString(), Reintentos.repetirLaPestana(otro, 0))
            assertFalse(otro.toString(), Reintentos.colgoLaPestana(otro, 1))
        }
    }

    /** Al rendirse, la pantalla de error dice que colgó, aunque lo último fuera un rechazo. */
    @Test
    fun `al rendirse se cuenta como colgado`() {
        assertTrue(Reintentos.colgoLaPestana("net::ERR_EMPTY_RESPONSE", 2))
        assertTrue(Reintentos.colgoLaPestana("net::ERR_CONNECTION_REFUSED", 2))
    }
}
