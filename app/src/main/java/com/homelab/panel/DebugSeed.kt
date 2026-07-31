package com.homelab.panel

/**
 * Configuración de prueba para verificar la aplicación desde el ordenador.
 *
 * Solo se usa desde las ayudas de depuración de [MainActivity], que están dentro de un
 * `if (BuildConfig.DEBUG)`. No forma parte del producto: sirve para poder comprobar el
 * estado de los servicios, las pestañas y el envío de enlaces en un móvil donde no se
 * pueden simular toques por adb.
 *
 * Las direcciones son de ejemplo y hay que ajustarlas a la máquina contra la que se vaya
 * a probar. No se deja aquí la del autor: este fichero acaba en un repositorio público y
 * la dirección de su nodo de la VPN no pinta nada ahí.
 */
object DebugSeed {

    fun create(): PanelConfig = PanelConfig(
        title = "Mi servidor",
        servers = listOf(
            Server(
                id = "srv-nas",
                name = "NAS de casa",
                hostHome = "192.168.1.254",
                // Rango de una malla tipo Tailscale, para probar el perfil de fuera.
                hostAway = "100.64.0.10"
            ),
            Server(
                id = "srv-red",
                name = "Red local",
                hostHome = "192.168.1.1"
            )
        ),
        downloadTargets = listOf(
            DownloadTarget(
                id = "dl-amule",
                name = "aMule del NAS",
                kind = TargetKind.AMULE.name,
                serverId = "srv-nas",
                port = 58711,
                path = "/"
            )
        ),
        groups = listOf(
            ServiceGroup(
                id = "grp-docker",
                name = "Servicios Docker",
                services = listOf(
                    Service(
                        id = "svc-immich",
                        name = "Immich",
                        subtitle = "Galería de fotos",
                        serverId = "srv-nas",
                        port = 2283,
                        category = "photos"
                    ),
                    Service(
                        id = "svc-portainer",
                        name = "Portainer",
                        subtitle = "Contenedores",
                        serverId = "srv-nas",
                        port = 9000,
                        category = "containers",
                        desktopMode = true
                    ),
                    Service(
                        id = "svc-jellyfin",
                        name = "Jellyfin",
                        subtitle = "Servidor multimedia",
                        serverId = "srv-nas",
                        port = 8096,
                        category = "media"
                    ),
                    Service(
                        id = "svc-amule",
                        name = "aMule",
                        subtitle = "Descargas",
                        serverId = "srv-nas",
                        port = 58711,
                        category = "downloads"
                    )
                )
            ),
            ServiceGroup(
                id = "grp-red",
                name = "Red",
                services = listOf(
                    Service(
                        id = "svc-router",
                        name = "Router",
                        subtitle = "Panel del router",
                        serverId = "srv-red",
                        port = 80,
                        category = "router"
                    )
                )
            ),
            ServiceGroup(
                id = "grp-web",
                name = "Webs",
                services = listOf(
                    Service(
                        id = "svc-ejemplo",
                        name = "Ejemplo con HTTPS",
                        subtitle = "Para comprobar el cifrado",
                        urlOwn = "https://example.com",
                        category = "web"
                    )
                )
            )
        )
    )
}
