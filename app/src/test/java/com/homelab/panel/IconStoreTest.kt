package com.homelab.panel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // -------------------------------------------------------------------------------
    // Cuánto se reduce una imagen al leerla.
    //
    // Esto sí es cuenta pura, sin Android de por medio: lo que se prueba aquí vale igual
    // en el móvil. Lo que hace el decodificador con el factor se midió aparte, en el móvil.
    // -------------------------------------------------------------------------------

    /** Una foto de 12 Mpx para el fondo: se lee a la mitad, 2000x1500, y no a 4000x3000. */
    @Test
    fun `una foto de 12 Mpx para el fondo se lee a la mitad`() {
        assertEquals(2, IconStore.factorDeReduccion(4000, 3000, 1440))
    }

    /** La de 50 Mpx de muchos móviles: a un cuarto, que sigue pasando de 1440. */
    @Test
    fun `una foto de 50 Mpx para el fondo se lee a un cuarto`() {
        assertEquals(4, IconStore.factorDeReduccion(8160, 6144, 1440))
    }

    /** Lo que ya guardó la aplicación se lee entero: para eso no cambia nada. */
    @Test
    fun `lo que ya cabe se lee entero`() {
        assertEquals(1, IconStore.factorDeReduccion(1440, 1080, 1440))
        assertEquals(1, IconStore.factorDeReduccion(192, 192, 192))
        assertEquals(1, IconStore.factorDeReduccion(32, 32, 192))
    }

    /** Manda el lado mayor, esté en horizontal o en vertical. */
    @Test
    fun `una foto en vertical cuenta igual`() {
        assertEquals(
            IconStore.factorDeReduccion(4000, 3000, 1440),
            IconStore.factorDeReduccion(3000, 4000, 1440)
        )
    }

    /** Una imagen rota o sin medidas no se reduce: que la lectura decida qué hacer con ella. */
    @Test
    fun `sin medidas no se reduce`() {
        assertEquals(1, IconStore.factorDeReduccion(0, 0, 1440))
        assertEquals(1, IconStore.factorDeReduccion(-1, -1, 192))
        assertEquals(1, IconStore.factorDeReduccion(4000, 3000, 0))
    }

    /**
     * La regla entera, en todas las medidas a la vez: el lado mayor **nunca baja** del que hace
     * falta —lo que no se lee no se recupera al escalar—, y el factor es **el mayor** posible:
     * uno el doble ya bajaría.
     */
    @Test
    fun `nunca baja del tamano pedido y no se queda corto`() {
        for (lado in listOf(192, 1440)) {
            for (ancho in 1..9000 step 37) {
                for (alto in listOf(1, ancho / 3 + 1, ancho, ancho * 2)) {
                    val factor = IconStore.factorDeReduccion(ancho, alto, lado)
                    val mayor = maxOf(ancho, alto)

                    assertEquals("potencia de dos", 0, factor and (factor - 1))
                    assertTrue("$ancho x $alto a $lado: baja", mayor / factor >= minOf(lado, mayor))
                    assertTrue("$ancho x $alto a $lado: se queda corto", mayor / (factor * 2) < lado)
                }
            }
        }
    }
}
