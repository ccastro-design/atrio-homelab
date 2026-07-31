package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Por qué un servicio se queda sin dirección con la que abrirse.
 *
 * Importa porque el síntoma es de los peores: tocabas la tarjeta y **no pasaba nada**, sin
 * mensaje ni pista, y lo normal era pensar que la aplicación se había colgado. El caso que
 * lo destapó es borrar un servidor: sus servicios se quedan en el panel apuntando a un
 * identificador que ya no existe.
 *
 * Se prueba aquí y no a mano porque montar cada caso en el móvil exige romper la
 * configuración a propósito, y los tres se distinguen por matices que a simple vista se
 * confunden.
 */
class SinDireccionTest {

    private val nas = Server(id = "nas", name = "NAS", hostHome = "192.168.1.254")

    private fun panel(servicio: Service, servidores: List<Server> = listOf(nas)) =
        PanelConfig(
            servers = servidores,
            groups = listOf(ServiceGroup(id = "g", name = "Grupo", services = listOf(servicio)))
        )

    @Test
    fun `un servicio con su servidor puesto se abre`() {
        val config = panel(Service(id = "s", name = "Jellyfin", serverId = "nas", port = 8096))

        assertNull(config.motivoSinDireccion(config.allServices.first(), away = false))
    }

    @Test
    fun `un servicio con direccion web propia se abre sin servidor`() {
        val config = panel(
            Service(id = "s", name = "Mi web", urlOwn = "https://elhumoviral.com")
        )

        assertNull(config.motivoSinDireccion(config.allServices.first(), away = false))
    }

    /** El caso que lo destapó: se borró el servidor y el servicio se quedó colgando. */
    @Test
    fun `si el servidor ya no existe lo dice`() {
        val config = panel(
            servicio = Service(id = "s", name = "Jellyfin", serverId = "nas", port = 8096),
            servidores = emptyList()
        )

        assertEquals(
            NoSePuedeAbrir.SERVIDOR_BORRADO,
            config.motivoSinDireccion(config.allServices.first(), away = false)
        )
    }

    @Test
    fun `un servidor sin ninguna direccion se distingue de uno borrado`() {
        val config = panel(
            servicio = Service(id = "s", name = "Jellyfin", serverId = "vacio", port = 8096),
            servidores = listOf(Server(id = "vacio", name = "Sin rellenar"))
        )

        assertEquals(
            NoSePuedeAbrir.SERVIDOR_SIN_DIRECCION,
            config.motivoSinDireccion(config.allServices.first(), away = false)
        )
    }

    @Test
    fun `sin servidor y sin direccion propia lo dice tambien`() {
        val config = panel(Service(id = "s", name = "A medio hacer"))

        assertEquals(
            NoSePuedeAbrir.NI_SERVIDOR_NI_DIRECCION,
            config.motivoSinDireccion(config.allServices.first(), away = false)
        )
    }

    /**
     * Estando fuera, un servidor sin dirección de fuera **no** es un servicio sin
     * dirección: se usa la de casa. Que no responda es otra cosa, y de eso ya avisa la
     * pantalla de error del navegador, no este aviso.
     */
    @Test
    fun `fuera de casa y sin direccion de vpn sigue teniendo la de casa`() {
        val config = panel(Service(id = "s", name = "Jellyfin", serverId = "nas", port = 8096))

        assertNull(config.motivoSinDireccion(config.allServices.first(), away = true))
    }

    /** Con dirección propia solo de casa pasa lo mismo: fuera se intenta con esa. */
    @Test
    fun `fuera de casa con direccion propia solo de casa se intenta igual`() {
        val config = panel(Service(id = "s", name = "Router", urlOwn = "http://192.168.1.1"))

        assertNull(config.motivoSinDireccion(config.allServices.first(), away = true))
    }
}
