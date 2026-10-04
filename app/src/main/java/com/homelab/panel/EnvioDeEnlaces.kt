package com.homelab.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Lo que va camino de un programa de descargas: los enlaces que llegan, de qué tipo son y si
 * el aviso de envío está abierto.
 *
 * Llegan por varios caminos —otra aplicación, una pestaña, un fichero armado por la web, los
 * botones de enviar— y cada uno deja el estado a su manera.
 */
class EnvioDeEnlaces {

    /** El aviso de envío está abierto. */
    var abierto by mutableStateOf(false)
        private set

    var enlaces by mutableStateOf<List<String>>(emptyList())
        private set

    /**
     * De qué son los enlaces que acaban de llegar, cuando quien los capturó ya lo sabía.
     *
     * Hace falta porque la dirección de un enlace de descarga muchas veces no dice nada:
     * los buscadores de NZB sirven `…/api?t=get&id=123&apikey=…`.
     */
    var tipo by mutableStateOf<LinkKind?>(null)
        private set

    /** Fichero que armó la propia web en el móvil, cuando no hay enlace que pasar. */
    var fichero by mutableStateOf<DownloadFile?>(null)
        private set

    /** Cierto mientras pueden llegar más enlaces de la misma ráfaga. Ver [rafagaTerminada]. */
    var recogiendo by mutableStateOf(false)
        private set

    fun deOtraAplicacion(enlace: String) {
        enlaces = listOf(enlace)
        abierto = true
    }

    /**
     * Un enlace pulsado en una pestaña. Una web que lanza varios los suelta en ráfaga, así
     * que se espera un momento a que dejen de llegar y se envían juntos.
     *
     * El aviso se abre con el primero, no cuando acaba la espera: pulsar y que no pase nada
     * durante un segundo parece que no ha funcionado y lleva a pulsar otra vez.
     */
    fun dePestana(enlace: String, tipoCapturado: LinkKind?) {
        enlaces = (enlaces + enlace).distinct()
        // El primero que llegue con tipo conocido manda: en una ráfaga son todos de la
        // misma página y del mismo tipo.
        if (tipo == null) tipo = tipoCapturado
        recogiendo = true
        abierto = true
    }

    /** Un fichero armado por la web. Aquí no hay ráfaga que esperar: ya está leído entero. */
    fun deFichero(nuevo: DownloadFile) {
        fichero = nuevo
        enlaces = emptyList()
        tipo = nuevo.kind
        recogiendo = false
        abierto = true
    }

    /** El botón de enviar de la barra de una pestaña: empieza de cero. */
    fun abrirDesdePestana() {
        enlaces = emptyList()
        tipo = null
        fichero = null
        recogiendo = false
        abierto = true
    }

    /** El botón de enviar del panel. */
    fun abrirDesdePanel() {
        enlaces = emptyList()
        recogiendo = false
        abierto = true
    }

    /** Pasó la espera de la ráfaga: ya no llegan más. */
    fun rafagaTerminada() {
        recogiendo = false
    }

    /** Se envió o se canceló. */
    fun cerrar() {
        abierto = false
        enlaces = emptyList()
        tipo = null
        fichero = null
    }
}
