package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La decisión de usar las direcciones de casa o las de fuera.
 *
 * Es la pieza donde un fallo tiene la peor consecuencia de toda la aplicación: dar por
 * buena la máquina de otra persona y mandarle las contraseñas guardadas. Y es la más
 * incómoda de probar a mano, porque el caso que importa exige estar físicamente en otra
 * casa con un equipo que responda en la misma dirección.
 */
class NetworkResolverTest {

    private val miCasa = listOf("WIFI_DE_CASA")

    @Test
    fun `en mi wifi usa las direcciones de casa`() {
        val v = NetworkResolver.decidir(
            wifi = "WIFI_DE_CASA",
            redesDeCasa = miCasa,
            descartada = false,
            respondeAlgo = true
        )

        assertEquals(false, v.away)
        assertNull("no hay nada que preguntar en la red propia", v.ask)
    }

    /**
     * El caso que dio origen a todo esto: en casa de un amigo con un equipo en la misma
     * dirección. Tiene que irse por la VPN **y** preguntar, nunca dar por buena la máquina.
     */
    @Test
    fun `en una wifi ajena donde algo responde va por vpn y pregunta`() {
        val v = NetworkResolver.decidir(
            wifi = "WIFI_DEL_AMIGO",
            redesDeCasa = miCasa,
            descartada = false,
            respondeAlgo = true
        )

        assertTrue("no debe fiarse de una red que no es suya", v.away)
        assertEquals("WIFI_DEL_AMIGO", v.ask)
    }

    @Test
    fun `en una wifi ajena donde no responde nada no molesta`() {
        val v = NetworkResolver.decidir(
            wifi = "WIFI_DE_UN_BAR",
            redesDeCasa = miCasa,
            descartada = false,
            respondeAlgo = false
        )

        assertTrue(v.away)
        assertNull("sin nada respondiendo no hay nada que aclarar", v.ask)
    }

    @Test
    fun `una wifi ya descartada no vuelve a preguntar`() {
        val v = NetworkResolver.decidir(
            wifi = "WIFI_DEL_AMIGO",
            redesDeCasa = miCasa,
            descartada = true,
            respondeAlgo = true
        )

        assertTrue(v.away)
        assertNull("el usuario ya dijo que no era suya", v.ask)
    }

    /**
     * Sin nombre de WiFi —por cable, sin permiso de ubicación o con la ubicación apagada—
     * no queda más que el método viejo: decidir por quién contesta.
     */
    @Test
    fun `sin nombre de wifi decide por quien responde`() {
        assertEquals(
            false,
            NetworkResolver.decidir("", miCasa, descartada = false, respondeAlgo = true).away
        )
        assertEquals(
            true,
            NetworkResolver.decidir("", miCasa, descartada = false, respondeAlgo = false).away
        )
    }

    /** Quien no ha declarado ninguna red se queda con el comportamiento de siempre. */
    @Test
    fun `sin redes declaradas decide por quien responde`() {
        val v = NetworkResolver.decidir(
            wifi = "CUALQUIERA",
            redesDeCasa = emptyList(),
            descartada = false,
            respondeAlgo = true
        )

        assertEquals(false, v.away)
        assertNull(v.ask)
    }

    /**
     * La regla de fondo: **teniendo redes declaradas, ninguna WiFi que no esté en la lista
     * puede acabar usando las direcciones locales.** Es lo único que impide entrar en la
     * máquina de otra persona, y por eso se comprueba con todas las combinaciones.
     */
    @Test
    fun `una wifi que no es suya nunca usa las direcciones de casa`() {
        listOf(true, false).forEach { descartada ->
            listOf(true, false).forEach { responde ->
                val v = NetworkResolver.decidir("AJENA", miCasa, descartada, responde)

                assertTrue(
                    "descartada=$descartada responde=$responde debería irse por la VPN",
                    v.away
                )
            }
        }
    }

    /** Un perfil guardado que no se entienda no puede dejar la aplicación sin decidir. */
    @Test
    fun `un perfil corrupto cae en automatico`() {
        assertEquals(
            NetworkProfile.AUTO,
            PanelConfig(profile = "LO-QUE-SEA").networkProfile
        )
        assertEquals(
            NetworkProfile.AUTO,
            PanelConfig(profile = "").networkProfile
        )
    }
}
