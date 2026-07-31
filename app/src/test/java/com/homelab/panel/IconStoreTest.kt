package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La dirección del icono declarado por un servicio.
 *
 * **Cuidado con estos tests**: se ejecutan con el Java del ordenador, y el fallo que
 * motivó esta función solo se daba con el `java.net.URI` de Android, que resuelve
 * `http://host:8085` + `images/x.png` como `http://host:8085images/x.png` —sin barra—
 * mientras que el del ordenador lo hace bien. Así que **pasar estos tests no demuestra que
 * funcione en el móvil**; lo que hacen es fijar el resultado esperado para que nadie
 * simplifique la normalización pensando que sobra.
 */
class IconStoreTest {

    /** El caso de qBittorrent: base sin ruta y href relativo sin barra. */
    @Test
    fun `base sin ruta y href relativo`() {
        assertEquals(
            "http://192.168.1.254:8085/images/qbittorrent32.png",
            IconStore.resolverIcono("http://192.168.1.254:8085", "images/qbittorrent32.png")
        )
    }

    /** El de Transmission: la página está en un subdirectorio tras una redirección. */
    @Test
    fun `href relativo desde un subdirectorio`() {
        assertEquals(
            "http://192.168.1.254:9091/transmission/web/images/favicon.ico",
            IconStore.resolverIcono(
                "http://192.168.1.254:9091/transmission/web/",
                "./images/favicon.ico"
            )
        )
    }

    @Test
    fun `href que empieza por barra va a la raiz`() {
        assertEquals(
            "http://192.168.1.254:8096/web/favicon.ico",
            IconStore.resolverIcono("http://192.168.1.254:8096/web/index.html", "/web/favicon.ico")
        )
    }

    @Test
    fun `href absoluto se deja tal cual`() {
        assertEquals(
            "https://cdn.ejemplo.com/icono.png",
            IconStore.resolverIcono("http://192.168.1.254:8085", "https://cdn.ejemplo.com/icono.png")
        )
    }

    @Test
    fun `sube un nivel con dos puntos`() {
        assertEquals(
            "http://nas.local:5000/favicon.ico",
            IconStore.resolverIcono("http://nas.local:5000/panel/", "../favicon.ico")
        )
    }

    /** Con https y sin puerto explícito tampoco puede perderse la barra. */
    @Test
    fun `https sin puerto`() {
        assertEquals(
            "https://mi.servidor.com/icono.png",
            IconStore.resolverIcono("https://mi.servidor.com", "icono.png")
        )
    }

    // -------------------------------------------------------------------------------
    // Qué hacer con el icono guardado.
    //
    // Aquí se colaron dos fallos seguidos, y los dos dejaban al servicio sin icono por
    // motivos contrarios. Se prueban los dos casos, uno frente al otro.
    // -------------------------------------------------------------------------------

    private val enCasaYFuera = "http://192.168.1.254:8085/|http://100.64.0.10:8085/"

    /**
     * **El primer fallo.** El icono iba con el nombre de la dirección, y un servicio tiene
     * dos: al salir de casa pasaba a ser otro servicio y se quedaba sin dibujo. Con las dos
     * direcciones juntas, cambiar de red no cambia nada.
     */
    @Test
    fun `cambiar de casa a la vpn no toca el icono`() {
        assertEquals(
            QueHacerConElIcono.USARLO,
            IconStore.decidirSobreElIcono(
                existe = true,
                origenAnotado = enCasaYFuera,
                origenActual = enCasaYFuera
            )
        )
    }

    /**
     * **El segundo fallo, y el peor**: la vista previa de la ficha dibuja el icono sin
     * saber las direcciones del servicio. Comparando con lo que ella puede dar, el icono
     * bueno **se borraba con solo abrir la ficha**. Sin saber las direcciones no se juzga.
     */
    @Test
    fun `sin saber las direcciones nunca se tira el icono`() {
        assertEquals(
            QueHacerConElIcono.USARLO,
            IconStore.decidirSobreElIcono(
                existe = true,
                origenAnotado = enCasaYFuera,
                origenActual = null
            )
        )
    }

    /** Y sin nada guardado, se pide; pero tampoco se «tira» lo que no hay. */
    @Test
    fun `sin saber las direcciones y sin icono, se pide`() {
        assertEquals(
            QueHacerConElIcono.PEDIRLO,
            IconStore.decidirSobreElIcono(
                existe = false,
                origenAnotado = enCasaYFuera,
                origenActual = null
            )
        )
    }

    /** Lo que sí tiene que invalidarlo: que el servicio se mude de puerto o de máquina. */
    @Test
    fun `cambiar el puerto del servicio tira el icono`() {
        assertEquals(
            QueHacerConElIcono.TIRARLO,
            IconStore.decidirSobreElIcono(
                existe = true,
                origenAnotado = enCasaYFuera,
                origenActual = "http://192.168.1.254:8099/|http://100.64.0.10:8099/"
            )
        )
    }

    /**
     * Los iconos de antes de que esto existiera no llevan direcciones apuntadas. No se
     * tiran: se aprovechan, y al usarlos se les anota la de ahora.
     */
    @Test
    fun `un icono viejo sin direcciones apuntadas se aprovecha`() {
        assertEquals(
            QueHacerConElIcono.USARLO,
            IconStore.decidirSobreElIcono(
                existe = true,
                origenAnotado = null,
                origenActual = enCasaYFuera
            )
        )
    }
}
