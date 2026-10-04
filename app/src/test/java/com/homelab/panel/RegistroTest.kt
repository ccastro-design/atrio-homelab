package com.homelab.panel

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.net.SocketException
import java.net.URLEncoder

/**
 * Que al registro no llegue ningún secreto, y que lo que llegue se reconozca. Ver [Registro].
 */
class RegistroTest {

    private fun sinRastro(texto: String, vararg secretos: String) =
        secretos.forEach { assertFalse("«$it» sigue en «$texto»", texto.contains(it)) }

    /** El `name=` de SABnzbd lleva dentro, codificada, la dirección del buscador con su clave. */
    @Test
    fun `de SABnzbd no queda ninguna de las dos claves`() {
        val buscador = "https://indexer.example/api?t=get&id=123&apikey=CLAVEBUSCADOR"
        val url = "http://192.168.1.254:8086/api?mode=addurl&output=json" +
            "&name=" + URLEncoder.encode(buscador, "UTF-8") + "&apikey=CLAVESAB"

        val limpio = Registro.sinSecretos(url)

        sinRastro(limpio, "CLAVEBUSCADOR", "CLAVESAB", "indexer")
        assertTrue(limpio.startsWith("http://192.168.1.254:8086/api?mode=addurl&output=json&name="))
    }

    /** En los buscadores Newznab la clave va en `r=` y el usuario en `i=`. */
    @Test
    fun `de un enlace Newznab no queda ni la clave ni el usuario`() {
        sinRastro(Registro.sinSecretos("https://indexer.example/getnzb/x.nzb?i=12345&r=CLAVENZB"), "CLAVENZB", "12345")
    }

    @Test
    fun `el usuario y la contraseña de una direccion se tachan`() {
        assertEquals("http://•••@192.168.1.254:9091/", Registro.sinSecretos("http://candido:clave@192.168.1.254:9091/"))
    }

    /** Chromium admite una arroba suelta en la contraseña: parte por la última. */
    @Test
    fun `una contraseña con arroba se tacha entera`() {
        assertEquals("http://•••@192.168.1.254:9091/", Registro.sinSecretos("http://candido:p@ss@192.168.1.254:9091/"))
    }

    @Test
    fun `un cuerpo de formulario tampoco deja la contraseña`() {
        sinRastro(Registro.sinSecretos("pass=SECRETO&submit=Submit"), "SECRETO")
        sinRastro(Registro.sinSecretos("cookie amuleweb_session_id=SECRETO"), "SECRETO")
        sinRastro(Registro.sinSecretos("https://x.example/#access_token=SECRETO"), "SECRETO")
        sinRastro(Registro.sinSecretos("{\"apiKey\": \"SECRETO\"}"), "SECRETO")
    }

    @Test
    fun `un texto sin secretos no cambia`() {
        val texto = "Contraseña: 200, entra (1532 caracteres)"
        assertEquals(texto, Registro.sinSecretos(texto))
    }

    @Test
    fun `un texto enorme se corta`() {
        assertTrue(Registro.sinSecretos("x".repeat(50_000)).length <= 2_001)
    }

    @Test
    fun `de un ed2k queda el nombre del fichero y su tamaño`() {
        val ed2k = "ed2k://|file|Mi%20Pelicula.mkv|734003200|0123456789ABCDEF0123456789ABCDEF|/"
        assertEquals("ed2k «Mi Pelicula.mkv» (734003200 bytes)", Registro.enlace(ed2k))
    }

    /** En un ed2k el «+» es parte del nombre. */
    @Test
    fun `el mas de un nombre ed2k no se vuelve espacio`() {
        val ed2k = "ed2k://|file|C++%20Primer.pdf|1234|0123456789ABCDEF0123456789ABCDEF|/"
        assertEquals("ed2k «C++ Primer.pdf» (1234 bytes)", Registro.enlace(ed2k))
    }

    /** Los rastreadores privados llevan la clave del usuario en la dirección de anuncio. */
    @Test
    fun `de un magnet queda el nombre y nunca los rastreadores`() {
        val magnet = "magnet:?xt=urn:btih:ABCDEF1234567890ABCD&dn=Ubuntu+24.04" +
            "&tr=https%3A%2F%2Ftracker.example%2Fannounce%3Fpasskey%3DSECRETO"

        val limpio = Registro.enlace(magnet)

        sinRastro(limpio, "SECRETO", "tracker")
        assertTrue(limpio.contains("«Ubuntu 24.04»"))
    }

    /** Hay rastreadores que meten la clave en la ruta, donde ningún nombre la delata. */
    @Test
    fun `de una direccion web queda solo el servidor`() {
        val limpio = Registro.enlace("https://tracker.example/rss/download/123/CLAVERSS/x.torrent")

        sinRastro(limpio, "CLAVERSS", "download")
        assertTrue(limpio.startsWith("https://tracker.example "))
    }

    @Test
    fun `un fichero metido en la direccion no se copia`() {
        sinRastro(Registro.enlace("data:application/x-bittorrent;base64,ZDg6YW5ub3VuY2U="), "ZDg6YW5ub3VuY2U")
    }

    @Test
    fun `el origen sale aunque la direccion este mal escrita`() {
        assertEquals("http://hispashare.com", Registro.origen("http:\\\\hispashare.com"))
        assertEquals("http://mi_nas:8080", Registro.origen("http://mi_nas:8080/admin"))
        assertEquals("http://192.168.1.254:9091", Registro.origen("http://candido:clave@192.168.1.254:9091/"))
    }

    @Test
    fun `una excepcion envuelta se lee en una linea sin repetirse`() {
        val e = ServidorColgo(SocketException("Connection reset"))
        assertEquals("ServidorColgo ← SocketException: Connection reset", Registro.motivo(e))
    }

    /** `HttpURLConnection` mete la dirección entera en el mensaje de un 404. */
    @Test
    fun `el mensaje de una excepcion tampoco deja claves`() {
        val e = FileNotFoundException("http://192.168.1.254:8086/api?mode=addurl&apikey=SECRETO")
        sinRastro(Registro.motivo(e), "SECRETO")
    }

    /**
     * El lector de JSON de Android copia en el mensaje el texto entero que no entendió; el de las
     * pruebas no, así que aquí se construye el mensaje a mano.
     */
    @Test
    fun `un JSON roto no se copia en el registro`() {
        val android = JSONException("Unterminated array at character 374 of {\"field\":\"apiKey\",\"value\":\"SECRETO\"}")
        val kotlinx = IllegalArgumentException("Unexpected JSON token at offset 61\nJSON input: {\"value\":\"SECRETO\"}")

        sinRastro(Registro.motivo(android), "SECRETO")
        sinRastro(Registro.motivo(kotlinx), "SECRETO")
        assertTrue(Registro.motivo(android).contains("at character 374"))
    }
}
