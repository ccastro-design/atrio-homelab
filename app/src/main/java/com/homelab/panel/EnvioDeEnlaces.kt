package com.homelab.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    /**
     * Hay un envío en marcha. Lo que llegue mientras tanto espera su turno en [cola] y sale al
     * [cerrar]: tocarlo a medio envío arrancaba un segundo envío a la vez que el primero, y
     * aMule pierde peticiones cuando le llegan varias juntas.
     */
    var enviando = false
        private set

    /** Sube con cada tanda que sale de la cola, para que el aviso de envío empiece de cero. */
    var tanda by mutableIntStateOf(0)
        private set

    private val cola = mutableListOf<Llegada>()

    private sealed interface Llegada {
        data class Enlaces(val enlaces: List<String>, val tipo: LinkKind?) : Llegada
        data class Fichero(val fichero: DownloadFile) : Llegada
    }

    fun deOtraAplicacion(enlace: String) {
        if (enviando) return encolar(Llegada.Enlaces(listOf(enlace), null))
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
        if (enviando) return encolar(Llegada.Enlaces(listOf(enlace), tipoCapturado))
        enlaces = (enlaces + enlace).distinct()
        // El primero que llegue con tipo conocido manda: en una ráfaga son todos de la
        // misma página y del mismo tipo.
        if (tipo == null) tipo = tipoCapturado
        recogiendo = true
        abierto = true
    }

    /** Un fichero armado por la web. Aquí no hay ráfaga que esperar: ya está leído entero. */
    fun deFichero(nuevo: DownloadFile) {
        if (enviando) return encolar(Llegada.Fichero(nuevo))
        fichero = nuevo
        enlaces = emptyList()
        tipo = nuevo.kind
        recogiendo = false
        abierto = true
    }

    /** El botón de enviar de la barra de una pestaña: empieza de cero. */
    fun abrirDesdePestana() {
        Registro.traza("Envío") { "Botón de enviar de una pestaña" }
        enlaces = emptyList()
        tipo = null
        fichero = null
        recogiendo = false
        abierto = true
    }

    /** El botón de enviar del panel. */
    fun abrirDesdePanel() {
        Registro.traza("Envío") { "Botón de enviar del panel" }
        enlaces = emptyList()
        recogiendo = false
        abierto = true
    }

    /** Pasó la espera de la ráfaga: ya no llegan más. */
    fun rafagaTerminada() {
        Registro.traza("Envío") { "Ráfaga cerrada con ${enlaces.size} enlaces" }
        recogiendo = false
    }

    /**
     * Empieza la entrega de [lote]. Lo que haya llegado a [enlaces] y no vaya en él —en el
     * instante entre decidir y empezar— pasa a la cola en vez de perderse.
     */
    fun empiezaEnvio(lote: List<String>) {
        enviando = true
        val sueltos = enlaces.filter { it !in lote }
        if (sueltos.isNotEmpty()) cola.add(0, Llegada.Enlaces(sueltos, tipo))
    }

    /** Se envió o se canceló. Si algo esperaba su turno, sale ahora como una tanda nueva. */
    fun cerrar() {
        enviando = false
        abierto = false
        enlaces = emptyList()
        tipo = null
        fichero = null
        recogiendo = false

        when (val siguiente = sacarDeLaCola() ?: return) {
            is Llegada.Enlaces -> {
                enlaces = siguiente.enlaces
                tipo = siguiente.tipo
            }
            is Llegada.Fichero -> {
                fichero = siguiente.fichero
                tipo = siguiente.fichero.kind
            }
        }
        tanda++
        abierto = true
    }

    private fun encolar(llegada: Llegada) {
        Registro.traza("Envío") { "Llega durante un envío: espera su turno (${cola.size + 1} en cola)" }
        cola += llegada
    }

    /**
     * La siguiente tanda: un fichero solo, o los enlaces seguidos que son del mismo tipo, que es
     * lo que se envía de una vez al mismo programa.
     */
    private fun sacarDeLaCola(): Llegada? {
        val primera = cola.removeFirstOrNull() ?: return null
        if (primera !is Llegada.Enlaces) return primera

        val tipoDe = { l: Llegada.Enlaces -> l.tipo ?: l.enlaces.firstNotNullOfOrNull { Links.detect(it) } }
        val tipoTanda = tipoDe(primera)
        var juntos = primera.enlaces
        var conTipo = primera.tipo

        while (true) {
            val otra = cola.firstOrNull() as? Llegada.Enlaces ?: break
            if (tipoDe(otra) != tipoTanda) break
            cola.removeAt(0)
            juntos = (juntos + otra.enlaces).distinct()
            if (conTipo == null) conTipo = otra.tipo
        }
        return Llegada.Enlaces(juntos, conTipo)
    }
}
