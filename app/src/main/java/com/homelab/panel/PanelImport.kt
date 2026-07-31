package com.homelab.panel

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Trae a la aplicación un panel que el usuario ya tenga montado en otro sitio.
 *
 * Se admiten dos cosas:
 *  - El `config.yml` de **Homer**, que es el panel autoalojado más extendido.
 *  - Cualquier **JSON** con una lista de accesos, que cubre a Homarr, Heimdall y demás
 *    sin atarse a la versión concreta de cada uno: se buscan objetos que tengan un nombre
 *    y una dirección, estén donde estén dentro del fichero.
 *
 * Además de traer los servicios, se **agrupan por máquina y se crean los servidores**:
 * un `config.yml` que repite la misma dirección en diez servicios queda con un solo
 * servidor y diez servicios que solo indican su puerto.
 */
object PanelImport {

    private const val TAG = "Panel"

    data class Result(
        val title: String,
        val subtitle: String,
        val servers: List<Server>,
        val groups: List<ServiceGroup>,
        /**
         * Rutas de los iconos tal como venían en el fichero, por id de servicio. Son
         * relativas al panel de origen (por ejemplo `assets/icons/amule.png`), así que
         * solo se pueden traer sabiendo la dirección de ese panel.
         */
        val logoPaths: Map<String, String> = emptyMap(),
        /** Ruta del logotipo del panel, también relativa al panel de origen. */
        val panelLogoPath: String = ""
    ) {
        val serviceCount: Int get() = groups.sumOf { it.services.size }

        /** True si el fichero trae iconos que habría que descargar del panel de origen. */
        val hasRelativeLogos: Boolean
            get() = (logoPaths.values + panelLogoPath).any {
                it.isNotBlank() && !it.startsWith("http", ignoreCase = true)
            }
    }

    /** Lee un fichero exportado de otro panel. Devuelve null si no se reconoce nada. */
    fun parse(text: String): Result? {
        val limpio = text.trim()
        if (limpio.isEmpty()) return null

        val bruto = if (limpio.startsWith("{") || limpio.startsWith("[")) {
            parseJson(limpio)
        } else {
            parseHomerYaml(limpio)
        }

        if (bruto == null || bruto.entries.isEmpty()) return null

        return construir(bruto)
    }

    /**
     * Trae los iconos que el usuario tenía en su panel anterior.
     *
     * En el fichero solo hay rutas relativas («assets/icons/amule.png»), porque las
     * imágenes viven en el servidor de ese panel. Sabiendo su dirección se pueden
     * descargar, y así el panel importado conserva el aspecto que tenía.
     */
    suspend fun withOriginIcons(
        context: android.content.Context,
        config: PanelConfig,
        result: Result,
        base: String
    ): PanelConfig {
        fun absoluta(ruta: String): String = when {
            ruta.startsWith("http", ignoreCase = true) -> ruta
            else -> base.trimEnd('/') + "/" + ruta.trimStart('/')
        }

        val grupos = config.groups.map { grupo ->
            grupo.copy(
                services = grupo.services.map { servicio ->
                    val ruta = result.logoPaths[servicio.id]

                    if (ruta.isNullOrBlank() || servicio.iconFile.isNotBlank()) {
                        servicio
                    } else {
                        val fichero = IconStore.downloadUserIcon(
                            context,
                            absoluta(ruta),
                            servicio.id
                        )
                        if (fichero == null) servicio else servicio.copy(iconFile = fichero)
                    }
                }
            )
        }

        val logoPanel = result.panelLogoPath
            .takeIf { it.isNotBlank() && config.logoFile.isBlank() }
            ?.let { IconStore.downloadUserIcon(context, absoluta(it), "logo") }

        return config.copy(groups = grupos, logoFile = logoPanel ?: config.logoFile)
    }

    /**
     * Rellena las direcciones de fuera a partir de un segundo panel.
     *
     * Quien accede a su casa por VPN suele tener dos paneles: uno con las direcciones
     * locales y otro con las de fuera. Emparejando los servicios por su nombre se saca la
     * dirección alternativa de cada máquina, que es lo que hace aparecer el selector de
     * «en casa / fuera».
     *
     * @return la configuración nueva y cuántos servidores han recibido dirección de fuera.
     */
    fun applyAwayAddresses(
        config: PanelConfig,
        away: Result
    ): Pair<PanelConfig, Int> {
        // Nombre normalizado -> dirección en el panel de fuera.
        val porNombre = away.groups
            .flatMap { it.services }
            .associateBy({ normalizar(it.name) }, { direccionDe(away, it) })
            .filterValues { it.isNotBlank() }

        if (porNombre.isEmpty()) return config to 0

        // Para cada servidor, qué equipos aparecen en el panel de fuera.
        val candidatosPorServidor = mutableMapOf<String, MutableList<String>>()
        val serviciosActualizados = mutableMapOf<String, String>()

        config.allServices.forEach { servicio ->
            val fuera = porNombre[normalizar(servicio.name)] ?: return@forEach
            val hostFuera = hostOf(fuera)
            if (hostFuera.isBlank()) return@forEach

            if (servicio.serverId.isNotBlank()) {
                val servidor = config.server(servicio.serverId) ?: return@forEach
                // Solo interesa si de verdad es otra máquina.
                if (!hostFuera.equals(servidor.hostHome, ignoreCase = true)) {
                    candidatosPorServidor.getOrPut(servidor.id) { mutableListOf() } += hostFuera
                }
            } else if (fuera != config.urlOf(servicio, away = false)) {
                serviciosActualizados[servicio.id] = fuera
            }
        }

        // Si un servidor recibe varias direcciones distintas, gana la más repetida.
        val elegido = candidatosPorServidor.mapValues { (_, lista) ->
            lista.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
        }.filterValues { it.isNotBlank() }

        val servidores = config.servers.map { servidor ->
            val nueva = elegido[servidor.id]
            if (nueva.isNullOrBlank() || servidor.hostAway.isNotBlank()) servidor
            else servidor.copy(hostAway = nueva)
        }

        val grupos = config.groups.map { grupo ->
            grupo.copy(
                services = grupo.services.map { servicio ->
                    val propia = serviciosActualizados[servicio.id]
                    if (propia.isNullOrBlank() || servicio.urlOwnAway.isNotBlank()) servicio
                    else servicio.copy(urlOwnAway = propia)
                }
            )
        }

        val cuenta = servidores.count { nuevo ->
            val antiguo = config.servers.firstOrNull { it.id == nuevo.id }
            nuevo.hostAway.isNotBlank() && antiguo?.hostAway.isNullOrBlank()
        }

        return config.copy(servers = servidores, groups = grupos) to cuenta
    }

    /** Dirección completa de un servicio dentro de un resultado importado. */
    private fun direccionDe(result: Result, service: Service): String {
        val servidor = result.servers.firstOrNull { it.id == service.serverId }
        return if (servidor == null) {
            service.urlOwn
        } else {
            buildUrl(service.scheme, servidor.hostHome, service.port, service.path)
        }
    }

    /** Compara nombres sin distinguir mayúsculas, acentos ni espacios de sobra. */
    private fun normalizar(texto: String): String =
        java.text.Normalizer.normalize(texto.trim().lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s+"), " ")

    // ---- Estructura intermedia, antes de repartir por servidores ----

    private data class Entry(
        val group: String,
        val name: String,
        val subtitle: String,
        val url: String,
        val logo: String = ""
    )

    private data class Raw(
        val title: String,
        val subtitle: String,
        val entries: List<Entry>,
        val panelLogo: String = ""
    )

    // ---- Homer: config.yml ----

    /**
     * Lector del `config.yml` de Homer.
     *
     * Se hace a mano en vez de con una librería de YAML porque el formato de Homer es
     * fijo y sencillo, y así la aplicación no arrastra una dependencia entera para leer
     * un fichero. Lo que no encaja se ignora, de modo que un fichero con cosas raras
     * dentro sigue importando lo que sí se entiende.
     */
    private fun parseHomerYaml(text: String): Raw? {
        var title = ""
        var subtitle = ""
        var panelLogo = ""
        val entries = mutableListOf<Entry>()

        var enServicios = false
        var sangriaDeGrupo = -1
        var grupoActual = ""
        var enItems = false

        // Servicio que se está leyendo
        var nombre = ""
        var subtituloItem = ""
        var url = ""
        var logo = ""

        fun cerrarServicio() {
            if (nombre.isNotBlank() && url.isNotBlank()) {
                entries += Entry(grupoActual, nombre, subtituloItem, url, logo)
            }
            nombre = ""
            subtituloItem = ""
            url = ""
            logo = ""
        }

        text.lines().forEach { linea ->
            val sinComentario = quitarComentario(linea)
            if (sinComentario.isBlank()) return@forEach

            val sangria = sinComentario.indexOfFirst { !it.isWhitespace() }
            val contenido = sinComentario.trim()

            // Cabecera del fichero, antes de la lista de servicios.
            if (!enServicios) {
                when {
                    contenido.startsWith("services:") -> enServicios = true
                    contenido.startsWith("title:") -> title = valor(contenido)
                    contenido.startsWith("subtitle:") -> subtitle = valor(contenido)
                    contenido.startsWith("logo:") -> panelLogo = valor(contenido)
                }
                return@forEach
            }

            val esElemento = contenido.startsWith("- ")
            val cuerpo = if (esElemento) contenido.removePrefix("- ").trim() else contenido

            when {
                // Un elemento a la altura de los grupos, o el primero que aparece.
                esElemento && (sangriaDeGrupo < 0 || sangria <= sangriaDeGrupo) -> {
                    cerrarServicio()
                    sangriaDeGrupo = sangria
                    enItems = false
                    grupoActual = if (cuerpo.startsWith("name:")) valor(cuerpo) else ""
                }

                // Un elemento más adentro: es un servicio del grupo.
                esElemento -> {
                    cerrarServicio()
                    enItems = true
                    if (cuerpo.startsWith("name:")) nombre = valor(cuerpo)
                }

                contenido.startsWith("items:") -> {
                    enItems = true
                }

                // Propiedades del servicio que se está leyendo.
                enItems -> when {
                    contenido.startsWith("name:") && nombre.isBlank() -> nombre = valor(contenido)
                    contenido.startsWith("url:") -> url = valor(contenido)
                    contenido.startsWith("subtitle:") -> subtituloItem = valor(contenido)
                    contenido.startsWith("logo:") -> logo = valor(contenido)
                    contenido.startsWith("icon:") && logo.isBlank() -> logo = valor(contenido)
                }

                // Propiedades del grupo.
                contenido.startsWith("name:") && grupoActual.isBlank() ->
                    grupoActual = valor(contenido)
            }
        }

        cerrarServicio()

        return Raw(title, subtitle, entries, panelLogo).takeIf { it.entries.isNotEmpty() }
    }

    private fun quitarComentario(linea: String): String {
        // Un # dentro de comillas no empieza un comentario.
        var enComillas = false
        var comilla = ' '

        linea.forEachIndexed { indice, c ->
            when {
                enComillas && c == comilla -> enComillas = false
                !enComillas && (c == '"' || c == '\'') -> {
                    enComillas = true
                    comilla = c
                }
                !enComillas && c == '#' -> return linea.substring(0, indice)
            }
        }
        return linea
    }

    private fun valor(linea: String): String =
        linea.substringAfter(':').trim().trim('"', '\'').trim()

    // ---- JSON de otros paneles ----

    /**
     * Recorre un JSON cualquiera buscando objetos que tengan nombre y dirección. Sirve
     * para los ficheros de Homarr, Heimdall y compañía sin depender de la versión de cada
     * uno, que cambia a menudo.
     */
    private fun parseJson(text: String): Raw? {
        val entries = mutableListOf<Entry>()
        var title = ""

        runCatching {
            val raiz = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)

            if (raiz is JSONObject) {
                title = listOf("title", "name", "pageTitle")
                    .firstNotNullOfOrNull { raiz.optString(it).takeIf { v -> v.isNotBlank() } }
                    .orEmpty()
            }

            recorrer(raiz, "", entries)
        }.onFailure { Log.w(TAG, "El JSON no se pudo leer", it) }

        return Raw(title, "", entries).takeIf { it.entries.isNotEmpty() }
    }

    private fun recorrer(nodo: Any?, grupo: String, salida: MutableList<Entry>) {
        when (nodo) {
            is JSONObject -> {
                val nombre = primerTexto(nodo, "name", "title", "appName", "label")
                val url = primerTexto(nodo, "url", "href", "link", "internalUrl", "externalUrl")

                if (nombre.isNotBlank() && esDireccion(url)) {
                    salida += Entry(
                        group = grupo,
                        name = nombre,
                        subtitle = primerTexto(nodo, "subtitle", "description", "tagline"),
                        url = url,
                        logo = primerTexto(nodo, "logo", "icon", "iconUrl", "appIcon", "image")
                    )
                    // Aun así se sigue bajando: puede tener servicios anidados.
                }

                nodo.keys().forEach { clave ->
                    val hijo = nodo.opt(clave)
                    if (hijo is JSONObject || hijo is JSONArray) {
                        // Si este objeto tiene nombre y contiene una lista, hace de grupo.
                        val nuevoGrupo = if (nombre.isNotBlank() && url.isBlank()) nombre else grupo
                        recorrer(hijo, nuevoGrupo, salida)
                    }
                }
            }

            is JSONArray -> {
                for (i in 0 until nodo.length()) recorrer(nodo.opt(i), grupo, salida)
            }
        }
    }

    private fun primerTexto(objeto: JSONObject, vararg claves: String): String =
        claves.firstNotNullOfOrNull { clave ->
            objeto.optString(clave).takeIf { it.isNotBlank() && it != "null" }
        }.orEmpty()

    private fun esDireccion(texto: String): Boolean =
        texto.startsWith("http://", true) || texto.startsWith("https://", true)

    // ---- De direcciones sueltas a servidores y servicios ----

    /**
     * Reparte los servicios entre servidores. Las máquinas que aparecen en más de un
     * servicio pasan a ser un servidor; las direcciones sueltas de internet se quedan con
     * su dirección completa, que es lo que les corresponde.
     */
    private fun construir(raw: Raw): Result {
        val validas = raw.entries.filter { esDireccion(it.url) }
        if (validas.isEmpty()) return Result(raw.title, raw.subtitle, emptyList(), emptyList())

        val logos = mutableMapOf<String, String>()

        // Cuántas veces aparece cada máquina, y si es de la red privada.
        val cuentaPorHost = validas
            .mapNotNull { hostOf(it.url).takeIf { host -> host.isNotBlank() } }
            .groupingBy { it }
            .eachCount()

        val hostsDeServidor = cuentaPorHost.filter { (host, veces) ->
            veces > 1 || isPrivateHost("http://$host")
        }.keys

        val servidores = hostsDeServidor.mapIndexed { indice, host ->
            Server(id = "srv-imp-$indice-${host.hashCode()}", name = host, hostHome = host)
        }
        val servidorPorHost = servidores.associateBy { it.hostHome }

        var contador = 0

        val grupos = validas
            .groupBy { it.group }
            .map { (nombreGrupo, entradas) ->
                ServiceGroup(
                    id = "grp-imp-${nombreGrupo.hashCode()}",
                    name = nombreGrupo.ifBlank { raw.title.ifBlank { "" } },
                    services = entradas.map { entrada ->
                        contador++
                        servicioDe(entrada, servidorPorHost, contador).also { servicio ->
                            if (entrada.logo.isNotBlank()) logos[servicio.id] = entrada.logo
                        }
                    }
                )
            }

        return Result(
            title = raw.title,
            subtitle = raw.subtitle,
            servers = servidores,
            groups = grupos,
            logoPaths = logos,
            panelLogoPath = raw.panelLogo
        )
    }

    private fun servicioDe(
        entrada: Entry,
        servidorPorHost: Map<String, Server>,
        indice: Int
    ): Service {
        val uri = runCatching { URI(entrada.url) }.getOrNull()
        val host = uri?.host.orEmpty()
        val servidor = servidorPorHost[host]

        // El nombre delata muchas veces qué programa es: sirve para el icono y para saber
        // si necesita la versión de escritorio.
        val plantilla = ServiceTemplates.matchByName(entrada.name)

        val esquema = uri?.scheme?.lowercase() ?: "http"
        val puerto = uri?.port ?: -1
        val ruta = uri?.path?.takeIf { it.isNotBlank() } ?: "/"
        val consulta = uri?.rawQuery?.let { "?$it" }.orEmpty()
        val ancla = uri?.rawFragment?.let { "#$it" }.orEmpty()

        return Service(
            id = "svc-imp-$indice",
            name = entrada.name,
            subtitle = entrada.subtitle,
            serverId = servidor?.id.orEmpty(),
            scheme = esquema,
            port = if (puerto > 0) puerto else if (esquema == "https") 443 else 80,
            path = ruta + consulta + ancla,
            urlOwn = if (servidor == null) entrada.url else "",
            category = plantilla?.category ?: "generic",
            desktopMode = plantilla?.desktop ?: false
        )
    }
}
