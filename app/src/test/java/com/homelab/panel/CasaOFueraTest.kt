package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo que se hace con una decisión de casa o fuera. Ver [CasaOFuera].
 *
 * La decisión en sí (qué red es, qué responde) está probada en `NetworkResolverTest`; aquí,
 * que lo decidido se aplique entero, incluida la red por la que hay que preguntar.
 */
class CasaOFueraTest {

    @Test
    fun `de partida se esta en casa y no se pregunta nada`() {
        val red = CasaOFuera()
        assertFalse(red.fuera)
        assertNull(red.redDesconocida)
    }

    @Test
    fun `aplicar un fuera con red desconocida pone las dos cosas`() {
        val red = CasaOFuera()
        red.aplicar(NetworkVerdict(away = true, ask = "WIFI_DEL_BAR"))
        assertTrue(red.fuera)
        assertEquals("WIFI_DEL_BAR", red.redDesconocida)
    }

    /** Volver a casa quita la pregunta pendiente: ya no hay red extraña por la que preguntar. */
    @Test
    fun `volver a casa quita la pregunta`() {
        val red = CasaOFuera()
        red.aplicar(NetworkVerdict(away = true, ask = "WIFI_DEL_BAR"))
        red.aplicar(NetworkVerdict(away = false))
        assertFalse(red.fuera)
        assertNull(red.redDesconocida)
    }
}
