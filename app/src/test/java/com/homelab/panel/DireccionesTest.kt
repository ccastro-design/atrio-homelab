package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Direcciones escritas a mano, con los fallos que la gente comete de verdad al teclearlas.
 *
 * El caso que trajo todo esto, encontrado usando la aplicación el 05/08/2026: una web
 * guardada como `http:\\hispashare.com`, con barras invertidas. **La pestaña la abría sin
 * una queja** —el WebView es Chromium y los navegadores enderezan esas barras en silencio—,
 * pero la comprobación de estado la marcaba como caída, porque va por otro camino y ese
 * camino construye un `java.net.URI`, que considera la barra invertida un carácter ilegal.
 *
 * O sea, **la aplicación se contradecía a sí misma**: cargaba la web y a la vez decía que no
 * respondía. Y lo que ve el usuario es lo segundo, que parece un error de la aplicación.
 * Lo era.
 */
class DireccionesTest {

    @Test
    fun `endereza las barras invertidas`() {
        assertEquals("http://hispashare.com", enderezarUrl("http:\\\\hispashare.com"))
        assertEquals("http://192.168.1.100/admin", enderezarUrl("http:\\\\192.168.1.100\\admin"))
    }

    /** Media barra mal también cuenta: se escapa una sola y el resto queda bien. */
    @Test
    fun `endereza aunque solo falle una barra`() {
        assertEquals("http://equipo:8080/panel", enderezarUrl("http:/\\equipo:8080/panel"))
    }

    @Test
    fun `quita los espacios de los extremos`() {
        assertEquals("https://equipo/", enderezarUrl("  https://equipo/  "))
    }

    /** Lo que ya está bien no se toca. */
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

    /** Y sigue sacándolo de las que están bien, que es lo que hacía antes. */
    @Test
    fun `saca el equipo de una direccion correcta`() {
        assertEquals("elhumoviral.com", hostOf("https://elhumoviral.com"))
        assertEquals("192.168.1.254", hostOf("http://192.168.1.254:8080/"))
    }
}
