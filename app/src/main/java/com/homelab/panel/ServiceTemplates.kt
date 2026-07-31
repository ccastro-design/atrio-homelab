package com.homelab.panel

/**
 * Servicios conocidos del mundo autoalojado, con su puerto habitual, su categoría y si
 * necesitan la versión de escritorio.
 *
 * Sirven para dos cosas: rellenar la ficha al añadir un servicio a mano, y reconocer lo
 * que encuentre el buscador de red. Aquí solo hay nombres y números de puerto, es decir
 * referencias por su nombre a programas conocidos: no se distribuye ningún logotipo ni
 * material de marca.
 */
data class ServiceTemplate(
    val name: String,
    val port: Int,
    val category: String,
    val scheme: String = "http",
    val path: String = "/",
    val desktop: Boolean = false
)

object ServiceTemplates {

    val all: List<ServiceTemplate> = listOf(
        ServiceTemplate("Actual Budget", 5006, "documents"),
        ServiceTemplate("AdGuard Home", 3000, "dns"),
        ServiceTemplate("aMule", 4711, "downloads"),
        ServiceTemplate("Audiobookshelf", 13378, "books"),
        ServiceTemplate("Authelia", 9091, "security"),
        ServiceTemplate("Authentik", 9000, "security"),
        ServiceTemplate("Bazarr", 6767, "media"),
        ServiceTemplate("Calibre-Web", 8083, "books"),
        ServiceTemplate("Cockpit", 9090, "virtualization", desktop = true),
        ServiceTemplate("Deluge", 8112, "downloads"),
        ServiceTemplate("Duplicati", 8200, "backup", desktop = true),
        ServiceTemplate("Emby", 8096, "media"),
        ServiceTemplate("ESPHome", 6052, "home"),
        ServiceTemplate("FileBrowser", 8080, "files"),
        ServiceTemplate("Firefly III", 8080, "documents"),
        ServiceTemplate("Frigate", 5000, "cameras"),
        ServiceTemplate("Gitea", 3000, "code"),
        ServiceTemplate("Grafana", 3000, "monitoring", desktop = true),
        ServiceTemplate("Guacamole", 8080, "virtualization", path = "/guacamole", desktop = true),
        ServiceTemplate("Heimdall", 80, "generic"),
        ServiceTemplate("Home Assistant", 8123, "home"),
        ServiceTemplate("Homarr", 7575, "generic"),
        ServiceTemplate("Immich", 2283, "photos"),
        ServiceTemplate("InfluxDB", 8086, "monitoring", desktop = true),
        ServiceTemplate("Jackett", 9117, "downloads", desktop = true),
        ServiceTemplate("Jellyfin", 8096, "media"),
        ServiceTemplate("Jellyseerr", 5055, "media"),
        ServiceTemplate("Jenkins", 8080, "code", desktop = true),
        ServiceTemplate("Kavita", 5000, "books"),
        ServiceTemplate("Komga", 25600, "books"),
        ServiceTemplate("Kopia", 51515, "backup"),
        ServiceTemplate("Lidarr", 8686, "music"),
        ServiceTemplate("Mealie", 9000, "documents"),
        ServiceTemplate("MinIO", 9001, "storage", desktop = true),
        ServiceTemplate("n8n", 5678, "code", desktop = true),
        ServiceTemplate("Navidrome", 4533, "music"),
        ServiceTemplate("Netdata", 19999, "monitoring", desktop = true),
        ServiceTemplate("Nextcloud", 443, "files", scheme = "https"),
        ServiceTemplate("Nginx Proxy Manager", 81, "network", desktop = true),
        ServiceTemplate("Node-RED", 1880, "home", desktop = true),
        ServiceTemplate("Ollama Web UI", 8080, "ai"),
        ServiceTemplate("Overseerr", 5055, "media"),
        ServiceTemplate("Paperless-ngx", 8000, "documents"),
        ServiceTemplate("Photoprism", 2342, "photos"),
        ServiceTemplate("Pi-hole", 80, "dns", path = "/admin"),
        ServiceTemplate("Plex", 32400, "media", path = "/web"),
        ServiceTemplate("Portainer", 9000, "containers", desktop = true),
        ServiceTemplate("Prometheus", 9090, "monitoring", desktop = true),
        ServiceTemplate("Prowlarr", 9696, "downloads", desktop = true),
        ServiceTemplate("Proxmox", 8006, "virtualization", scheme = "https", desktop = true),
        ServiceTemplate("qBittorrent", 8080, "downloads"),
        ServiceTemplate("QNAP", 8080, "storage", desktop = true),
        ServiceTemplate("Radarr", 7878, "media", desktop = true),
        ServiceTemplate("Readarr", 8787, "books", desktop = true),
        ServiceTemplate("SABnzbd", 8080, "downloads"),
        ServiceTemplate("Scrutiny", 8080, "monitoring"),
        ServiceTemplate("Sonarr", 8989, "media", desktop = true),
        ServiceTemplate("Synology DSM", 5000, "storage", desktop = true),
        ServiceTemplate("Syncthing", 8384, "files"),
        ServiceTemplate("Tautulli", 8181, "monitoring"),
        ServiceTemplate("Technitium DNS", 5380, "dns"),
        ServiceTemplate("Traefik", 8080, "network", desktop = true),
        ServiceTemplate("Transmission", 9091, "downloads"),
        ServiceTemplate("TrueNAS", 80, "storage", desktop = true),
        ServiceTemplate("UniFi", 8443, "network", scheme = "https", desktop = true),
        ServiceTemplate("Uptime Kuma", 3001, "monitoring"),
        ServiceTemplate("Vaultwarden", 80, "security"),
        ServiceTemplate("Wiki.js", 3000, "documents"),
        ServiceTemplate("Zabbix", 8080, "monitoring", desktop = true),
        ServiceTemplate("Zigbee2MQTT", 8080, "home")
    ).sortedBy { it.name.lowercase() }

    /**
     * Puertos que merece la pena sondear al buscar servicios en la red, de mayor a
     * menor probabilidad de encontrar algo.
     */
    val scanPorts: List<Int> = (all.map { it.port } + listOf(80, 443, 8080, 8443, 5000, 9000))
        .distinct()

    /**
     * Puertos que usan tantos programas distintos que adivinar por el número solo sirve
     * para equivocarse. El buscador de red no pone nombre a lo que encuentre en ellos.
     */
    private val PUERTOS_AMBIGUOS = setOf(80, 443, 3000, 5000, 8000, 8080, 8443, 9000, 9090)

    /**
     * Plantilla de un puerto **solo cuando es de fiar**: un único programa conocido lo usa
     * y no es uno de los puertos que usa medio mundo. Sirve para no bautizar como
     * «Nextcloud» al puerto 443 de un NAS cualquiera.
     */
    fun trustedByPort(port: Int): ServiceTemplate? {
        if (port in PUERTOS_AMBIGUOS) return null
        val candidatas = all.filter { it.port == port }
        return candidatas.singleOrNull()
    }

    /**
     * Busca un programa conocido dentro de un texto leído del propio servicio, como el
     * título de su página o su cabecera `Server`. Sirve para ponerle el icono y la ruta
     * correctos: un título «aMule control panel» delata a aMule.
     */
    fun matchByName(text: String): ServiceTemplate? {
        val t = text.lowercase()
        // El más largo primero, para que «qBittorrent» gane a un hipotético «qBit».
        return all
            .sortedByDescending { it.name.length }
            .firstOrNull { plantilla -> t.contains(plantilla.name.lowercase()) }
    }

}
