package com.homelab.panel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue

/**
 * Las pestañas abiertas y cuál se está viendo.
 *
 * Vivía suelto dentro de `Contenido`, mezclado con el resto del estado de la aplicación. Aquí
 * se puede probar sin pantalla: las cuentas con los índices tienen su porqué —mover una
 * pestaña no debe saltar a otra que no se ha pedido— y están fijadas en `PestanasTest`.
 */
class Pestanas {

    val lista = mutableStateListOf<TabState>()

    /** Índice de la que se está viendo. */
    var activa by mutableIntStateOf(0)

    val vacia: Boolean get() = lista.isEmpty()

    /** Abre un servicio en la pestaña que ya tuviera, o en una nueva al final. */
    fun abrir(servicio: Service, destino: String) {
        val existente = lista.indexOfFirst { it.service.id == servicio.id }
        if (existente >= 0) {
            activa = existente
            // Puede llevar cargada la dirección del otro perfil: hay que recargar, o se
            // queda mostrando algo que ya no responde.
            if (lista[existente].url != destino) lista[existente].load(destino)
        } else {
            lista.add(TabState(servicio, destino))
            activa = lista.lastIndex
        }
    }

    /**
     * Cambia una pestaña de sitio, arrastrándola por la tira.
     *
     * La pestaña que se estaba viendo sigue siendo la misma aunque cambie de posición: se
     * recoloca el índice de la activa, o al mover una de al lado se saltaría a otra
     * página sin haberla pedido.
     */
    fun mover(desde: Int, hasta: Int) {
        if (desde !in lista.indices || hasta !in lista.indices || desde == hasta) return

        lista.add(hasta, lista.removeAt(desde))

        activa = when {
            activa == desde -> hasta
            desde < activa && hasta >= activa -> activa - 1
            desde > activa && hasta <= activa -> activa + 1
            else -> activa
        }
    }

    /**
     * Cierra una. Solo se puede cerrar la activa (la X sale únicamente en ella), así que
     * basta con no salirse de la lista: se pasa a ver la que ocupa su sitio.
     */
    fun cerrar(indice: Int) {
        lista.getOrNull(indice)?.destroy()
        lista.removeAt(indice)
        activa = activa.coerceIn(0, maxOf(0, lista.lastIndex))
    }

    /**
     * Cierra todas. Borrar las cookies no echa de un sitio en el que ya estás dentro: la
     * página sigue cargada y funcionando, y el usuario vería que «no ha pasado nada».
     */
    fun cerrarTodas() {
        lista.forEach { it.destroy() }
        lista.clear()
        activa = 0
    }

    /** Al cambiar entre casa y fuera, las pestañas abiertas conservan la dirección antigua. */
    fun cambiarDirecciones(direccionDe: (Service) -> String) {
        lista.forEach { pestana ->
            val destino = direccionDe(pestana.service)
            if (destino.isNotBlank() && pestana.url != destino) pestana.load(destino)
        }
    }
}
