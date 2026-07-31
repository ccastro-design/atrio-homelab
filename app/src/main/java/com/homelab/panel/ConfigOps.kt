package com.homelab.panel

/**
 * Operaciones sobre la configuración: alta, baja y reordenación de servicios y grupos.
 *
 * Están aquí, y no en cada pantalla, porque el panel y los ajustes hacen lo mismo y
 * tenerlo duplicado ya provocó un fallo: al guardar un servicio editado se colocaba al
 * final de su grupo en vez de quedarse donde estaba.
 */
object ConfigOps {

    /**
     * Guarda un servicio, nuevo o editado.
     *
     * Si el servicio ya estaba en el grupo de destino **se queda en su sitio**. Solo va al
     * final cuando es nuevo o cuando cambia de grupo.
     */
    fun saveService(config: PanelConfig, service: Service, groupId: String): PanelConfig {
        // Sin ningún grupo todavía, se crea uno que lo aloje.
        if (config.groups.isEmpty()) {
            return config.copy(
                groups = listOf(
                    ServiceGroup(
                        id = "grp-${System.currentTimeMillis()}",
                        name = service.name,
                        services = listOf(service)
                    )
                )
            )
        }

        val destino = groupId.ifBlank { config.groups.first().id }
        val posicion = config.groups
            .firstOrNull { it.id == destino }
            ?.services
            ?.indexOfFirst { it.id == service.id }
            ?: -1

        // Estaba en este mismo grupo: se sustituye en su posición.
        if (posicion >= 0) {
            return config.copy(
                groups = config.groups.map { grupo ->
                    if (grupo.id != destino) {
                        grupo
                    } else {
                        val servicios = grupo.services.toMutableList()
                        servicios[posicion] = service
                        grupo.copy(services = servicios)
                    }
                }
            )
        }

        // Es nuevo, o viene de otro grupo: se saca de donde estuviera y se añade al final.
        val sinEl = config.groups.map { grupo ->
            grupo.copy(services = grupo.services.filter { it.id != service.id })
        }

        return config.copy(
            groups = sinEl.map { grupo ->
                if (grupo.id == destino) grupo.copy(services = grupo.services + service) else grupo
            }
        )
    }

    /** Cambia un servicio donde quiera que esté, sin tocar su grupo ni su orden. */
    fun updateService(
        config: PanelConfig,
        serviceId: String,
        cambio: (Service) -> Service
    ): PanelConfig = config.copy(
        groups = config.groups.map { grupo ->
            grupo.copy(
                services = grupo.services.map { if (it.id == serviceId) cambio(it) else it }
            )
        }
    )

    fun deleteService(config: PanelConfig, serviceId: String): PanelConfig = config.copy(
        groups = config.groups.map { grupo ->
            grupo.copy(services = grupo.services.filter { it.id != serviceId })
        }
    )

    /**
     * Mueve un servicio de una posición a otra contando **todos** los servicios en el
     * orden en que se ven, sin separar por grupos.
     *
     * Así, arrastrar un servicio más allá del último de su grupo lo pasa al grupo
     * siguiente sin ningún caso especial: hereda el grupo del servicio cuyo sitio ocupa.
     */
    fun moveService(config: PanelConfig, from: Int, to: Int): PanelConfig {
        val plano = config.groups
            .flatMap { grupo -> grupo.services.map { grupo.id to it } }
            .toMutableList()

        if (from !in plano.indices || to !in plano.indices || from == to) return config

        val (grupoOrigen, servicio) = plano.removeAt(from)

        val grupoDestino = when {
            to < plano.size -> plano[to].first
            plano.isNotEmpty() -> plano.last().first
            else -> grupoOrigen
        }

        plano.add(to, grupoDestino to servicio)

        // Se rehacen los grupos en su orden, conservando los que se queden vacíos.
        return config.copy(
            groups = config.groups.map { grupo ->
                grupo.copy(
                    services = plano.filter { it.first == grupo.id }.map { it.second }
                )
            }
        )
    }

    /** Mueve un grupo entero, con sus servicios. */
    fun moveGroup(config: PanelConfig, from: Int, to: Int): PanelConfig {
        if (from !in config.groups.indices || to !in config.groups.indices) return config

        val grupos = config.groups.toMutableList()
        grupos.add(to, grupos.removeAt(from))
        return config.copy(groups = grupos)
    }

    fun renameGroup(config: PanelConfig, groupId: String, name: String): PanelConfig =
        config.copy(
            groups = config.groups.map { if (it.id == groupId) it.copy(name = name) else it }
        )

    fun deleteGroup(config: PanelConfig, groupId: String): PanelConfig =
        config.copy(groups = config.groups.filter { it.id != groupId })

    fun addGroup(config: PanelConfig, name: String, color: String = ""): PanelConfig =
        config.copy(
            groups = config.groups + ServiceGroup(
                id = "grp-${System.currentTimeMillis()}",
                name = name,
                color = color
            )
        )
}
