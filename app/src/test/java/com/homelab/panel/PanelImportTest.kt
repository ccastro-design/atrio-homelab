package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El lector de paneles ajenos: el `config.yml` de Homer y el JSON de Homarr, Heimdall y
 * compañía.
 *
 * Probarlo a mano cuesta un rato largo —conseguir un fichero, pasarlo al móvil, importarlo,
 * mirar el resultado— y por eso se probaba poco. Aquí caben todos los casos raros juntos:
 * ficheros a medias, sin puertos, con direcciones repetidas o con basura dentro.
 */
class PanelImportTest {

    // ---- Homer ----

    private val homer = """
        title: "Mi panel"
        subtitle: "Servicios de casa"
        services:
          - name: "Multimedia"
            items:
              - name: "Jellyfin"
                subtitle: "Películas"
                logo: "assets/icons/jellyfin.png"
                url: "http://192.168.1.254:8096"
              - name: "Plex"
                url: "http://192.168.1.254:32400/web"
          - name: "Red"
            items:
              - name: "Router"
                url: "http://192.168.1.1"
    """.trimIndent()

    @Test
    fun `lee un config yml de Homer`() {
        val r = PanelImport.parse(homer)

        assertNotNull("el fichero de Homer debe reconocerse", r)
        assertEquals("Mi panel", r!!.title)
        assertEquals("Servicios de casa", r.subtitle)
        assertEquals(2, r.groups.size)
        assertEquals(3, r.serviceCount)
    }

    /**
     * Lo que más valor tiene de la importación: los servicios que están en la misma máquina
     * se agrupan en un servidor, para que cambiar la IP sea un cambio en un sitio.
     */
    @Test
    fun `agrupa por maquina y crea los servidores`() {
        val r = PanelImport.parse(homer)!!

        val equipos = r.servers.map { it.hostHome }.toSet()

        assertTrue("debe reconocer el NAS", equipos.contains("192.168.1.254"))
        assertTrue("y el router", equipos.contains("192.168.1.1"))
        assertEquals("dos máquinas distintas, dos servidores", 2, r.servers.size)
    }

    @Test
    fun `conserva los puertos y las rutas`() {
        val r = PanelImport.parse(homer)!!
        val servicios = r.groups.flatMap { it.services }

        val plex = servicios.first { it.name == "Plex" }

        assertEquals(32400, plex.port)
        assertTrue("la ruta del enlace no se puede perder", plex.path.contains("web"))
    }

    /** Los iconos vienen como rutas del panel de origen; hay que quedárselas para poder traerlos. */
    @Test
    fun `apunta los iconos relativos para poder traerlos`() {
        val r = PanelImport.parse(homer)!!

        assertTrue("debe detectar que hay iconos que descargar", r.hasRelativeLogos)
        assertTrue(r.logoPaths.values.any { it.contains("jellyfin") })
    }

    // ---- JSON de otros paneles ----

    @Test
    fun `lee un json con nombre y direccion`() {
        val json = """
            {
              "apps": [
                { "name": "Immich", "url": "http://192.168.1.254:2283" },
                { "name": "Portainer", "url": "http://192.168.1.254:9000" }
              ]
            }
        """.trimIndent()

        val r = PanelImport.parse(json)

        assertNotNull(r)
        assertEquals(2, r!!.serviceCount)
        assertEquals("las dos están en la misma máquina", 1, r.servers.size)
    }

    /** Un JSON anidado de cualquier manera: se recorre buscando nombre y dirección. */
    @Test
    fun `lee un json anidado`() {
        val json = """
            {
              "data": {
                "categories": [
                  { "items": [ { "title": "Grafana", "link": "http://10.0.0.5:3000" } ] }
                ]
              }
            }
        """.trimIndent()

        val r = PanelImport.parse(json)

        assertNotNull("la estructura no se puede dar por sabida", r)
        assertEquals(1, r!!.serviceCount)
    }

    // ---- Lo que no debe colarse ----

    @Test
    fun `un fichero sin servicios no se acepta`() {
        assertNull(PanelImport.parse(""))
        assertNull(PanelImport.parse("   "))
        assertNull(PanelImport.parse("esto no es ni yaml ni json"))
    }

    @Test
    fun `un json sin direcciones no se acepta`() {
        val json = """{ "version": 3, "theme": "dark", "columns": 4 }"""

        val r = PanelImport.parse(json)

        assertTrue("sin servicios no hay nada que importar", r == null || r.serviceCount == 0)
    }

    /** Un fichero cortado a la mitad no puede reventar la importación. */
    @Test
    fun `un fichero a medias no revienta`() {
        val cortado = homer.substring(0, homer.length / 2)

        // Lo que no puede pasar es que lance una excepción y se lleve la aplicación por
        // delante: o entiende algo, o devuelve nulo.
        val r = PanelImport.parse(cortado)

        if (r != null) assertTrue("lo que devuelva tiene que ser coherente", r.serviceCount >= 0)
    }

    /** Sin iconos relativos no hay que pedirle al usuario la dirección del panel viejo. */
    @Test
    fun `sin iconos relativos no pide la direccion de origen`() {
        val json = """{ "apps": [ { "name": "Immich", "url": "http://192.168.1.254:2283" } ] }"""

        val r = PanelImport.parse(json)!!

        assertFalse("no hay iconos que traer", r.hasRelativeLogos)
    }

    /** Una dirección con nombre de máquina, no IP, también tiene que funcionar. */
    @Test
    fun `entiende direcciones con nombre de maquina`() {
        val json = """{ "apps": [ { "name": "NAS", "url": "http://nas.local:5000" } ] }"""

        val r = PanelImport.parse(json)

        assertNotNull(r)
        assertEquals(1, r!!.serviceCount)
        assertTrue(r.servers.any { it.hostHome == "nas.local" })
    }
}
