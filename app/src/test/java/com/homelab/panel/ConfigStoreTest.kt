package com.homelab.panel

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * El guardado de la configuración.
 *
 * Es donde un fallo cuesta más caro: alguien con veinte servicios metidos a mano se los
 * encuentra borrados. Antes pasaba de verdad —se escribía encima del fichero bueno y, si no
 * se podía leer, se partía de cero sin avisar— y comprobarlo exigía corromper el fichero en
 * un móvil. Aquí se hace en medio segundo.
 */
class ConfigStoreTest {

    private lateinit var carpeta: File

    /** Una configuración reconocible, para saber si lo que se recupera es la buena. */
    private val mia = PanelConfig(
        title = "Mi panel",
        servers = listOf(Server(id = "s1", name = "NAS", hostHome = "192.168.1.254")),
        groups = listOf(
            ServiceGroup(
                id = "g1",
                name = "Servicios",
                services = listOf(
                    Service(id = "sv1", name = "Immich", serverId = "s1", port = 2283),
                    Service(id = "sv2", name = "Jellyfin", serverId = "s1", port = 8096)
                )
            )
        )
    )

    private val deFabrica = PanelConfig(title = "de fábrica")

    @Before
    fun crearCarpeta() {
        carpeta = File(System.getProperty("java.io.tmpdir"), "atrio-test-${System.nanoTime()}")
        carpeta.mkdirs()
        ConfigStore.avisoVisto()
    }

    @After
    fun limpiar() {
        carpeta.deleteRecursively()
    }

    private fun cargar() = ConfigStore.loadFrom(carpeta) { deFabrica }

    private fun fichero(nombre: String) = File(carpeta, nombre)

    @Test
    fun `guarda y vuelve a leer lo mismo`() {
        ConfigStore.saveIn(carpeta, mia)

        val leida = cargar()

        assertEquals("Mi panel", leida.title)
        assertEquals(2, leida.allServices.size)
        assertEquals(ConfigStore.Estado.NORMAL, ConfigStore.ultimoEstado)
    }

    @Test
    fun `la primera vez parte de la configuracion de fabrica`() {
        val leida = cargar()

        assertEquals("de fábrica", leida.title)
        assertTrue("y la deja guardada", fichero("config.json").exists())
    }

    /** Al leer una configuración buena se deja copia, que es la red de seguridad. */
    @Test
    fun `al arrancar bien deja una copia de seguridad`() {
        ConfigStore.saveIn(carpeta, mia)
        cargar()

        assertTrue(fichero("config-copia.json").exists())
    }

    /**
     * El caso que importa: el fichero se queda cortado —un apagón a mitad de la escritura—
     * y al arrancar hay que recuperar la copia, **no** partir de cero.
     */
    @Test
    fun `con el fichero cortado recupera la copia y no pierde nada`() {
        ConfigStore.saveIn(carpeta, mia)
        cargar() // deja la copia

        // Se corta el fichero por la mitad, como lo dejaría un apagón.
        val bueno = fichero("config.json")
        val entero = bueno.readText()
        bueno.writeText(entero.substring(0, entero.length / 2))

        val recuperada = cargar()

        assertEquals("Mi panel", recuperada.title)
        assertEquals("los dos servicios siguen ahí", 2, recuperada.allServices.size)
        assertEquals(ConfigStore.Estado.RECUPERADA, ConfigStore.ultimoEstado)
    }

    /** Lo ilegible se aparta para poder mirarlo, en vez de machacarse. */
    @Test
    fun `el fichero ilegible se aparta en vez de borrarse`() {
        ConfigStore.saveIn(carpeta, mia)
        cargar()
        fichero("config.json").writeText("{ esto no es json")

        cargar()

        assertTrue("debe quedar apartado", fichero("config-roto.json").exists())
        assertTrue(fichero("config-roto.json").readText().contains("esto no es json"))
    }

    /** Sin fichero y sin copia utilizable no queda otra, pero hay que avisar. */
    @Test
    fun `sin copia utilizable avisa de que se ha perdido`() {
        fichero("config.json").writeText("basura")
        fichero("config-copia.json").writeText("basura también")

        val leida = cargar()

        assertEquals("de fábrica", leida.title)
        assertEquals(ConfigStore.Estado.PERDIDA, ConfigStore.ultimoEstado)
    }

    /**
     * Un guardado que falle a medias no puede dejar el fichero bueno peor de lo que estaba.
     * Se comprueba que no queda ningún temporal suelto tras guardar.
     */
    @Test
    fun `guardar no deja ficheros temporales sueltos`() {
        ConfigStore.saveIn(carpeta, mia)
        ConfigStore.saveIn(carpeta, mia.copy(title = "otro"))

        val sueltos = carpeta.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()

        assertTrue("no debe quedar $sueltos", sueltos.isEmpty())
        assertEquals("otro", cargar().title)
    }

    /** Lo que se exporta se tiene que poder volver a importar. */
    @Test
    fun `lo exportado se puede importar`() {
        val texto = ConfigStore.export(mia)
        val vuelta = ConfigStore.import(texto)

        assertNotNull(vuelta)
        assertEquals("Mi panel", vuelta!!.title)
        assertEquals(2, vuelta.allServices.size)
    }

    /** Un texto que no es una configuración no puede colarse como si lo fuera. */
    @Test
    fun `importar basura devuelve nulo`() {
        assertEquals(null, ConfigStore.import("no soy una configuración"))
        assertEquals(null, ConfigStore.import(""))
    }

    /**
     * Una configuración de una versión anterior, sin los campos nuevos, tiene que seguir
     * leyéndose. Es lo que permitirá publicar actualizaciones sin romper lo de nadie.
     */
    @Test
    fun `lee una configuracion vieja sin los campos nuevos`() {
        val vieja = """
            {
              "title": "Panel antiguo",
              "servers": [],
              "groups": [
                { "id": "g1", "name": "Grupo", "services": [
                    { "id": "sv1", "name": "Servicio" }
                ] }
              ]
            }
        """.trimIndent()

        val leida = ConfigStore.import(vieja)

        assertNotNull("una configuración vieja no puede dejar de leerse", leida)
        assertEquals("Panel antiguo", leida!!.title)
        assertEquals(1, leida.allServices.size)
        // Y los campos que no estaban toman su valor de siempre.
        assertEquals("", leida.serviceNameColor)
        assertEquals(emptyList<String>(), leida.homeSsids)
    }
}
