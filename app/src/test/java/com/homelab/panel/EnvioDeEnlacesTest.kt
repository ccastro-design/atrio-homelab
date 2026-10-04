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
    private val ed2kA = "ed2k://|file|a.mkv|1|AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA|/"
    private val ed2kB = "ed2k://|file|b.mkv|2|BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB|/"
    private val ed2kC = "ed2k://|file|c.mkv|3|CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC|/"

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

    /**
     * Lo que llega a mitad de un envío no toca el que está en marcha: espera y sale como tanda
     * nueva al cerrar. Antes arrancaba un segundo envío a la vez y uno de los dos se perdía.
     */
    @Test
    fun `lo que llega durante un envio espera su turno`() {
        val e = EnvioDeEnlaces()
        e.dePestana(magnet1, LinkKind.MAGNET)
        e.rafagaTerminada()
        e.empiezaEnvio(listOf(magnet1))

        e.dePestana(magnet2, LinkKind.MAGNET)
        assertEquals(listOf(magnet1), e.enlaces)
        assertFalse(e.recogiendo)

        val tanda = e.tanda
        e.cerrar()
        assertTrue(e.abierto)
        assertEquals(listOf(magnet2), e.enlaces)
        assertEquals(LinkKind.MAGNET, e.tipo)
        assertEquals(tanda + 1, e.tanda)
        assertFalse(e.enviando)
    }

    /** Dos ed2k tocados seguidos en el navegador: el segundo ya no sustituye al primero. */
    @Test
    fun `un enlace de otra aplicacion durante un envio no sustituye al que se envia`() {
        val e = EnvioDeEnlaces()
        e.deOtraAplicacion(ed2kA)
        e.empiezaEnvio(listOf(ed2kA))
        e.deOtraAplicacion(ed2kB)
        assertEquals(listOf(ed2kA), e.enlaces)

        e.cerrar()
        assertEquals(listOf(ed2kB), e.enlaces)
        assertTrue(e.abierto)
    }

    /** Los del mismo tipo que esperaban salen juntos; uno de otro tipo, en su propia tanda. */
    @Test
    fun `la cola junta los del mismo tipo y separa los demas`() {
        val e = EnvioDeEnlaces()
        e.deOtraAplicacion(ed2kA)
        e.empiezaEnvio(listOf(ed2kA))
        e.deOtraAplicacion(ed2kB)
        e.deOtraAplicacion(ed2kC)
        e.dePestana(magnet1, LinkKind.MAGNET)

        e.cerrar()
        assertEquals(listOf(ed2kB, ed2kC), e.enlaces)

        e.empiezaEnvio(listOf(ed2kB, ed2kC))
        e.cerrar()
        assertEquals(listOf(magnet1), e.enlaces)
        assertEquals(LinkKind.MAGNET, e.tipo)

        e.empiezaEnvio(listOf(magnet1))
        e.cerrar()
        assertFalse(e.abierto)
    }

    @Test
    fun `un fichero durante un envio tambien espera`() {
        val e = EnvioDeEnlaces()
        e.deOtraAplicacion(ed2kA)
        e.empiezaEnvio(listOf(ed2kA))
        val nzb = DownloadFile("x.nzb", "<nzb/>", LinkKind.NZB)
        e.deFichero(nzb)
        assertNull(e.fichero)

        e.cerrar()
        assertEquals(nzb, e.fichero)
        assertEquals(LinkKind.NZB, e.tipo)
        assertTrue(e.abierto)
    }

    /** Lo que llegó entre decidir y empezar a enviar no se pierde: pasa a la cola. */
    @Test
    fun `lo que no va en el lote pasa a la cola`() {
        val e = EnvioDeEnlaces()
        e.dePestana(magnet1, LinkKind.MAGNET)
        e.dePestana(magnet2, LinkKind.MAGNET)
        e.empiezaEnvio(listOf(magnet1))

        e.cerrar()
        assertEquals(listOf(magnet2), e.enlaces)
        assertTrue(e.abierto)
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
