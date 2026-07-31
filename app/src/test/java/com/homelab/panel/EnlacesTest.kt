package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reconocimiento de enlaces de descarga por su dirección.
 *
 * Lo que se prueba aquí no es solo lo que reconoce, sino **lo que no puede reconocer**, que
 * es lo que obliga a que el tipo viaje junto al enlace desde el navegador: comprobado el
 * 30/07/2026 contra un servidor de pruebas, un enlace de buscador de NZB llegaba al cuadro
 * de envío y salía «eso no parece un enlace que la aplicación pueda enviar».
 */
class EnlacesTest {

    @Test
    fun `reconoce los enlaces que llevan su tipo en la direccion`() {
        assertEquals(LinkKind.ED2K, Links.detect("ed2k://|file|peli.avi|123|ABC|/"))
        assertEquals(LinkKind.MAGNET, Links.detect("magnet:?xt=urn:btih:abcdef"))
        assertEquals(LinkKind.TORRENT, Links.detect("https://sitio.com/f/peli.torrent"))
        assertEquals(LinkKind.NZB, Links.detect("https://sitio.com/f/peli.nzb"))
    }

    /** La extensión se mira en la ruta, no en la dirección entera. */
    @Test
    fun `reconoce la extension aunque lleve parametros detras`() {
        assertEquals(LinkKind.TORRENT, Links.detect("https://sitio.com/f/peli.torrent?id=9"))
        assertEquals(LinkKind.NZB, Links.detect("https://sitio.com/f/peli.nzb?apikey=xxx"))
    }

    /**
     * **El caso que obliga a llevar el tipo aparte.** Así sirven los NZB casi todos los
     * buscadores, y los indexadores de torrent hacen lo mismo: la dirección no dice nada y
     * el tipo solo se sabe por la cabecera con la que responde el servidor, que la ve el
     * navegador. Si esto algún día devolviera algo distinto de `null`, sería que alguien
     * ha metido adivinanzas por el nombre, que es justo lo que no se quiere.
     */
    @Test
    fun `no reconoce el enlace de un buscador de nzb`() {
        assertNull(Links.detect("https://indexador.com/api?t=get&id=123&apikey=xxx"))
        assertNull(Links.detect("https://indexador.com/download/123456"))
    }

    /** Una dirección web a secas no es un enlace de descarga. */
    @Test
    fun `una pagina normal no es un enlace de descarga`() {
        assertNull(Links.detect("https://elhumoviral.com"))
        assertNull(Links.detect("http://192.168.1.254:8080/"))
    }

    @Test
    fun `saca todos los enlaces de un texto pegado, sin repetidos`() {
        val texto = """
            magnet:?xt=urn:btih:aaa
            magnet:?xt=urn:btih:bbb
            magnet:?xt=urn:btih:aaa
            esto no es un enlace
        """.trimIndent()

        assertEquals(2, Links.extractAll(texto).size)
    }
}
