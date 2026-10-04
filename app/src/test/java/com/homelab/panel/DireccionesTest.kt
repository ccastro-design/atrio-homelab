package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direcciones escritas a mano, con los fallos que la gente comete de verdad al teclearlas.
 *
 * Una web guardada como `http:\\hispashare.com`: la pestaña la abría —los navegadores
 * enderezan esas barras en silencio—, pero la comprobación de estado la marcaba como caída,
 * porque construye un `java.net.URI`, que considera la barra invertida un carácter ilegal.
 */
class DireccionesTest {

    @Test
    fun `endereza las barras invertidas`() {
        assertEquals("http://hispashare.com", enderezarUrl("http:\\\\hispashare.com"))
        assertEquals("http://192.168.1.100/admin", enderezarUrl("http:\\\\192.168.1.100\\admin"))
    }

    @Test
    fun `endereza aunque solo falle una barra`() {
        assertEquals("http://equipo:8080/panel", enderezarUrl("http:/\\equipo:8080/panel"))
    }

    @Test
    fun `quita los espacios de los extremos`() {
        assertEquals("https://equipo/", enderezarUrl("  https://equipo/  "))
    }

    @Test
    fun `deja igual una direccion correcta`() {
        assertEquals("https://equipo:8443/ruta?a=1", enderezarUrl("https://equipo:8443/ruta?a=1"))
    }

    /**
     * **El fallo tal y como se veía.** Sin enderezar, `hostOf` devuelve cadena vacía, y sin
     * equipo al que llamar la comprobación de estado no tiene a dónde conectarse: el
     * servicio sale como «Sin conexión» aunque su web cargue perfectamente.
     */
    @Test
    fun `saca el equipo aunque las barras esten invertidas`() {
        assertEquals("hispashare.com", hostOf("http:\\\\hispashare.com"))
        assertEquals("192.168.1.100", hostOf("http:\\\\192.168.1.100\\admin"))
    }

    @Test
    fun `saca el equipo de una direccion correcta`() {
        assertEquals("elhumoviral.com", hostOf("https://elhumoviral.com"))
        assertEquals("192.168.1.254", hostOf("http://192.168.1.254:8080/"))
    }
}
