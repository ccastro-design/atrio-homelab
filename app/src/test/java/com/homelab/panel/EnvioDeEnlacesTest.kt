package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Los enlaces camino de un programa de descargas. Ver [EnvioDeEnlaces]. */
class EnvioDeEnlacesTest {

    private val magnet1 = "magnet:?xt=urn:btih:1111"
    private val magnet2 = "magnet:?xt=urn:btih:2222"

    @Test
    fun `de otra aplicacion abre el aviso con ese enlace`() {
        val e = EnvioDeEnlaces()
        e.deOtraAplicacion(magnet1)
        assertTrue(e.abierto)
        assertEquals(listOf(magnet1), e.enlaces)
    }

    /** Una ráfaga se junta sin repetidos, y el aviso ya está abierto desde el primero. */
    @Test
    fun `una rafaga desde una pestana se junta sin repetir`() {
        val e = EnvioDeEnlaces()
        e.dePestana(magnet1, LinkKind.MAGNET)
        assertTrue(e.abierto)
        assertTrue(e.recogiendo)

        e.dePestana(magnet2, LinkKind.MAGNET)
        e.dePestana(magnet1, LinkKind.MAGNET)
        assertEquals(listOf(magnet1, magnet2), e.enlaces)

        e.rafagaTerminada()
        assertFalse(e.recogiendo)
        assertTrue(e.abierto)
    }

    /** El tipo lo pone el primero que lo sepa; los siguientes de la ráfaga no lo cambian. */
    @Test
    fun `el tipo lo pone el primero que lo sabe`() {
        val e = EnvioDeEnlaces()
        e.dePestana("https://indexador/api?t=get&id=1", null)
        assertNull(e.tipo)
        e.dePestana("https://indexador/api?t=get&id=2", LinkKind.NZB)
        assertEquals(LinkKind.NZB, e.tipo)
        e.dePestana("https://indexador/api?t=get&id=3", LinkKind.TORRENT)
        assertEquals(LinkKind.NZB, e.tipo)
    }

    @Test
    fun `un fichero sustituye a los enlaces y trae su tipo`() {
        val e = EnvioDeEnlaces()
        e.dePestana(magnet1, LinkKind.MAGNET)
        val nzb = DownloadFile("x.nzb", "<nzb/>", LinkKind.NZB)

        e.deFichero(nzb)
        assertEquals(nzb, e.fichero)
        assertEquals(emptyList<String>(), e.enlaces)
        assertEquals(LinkKind.NZB, e.tipo)
        assertFalse(e.recogiendo)
        assertTrue(e.abierto)
    }

    /** El botón de la pestaña empieza de cero: nada de lo que hubiera de antes. */
    @Test
    fun `el boton de la pestana empieza de cero`() {
        val e = EnvioDeEnlaces()
        e.deFichero(DownloadFile("x.nzb", "<nzb/>", LinkKind.NZB))
        e.abrirDesdePestana()
        assertTrue(e.abierto)
        assertEquals(emptyList<String>(), e.enlaces)
        assertNull(e.tipo)
        assertNull(e.fichero)
        assertFalse(e.recogiendo)
    }

    @Test
    fun `el boton del panel abre sin enlaces`() {
        val e = EnvioDeEnlaces()
        e.abrirDesdePanel()
        assertTrue(e.abierto)
        assertEquals(emptyList<String>(), e.enlaces)
        assertFalse(e.recogiendo)
    }

    /** Al terminar no queda nada para la próxima vez. */
    @Test
    fun `cerrar lo deja todo limpio`() {
        val e = EnvioDeEnlaces()
        e.dePestana(magnet1, LinkKind.MAGNET)
        e.deFichero(DownloadFile("x.nzb", "<nzb/>", LinkKind.NZB))
        e.cerrar()
        assertFalse(e.abierto)
        assertEquals(emptyList<String>(), e.enlaces)
        assertNull(e.tipo)
        assertNull(e.fichero)
    }
}
