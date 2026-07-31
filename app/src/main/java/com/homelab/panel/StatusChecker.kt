package com.homelab.panel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Cada cuánto se repite la comprobación mientras el panel está en pantalla.
 *
 * Está aquí y no suelto en el bucle porque la ficha del servicio lo dice con palabras:
 * si cambia el número, tiene que cambiar en los dos sitios a la vez.
 */
const val STATUS_REFRESH_SECONDS = 30

/** Estado de un servicio, tal como se muestra en su tarjeta. */
enum class ServiceState { UNKNOWN, CHECKING, UP, DOWN }

data class ServiceStatus(val state: ServiceState)

/**
 * Comprueba qué servicios responden.
 *
 * Solo trabaja mientras el panel está en pantalla: no hay servicios en segundo plano,
 * ni notificaciones, ni despertares programados. Así no hace falta pelearse con el
 * ahorro de batería de cada fabricante ni pedir permisos, y el usuario ve el estado
 * justo cuando le interesa, que es cuando está mirando el panel.
 */
class StatusMap {

    val entries: SnapshotStateMap<String, ServiceStatus> = mutableStateMapOf()

    operator fun get(serviceId: String): ServiceStatus =
        entries[serviceId] ?: ServiceStatus(ServiceState.UNKNOWN)

    /** Comprueba todos los servicios pedidos, en tandas para no saturar la red. */
    suspend fun refresh(config: PanelConfig, away: Boolean) {
        val servicios = config.allServices.filter { it.checkStatus && !it.isExample }
        if (servicios.isEmpty()) return

        // «Comprobando» solo la primera vez. En los refrescos siguientes se conserva a la
        // vista el estado anterior hasta que llega el nuevo: marcarlos todos como
        // comprobando hacía que el punto y el texto parpadearan cada treinta segundos.
        servicios.forEach { servicio ->
            if (entries[servicio.id] == null) {
                entries[servicio.id] = ServiceStatus(ServiceState.CHECKING)
            }
        }

        servicios.chunked(TANDA).forEach { tanda ->
            coroutineScope {
                tanda.map { servicio ->
                    async {
                        val url = config.urlOf(servicio, away)
                        val responde = url.isNotBlank() && Reachability.probe(url) != null
                        val nuevo =
                            ServiceStatus(if (responde) ServiceState.UP else ServiceState.DOWN)

                        // Solo se escribe si de verdad cambió, para no repintar de balde.
                        if (entries[servicio.id] != nuevo) entries[servicio.id] = nuevo
                    }
                }.awaitAll()
            }
        }
    }

    private companion object {
        /** Ocho a la vez: suficiente para que sea rápido sin inundar la red doméstica. */
        const val TANDA = 8
    }
}

@Composable
fun rememberStatusMap(): StatusMap = remember { StatusMap() }
