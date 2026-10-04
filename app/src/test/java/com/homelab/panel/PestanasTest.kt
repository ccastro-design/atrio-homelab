package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Las pestañas abiertas. Ver [Pestanas].
 *
 * La regla que más importa: la que se está viendo no cambia salvo que el usuario lo pida.
 * Mover otra de sitio o volver a abrir un servicio no debe saltar a una página distinta.
 */
class PestanasTest {

    private fun servicio(id: String) = Service(id = id, name = id.uppercase())

    /** Tres pestañas, a, b y c, en ese orden. */
    private fun tres(): Pestanas = Pestanas().apply {
        abrir(servicio("a"), "http://a")
        abrir(servicio("b"), "http://b")
        abrir(servicio("c"), "http://c")
    }

    private fun Pestanas.orden() = lista.joinToString("") { it.service.id }
    private fun Pestanas.vista() = lista[activa].service.id

    @Test
    fun `abrir un servicio nuevo lo pone al final y lo ensena`() {
        val p = tres()
        assertEquals("abc", p.orden())
        assertEquals("c", p.vista())
    }

    /** Abrirlo otra vez no duplica la pestaña: se va a la que ya había. */
    @Test
    fun `abrir uno que ya estaba va a su pestana`() {
        val p = tres()
        p.abrir(servicio("a"), "http://a")
        assertEquals("abc", p.orden())
        assertEquals("a", p.vista())
    }

    /** Si cambió la dirección (casa o fuera), se recarga; si no, se deja como está. */
    @Test
    fun `abrir uno que ya estaba solo recarga si cambio la direccion`() {
        val p = tres()
        val a = p.lista[0]

        val antes = a.loadId
        p.abrir(servicio("a"), "http://a")
        assertEquals(antes, a.loadId)

        p.abrir(servicio("a"), "http://a-fuera")
        assertEquals(antes + 1, a.loadId)
        assertEquals("http://a-fuera", a.url)
    }

    @Test
    fun `mover la que se ve la sigue ensenando`() {
        val p = tres()
        p.activa = 0
        p.mover(0, 2)
        assertEquals("bca", p.orden())
        assertEquals("a", p.vista())
    }

    @Test
    fun `mover otra no cambia la que se ve`() {
        val p = tres()
        p.activa = 1                       // se ve «b»
        p.mover(0, 2)                      // «a» pasa al final: bca
        assertEquals("bca", p.orden())
        assertEquals("b", p.vista())

        p.mover(2, 0)                      // «a» vuelve al principio: abc
        assertEquals("abc", p.orden())
        assertEquals("b", p.vista())

        p.mover(2, 0)                      // «c» al principio: cab
        assertEquals("cab", p.orden())
        assertEquals("b", p.vista())
    }

    @Test
    fun `mover fuera de la lista no hace nada`() {
        val p = tres()
        p.mover(0, 5)
        p.mover(-1, 1)
        p.mover(1, 1)
        assertEquals("abc", p.orden())
    }

    @Test
    fun `cerrar la que se ve ensena la siguiente`() {
        val p = tres()
        p.activa = 1
        p.cerrar(1)
        assertEquals("ac", p.orden())
        assertEquals("c", p.vista())
    }

    @Test
    fun `cerrar la ultima ensena la anterior`() {
        val p = tres()
        p.cerrar(2)
        assertEquals("ab", p.orden())
        assertEquals("b", p.vista())
    }

    @Test
    fun `cerrar todas deja la lista vacia`() {
        val p = tres()
        p.cerrarTodas()
        assertTrue(p.vacia)
        assertEquals(0, p.activa)
    }

    /** Al pasar de casa a fuera cada pestaña va a su dirección nueva; las que no tienen, se quedan. */
    @Test
    fun `cambiar de direcciones recarga solo las que cambian`() {
        val p = tres()
        val cargas = p.lista.map { it.loadId }

        p.cambiarDirecciones { s -> if (s.id == "a") "http://a-fuera" else if (s.id == "b") "" else "http://c" }

        assertEquals("http://a-fuera", p.lista[0].url)
        assertEquals(cargas[0] + 1, p.lista[0].loadId)
        assertEquals("http://b", p.lista[1].url)        // sin dirección de fuera: se queda
        assertEquals(cargas[1], p.lista[1].loadId)
        assertEquals(cargas[2], p.lista[2].loadId)      // la misma: no se recarga
    }
}
