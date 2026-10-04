package com.homelab.panel

import com.homelab.panel.LinkSender.Texto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Qué se le dice al usuario después de cada entrega. Ver [LinkSender.textoDe].
 *
 * Aquí se fija qué texto corresponde a cada resultado, para que nadie los cruce.
 */
class MensajesDeEnvioTest {

    private val nombre = "aMule"

    private fun aviso(entrega: Entrega) = LinkSender.textoDe(entrega, nombre)

    @Test
    fun `si salio bien no hay aviso`() {
        assertNull(aviso(Entrega.Hecha))
    }

    @Test
    fun `cada fallo tiene el suyo`() {
        assertEquals(Texto(R.string.send_duplicate, listOf(nombre)), aviso(Entrega.YaEstaba))
        assertEquals(Texto(R.string.send_bad_password, listOf(nombre)), aviso(Entrega.ContrasenaMala))
        assertEquals(Texto(R.string.send_no_password, listOf(nombre)), aviso(Entrega.FaltaContrasena))
        assertEquals(Texto(R.string.send_no_api_key, listOf(nombre)), aviso(Entrega.FaltaClaveApi))
        assertEquals(Texto(R.string.send_bad_api_key, listOf(nombre)), aviso(Entrega.ClaveApiMala))
        assertEquals(Texto(R.string.send_not_whitelisted, listOf(nombre)), aviso(Entrega.FueraDeLaListaBlanca))
        assertEquals(Texto(R.string.send_host_not_allowed, listOf(nombre)), aviso(Entrega.NombreDeEquipoNoPermitido))
        assertEquals(Texto(R.string.send_bad_answer, listOf(nombre)), aviso(Entrega.RespuestaIninteligible))
        assertEquals(Texto(R.string.send_not_accepted, listOf(nombre)), aviso(Entrega.NoAceptada))
        assertEquals(Texto(R.string.send_file_not_supported, listOf(nombre)), aviso(Entrega.NoSabeSubirFicheros))
    }

    /** El código va como número: el texto lo pinta con `%d`. */
    @Test
    fun `un codigo http lleva el numero`() {
        assertEquals(Texto(R.string.send_http_error, listOf(nombre, 500)), aviso(Entrega.CodigoHttp(500)))
    }

    /** SABnzbd a veces dice que no sin decir por qué: entonces se dice que no lo dio. */
    @Test
    fun `un rechazo sin motivo dice que no lo dio`() {
        assertEquals(
            Texto(R.string.send_refused, listOf(nombre, Texto(R.string.send_reason_unknown))),
            aviso(Entrega.Rechazada(null))
        )
    }

    /** Transmission pasa su resultado tal cual, aunque venga vacío. */
    @Test
    fun `un rechazo con su motivo lo repite tal cual`() {
        assertEquals(Texto(R.string.send_refused, listOf(nombre, "invalid or corrupt torrent file")),
            aviso(Entrega.Rechazada("invalid or corrupt torrent file")))
        assertEquals(Texto(R.string.send_refused, listOf(nombre, "")), aviso(Entrega.Rechazada("")))
    }

    @Test
    fun `si llego todo es un aviso breve`() {
        assertEquals(Desenlace.Todo(4), desenlace(4, 4, emptyList()))
    }

    /** El 04/10/2026 llegaron 3 de 4 a aMule y el aviso se calló el motivo. */
    @Test
    fun `si llego solo una parte se dice por que fallo el resto`() {
        val colgo = "«aMule» ha cortado la conexión sin contestar."
        assertEquals(Desenlace.Parte(3, 4, listOf(colgo)), desenlace(4, 3, listOf(colgo)))
    }

    @Test
    fun `los motivos repetidos se dicen una vez`() {
        assertEquals(Desenlace.Parte(1, 4, listOf("X.", "Y.")), desenlace(4, 1, listOf("X.", "X.", "Y.")))
    }

    @Test
    fun `si no llego ninguno se dice el primer motivo`() {
        assertEquals(Desenlace.Nada("X."), desenlace(2, 0, listOf("X.", "Y.")))
        assertEquals(Desenlace.Nada(null), desenlace(0, 0, emptyList()))
    }
}
