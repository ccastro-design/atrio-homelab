package com.homelab.panel

import kotlinx.serialization.Serializable

/**
 * Cómo se decide qué dirección usar para llegar a los servicios.
 *
 * AUTO comprueba si la dirección de casa responde; si no, usa la de fuera. Se eligió
 * la alcanzabilidad en vez del nombre de la red WiFi porque no necesita permiso de
 * ubicación, funciona igual por cable y no depende de que el usuario escriba bien el
 * nombre de su red.
 */
enum class NetworkProfile { AUTO, HOME, AWAY }

/**
 * Tipos de enlace que la aplicación sabe entregar a un servicio de descarga.
 *
 * No hay tipo para «enlace directo» a propósito. Existió mientras estuvo previsto pyLoad,
 * y era un problema: un enlace directo es **cualquier dirección web**, así que con un
 * destino así configurado toda página pasaba a ser enviable y la aplicación se ofrecía a
 * descargar cualquier cosa que se compartiera con ella. Los cuatro tipos de aquí son
 * inequívocos: se reconocen por el propio enlace y no se confunden con navegar.
 */
enum class LinkKind { ED2K, MAGNET, TORRENT, NZB }

/**
 * Clase de servicio de descarga, con los tipos de enlace que acepta de fábrica. El
 * usuario puede quitar tipos a un destino concreto, pero no añadirle los que su
 * programa no entiende.
 */
enum class TargetKind(val defaults: Set<LinkKind>, val displayName: String) {
    // El nombre se escribe tal como lo escribe cada programa, y no se traduce.
    AMULE(setOf(LinkKind.ED2K), "aMule"),
    QBITTORRENT(setOf(LinkKind.MAGNET, LinkKind.TORRENT), "qBittorrent"),
    TRANSMISSION(setOf(LinkKind.MAGNET, LinkKind.TORRENT), "Transmission"),
    SABNZBD(setOf(LinkKind.NZB), "SABnzbd");

    companion object {
        fun from(value: String): TargetKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: AMULE
    }
}

/**
 * Una máquina que aloja servicios. En un servidor doméstico casi todos los servicios
 * viven en el mismo equipo, así que su dirección se define una vez aquí y cada
 * servicio indica solo su puerto y su ruta. Cambiar la IP del servidor es entonces un
 * cambio en un sitio y no en quince.
 */
@Serializable
data class Server(
    val id: String = "",
    val name: String = "",
    /** Nombre o IP dentro de la red local, sin esquema ni puerto. */
    val hostHome: String = "",
    /** Dirección para llegar desde fuera. Vacía significa la misma que la de casa. */
    val hostAway: String = ""
) {
    fun host(away: Boolean): String =
        if (away && hostAway.isNotBlank()) hostAway else hostHome
}

/**
 * Un servicio del panel. Su dirección se construye de dos maneras: colgando de un
 * servidor (lo normal, indicando puerto y ruta) o con dirección propia completa, para
 * páginas de internet y servicios que no están en ninguno de los servidores.
 */
@Serializable
data class Service(
    val id: String = "",
    val name: String = "",
    val subtitle: String = "",

    /** Servidor del que cuelga. Vacío significa dirección propia. */
    val serverId: String = "",
    val scheme: String = "http",
    /** 0 significa el puerto por omisión del esquema. */
    val port: Int = 0,
    val path: String = "/",

    /** Usadas solo cuando no hay servidor. La de fuera vacía significa la misma. */
    val urlOwn: String = "",
    val urlOwnAway: String = "",

    /** Icono de categoría del juego propio. Ver [Categories]. */
    val category: String = "generic",
    /** Nombre del fichero de imagen aportada por el usuario, si puso una. */
    val iconFile: String = "",
    /** Pedir al propio servicio su icono, como hace un navegador. */
    val useFavicon: Boolean = true,

    /** Abrir fuera del panel en vez de en una pestaña propia. */
    val openExternal: Boolean = false,
    /**
     * Aplicación del móvil que abre este servicio. Vacío significa dejárselo al sistema,
     * que en la práctica es el navegador. Ver [ExternalApps]: Android no puede deducir la
     * aplicación a partir de la dirección de un servicio autoalojado.
     */
    val externalPackage: String = "",
    /**
     * Pedir la versión de escritorio del sitio. Los paneles pensados para pantalla
     * grande se apiñan o se cortan con la versión móvil.
     */
    val desktopMode: Boolean = false,
    val autoLogin: Boolean = false,
    val checkStatus: Boolean = true,
    /**
     * Avisar en la tarjeta de que este servicio va sin cifrar.
     *
     * Se puede quitar servicio a servicio, igual que la comprobación: en una red de casa
     * casi todo va por HTTP y el aviso repetido quince veces deja de avisar de nada. Lo
     * que **no** se puede desactivar es la confirmación de un certificado desconocido:
     * eso no es un aviso, es una decisión.
     */
    val warnCleartext: Boolean = true,

    /**
     * Servicio de ejemplo del primer arranque. No se comprueba ni se intenta abrir:
     * al pulsarlo se abre su ficha explicada, para que sirva de plantilla en vez de
     * dar la sensación de que la aplicación está rota.
     */
    val isExample: Boolean = false
)

@Serializable
data class ServiceGroup(
    val id: String = "",
    val name: String = "",
    /**
     * Color del grupo, «#RRGGBB». Vacío significa el del tema. Tiñe su cabecera y el
     * borde de sus tarjetas: con tres o cuatro grupos, el color hace de índice y se
     * localiza el que se busca sin leer.
     */
    val color: String = "",
    val services: List<Service> = emptyList()
)

/**
 * Un destino al que enviar enlaces. Son instancias, no clases: quien tenga dos
 * qBittorrent, uno en el servidor de casa y otro en un servidor remoto, define dos
 * destinos y elige entre ellos.
 *
 * La contraseña no se guarda aquí, sino cifrada. Ver [SecureStore].
 */
@Serializable
data class DownloadTarget(
    val id: String = "",
    val name: String = "",
    /** Nombre de [TargetKind]. Se guarda como texto para tolerar versiones futuras. */
    val kind: String = TargetKind.AMULE.name,

    val serverId: String = "",
    val scheme: String = "http",
    val port: Int = 0,
    val path: String = "/",
    val urlOwn: String = "",
    val urlOwnAway: String = "",

    val username: String = "",
    /** Nombres de [LinkKind] que este destino acepta. Vacío significa los de fábrica. */
    val accepts: List<String> = emptyList()
) {
    val targetKind: TargetKind get() = TargetKind.from(kind)

    /** Tipos de enlace que acepta de verdad, ya resueltos. */
    val acceptedKinds: Set<LinkKind>
        get() = if (accepts.isEmpty()) targetKind.defaults
        else accepts.mapNotNull { name ->
            LinkKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }.toSet()
}

@Serializable
data class PanelConfig(
    /** Versión del formato, para poder migrar configuraciones antiguas. */
    val formatVersion: Int = 1,

    /** Vacío significa usar el nombre de la aplicación traducido. */
    val title: String = "",
    val subtitle: String = "",
    /** Imagen del encabezado aportada por el usuario. Vacío: el logotipo de la app. */
    val logoFile: String = "",
    /**
     * Icono del juego propio usado como logotipo, cuando el usuario no pone una imagen
     * suya. Ver [Categories]. Vacío significa el icono de la aplicación.
     */
    val logoIcon: String = "",

    /**
     * Tema: `LIGHT`, `DARK` o `SYSTEM`.
     *
     * Claro de partida y no el del sistema: es el que mejor sienta al panel, y así se ve
     * igual en cualquier móvil sin depender de cómo lo tenga configurado su dueño. Quien
     * prefiera otra cosa lo cambia aquí.
     */
    val theme: String = "LIGHT",

    // ---- Colores e imagen elegidos por el usuario ----
    //
    // Todos vacíos de partida, que significa «lo que diga el tema». Se guardan como
    // «#RRGGBB» y no como número para que un fichero de configuración exportado se pueda
    // leer y entender de un vistazo.

    // De cada color hay dos: el del tema claro y el del oscuro. Un color fijo no puede
    // seguir al tema —el blanco que se lee sobre un fondo oscuro desaparece sobre uno
    // claro—, así que se guardan por separado. **El de oscuro vacío significa «el mismo
    // que en claro»**, con lo que una configuración de las de antes se sigue viendo igual.

    /** Fondo del panel. */
    val backgroundColor: String = "",
    val backgroundColorDark: String = "",
    /** Imagen de fondo del panel. Manda sobre el color. */
    val backgroundImage: String = "",
    /**
     * Cuánto se atenúa la imagen para que el texto siga leyéndose, de 0 a 100.
     *
     * De fábrica **sin atenuar**: los fondos que trae la aplicación ya son oscuros y el
     * texto se lee encima sin ayuda, así que velarlos solo los ensucia. Quien ponga una
     * foto clara tiene el deslizador en Apariencia.
     */
    val backgroundDim: Int = 0,
    /** Desenfoque de la imagen de fondo, en píxeles de pantalla. */
    val backgroundBlur: Int = 0,
    /** Aumento de la imagen y desplazamiento, para elegir qué parte se ve. */
    val backgroundScale: Float = 1f,
    val backgroundOffsetX: Float = 0f,
    val backgroundOffsetY: Float = 0f,
    /**
     * Opacidad de las tarjetas de servicio, de 0 a 100. Menos de 100 deja ver el fondo
     * a través de ellas.
     */
    val cardOpacity: Int = 100,
    /** Alto de las tarjetas: `COMPACT`, `NORMAL` o `COMFY`. */
    val cardSize: String = "NORMAL",
    /**
     * Enseñar la dirección debajo del nombre del servicio. Es el único dato técnico que
     * se ve en el panel: quien tiene quince servicios la usa para distinguirlos, y a
     * quien tiene cuatro le sobra.
     */
    val showAddress: Boolean = true,
    /** Color de los textos sobre el fondo. */
    val textColor: String = "",
    val textColorDark: String = "",
    /**
     * Color del nombre del servicio dentro de su tarjeta.
     *
     * Va aparte de [textColor] porque no está sobre el fondo, sino sobre la tarjeta: con
     * las tarjetas semitransparentes y un fondo de por medio, el color que se lee bien en
     * uno no tiene por qué leerse en la otra. Vacío significa el del tema.
     */
    val serviceNameColor: String = "",
    val serviceNameColorDark: String = "",
    /**
     * Color de los nombres de grupo —«Servicios Docker», «Red»— cuando el grupo no tiene
     * uno propio. El del grupo manda sobre este: quien se lo pone a un grupo concreto es
     * justamente para distinguirlo de los demás. Vacío significa el del tema.
     */
    val groupNameColor: String = "",
    val groupNameColorDark: String = "",
    /** Fondo de la barra superior. */
    val bannerColor: String = "",
    val bannerColorDark: String = "",
    /** Título e iconos de la barra superior. */
    val bannerTextColor: String = "",
    val bannerTextColorDark: String = "",

    val profile: String = NetworkProfile.AUTO.name,
    /** Etiquetas de los dos perfiles. Vacías: los textos traducidos por omisión. */
    val labelHome: String = "",
    val labelAway: String = "",

    /**
     * Desbloqueo biométrico al abrir, desactivado de partida. Un panel de accesos que
     * exige la huella sin que nadie lo haya pedido es fricción; quien guarde
     * contraseñas dentro lo activa desde Ajustes.
     */
    val requireUnlock: Boolean = false,

    /**
     * Minutos fuera de la aplicación tras los cuales se vuelve a pedir el desbloqueo.
     * `-1` significa no volver a pedirlo hasta cerrarla del todo.
     *
     * Sin esto, la huella solo se pedía al arrancar en frío: salir al escritorio y volver
     * dejaba entrar de vuelta sin preguntar nada, con las pestañas abiertas y sus sesiones
     * iniciadas.
     */
    val relockMinutes: Int = 1,
    /**
     * Ocultar la aplicación en la lista de aplicaciones recientes y prohibir capturas.
     * Apagado de partida: también impide al usuario hacer sus propias capturas.
     */
    val secureScreen: Boolean = false,
    /** Cerrar las sesiones de las pestañas al salir de la aplicación. */
    val clearSessionsOnExit: Boolean = false,

    /** Comprobar si cada servicio responde, mientras el panel está en pantalla. */
    val checkStatus: Boolean = true,
    /**
     * Avisar en las tarjetas de los servicios que van sin cifrar. Llave maestra del aviso
     * de cada servicio, igual que [checkStatus] lo es de su comprobación.
     */
    val warnCleartext: Boolean = true,

    val servers: List<Server> = emptyList(),
    val groups: List<ServiceGroup> = emptyList(),
    val downloadTargets: List<DownloadTarget> = emptyList(),

    /**
     * Destino elegido para cada tipo de enlace: nombre de [LinkKind] al id del
     * destino. Sin entrada, o con el id vacío, significa preguntar.
     */
    val linkRouting: Map<String, String> = emptyMap(),

    /**
     * Servidores cuyo certificado no verificado aceptó el usuario expresamente,
     * con la huella que aceptó. Si el certificado cambia, se vuelve a preguntar.
     */
    val trustedCerts: Map<String, String> = emptyMap(),

    /**
     * Si ya se ha visto la presentación de la aplicación. Va en la configuración y no en
     * unas preferencias aparte para que restaurar una copia de seguridad en un móvil
     * nuevo no la vuelva a sacar.
     */
    val tutorialSeen: Boolean = false,

    /**
     * Nombres de las redes WiFi de casa. Solo en ellas se usan las direcciones locales;
     * en cualquier otra se va por la VPN aunque algo responda, porque ese algo puede ser
     * la máquina de otra persona. Ver [WifiNetwork].
     */
    val homeSsids: List<String> = emptyList()
) {
    val networkProfile: NetworkProfile
        get() = runCatching { NetworkProfile.valueOf(profile) }.getOrDefault(NetworkProfile.AUTO)

    // ---- Colores según el tema que esté puesto ----
    //
    // Sin color para el oscuro se usa el del claro, que es lo que hace que una
    // configuración anterior a esto se vea exactamente igual que antes.

    fun backgroundColorOf(dark: Boolean) = porTema(backgroundColor, backgroundColorDark, dark)
    fun textColorOf(dark: Boolean) = porTema(textColor, textColorDark, dark)
    fun bannerColorOf(dark: Boolean) = porTema(bannerColor, bannerColorDark, dark)
    fun bannerTextColorOf(dark: Boolean) = porTema(bannerTextColor, bannerTextColorDark, dark)
    fun serviceNameColorOf(dark: Boolean) = porTema(serviceNameColor, serviceNameColorDark, dark)
    fun groupNameColorOf(dark: Boolean) = porTema(groupNameColor, groupNameColorDark, dark)

    val allServices: List<Service> get() = groups.flatMap { it.services }

    fun server(id: String): Server? = servers.firstOrNull { it.id == id }

    /** Dirección efectiva de un servicio según el perfil de red activo. */
    fun urlOf(service: Service, away: Boolean): String {
        val server = server(service.serverId)

        if (server == null) {
            val own = service.urlOwn.trim()
            val awayUrl = service.urlOwnAway.trim()
            return if (away && awayUrl.isNotEmpty()) awayUrl else own
        }

        return buildUrl(service.scheme, server.host(away), service.port, service.path)
    }

    /** Dirección de casa de un servicio, para comprobar si estamos en su red. */
    fun homeUrlOf(service: Service): String = urlOf(service, away = false)

    /**
     * Las dos direcciones de un servicio juntas, para saber si su icono sigue valiendo.
     *
     * El icono se guarda por servicio y no por dirección, o se perdería cada vez que se
     * sale de casa. Pero entonces hay que notar cuándo el servicio **se muda de verdad**:
     * si cambia de máquina o de puerto, el icono guardado puede ser el de otro programa.
     * Van las dos direcciones porque cualquiera de ellas puede cambiar sola.
     */
    fun iconOrigin(service: Service): String =
        urlOf(service, away = false) + "|" + urlOf(service, away = true)

    /**
     * Por qué un servicio no se puede abrir, o `null` si sí tiene dirección.
     *
     * Un servicio saca su dirección de un servidor —que guarda la de la red local y la de
     * fuera— o de la suya propia, que es lo que se usa para páginas de internet. Cuando no
     * queda ninguna de las dos no hay nada que abrir, y antes tocar la tarjeta **no hacía
     * absolutamente nada**: el panel se lo callaba y parecía que la aplicación se había
     * quedado colgada.
     *
     * El caso corriente es borrar un servidor: sus servicios siguen en el panel apuntando
     * a un identificador que ya no existe.
     */
    fun motivoSinDireccion(service: Service, away: Boolean): NoSePuedeAbrir? {
        if (urlOf(service, away).isNotBlank()) return null

        if (service.serverId.isNotBlank()) {
            return if (server(service.serverId) == null) {
                NoSePuedeAbrir.SERVIDOR_BORRADO
            } else {
                NoSePuedeAbrir.SERVIDOR_SIN_DIRECCION
            }
        }

        return NoSePuedeAbrir.NI_SERVIDOR_NI_DIRECCION
    }

    /** Dirección efectiva de un destino de descarga. */
    fun urlOf(target: DownloadTarget, away: Boolean): String {
        val server = server(target.serverId)

        if (server == null) {
            val own = target.urlOwn.trim()
            val awayUrl = target.urlOwnAway.trim()
            return if (away && awayUrl.isNotEmpty()) awayUrl else own
        }

        return buildUrl(target.scheme, server.host(away), target.port, target.path)
    }

    /** True si el servicio tiene una dirección propia para cuando no estás en casa. */
    fun hasAwayAddress(service: Service): Boolean {
        val server = server(service.serverId)
        return if (server == null) service.urlOwnAway.isNotBlank() else server.hostAway.isNotBlank()
    }

    /** Destinos capaces de aceptar un tipo de enlace. */
    fun targetsFor(kind: LinkKind): List<DownloadTarget> =
        downloadTargets.filter { kind in it.acceptedKinds }

    /** Destino fijado para un tipo de enlace, si sigue existiendo. */
    fun preferredTarget(kind: LinkKind): DownloadTarget? {
        val id = linkRouting[kind.name].orEmpty()
        if (id.isBlank()) return null
        return downloadTargets.firstOrNull { it.id == id && kind in it.acceptedKinds }
    }

    /** True si no hay ningún servicio de verdad, solo los ejemplos o nada. */
    val isEmpty: Boolean get() = allServices.none { !it.isExample }

    val hasExamples: Boolean get() = allServices.any { it.isExample }
}

/** Une esquema, equipo, puerto y ruta en una dirección completa. */
/**
 * Motivos por los que un servicio no tiene con qué abrirse.
 *
 * Cada uno se explica al usuario con sus palabras: no es lo mismo haber borrado el
 * servidor que tenerlo sin rellenar, y la solución tampoco.
 */
enum class NoSePuedeAbrir {
    /** Apunta a un servidor que ya no está en la lista, casi siempre porque se borró. */
    SERVIDOR_BORRADO,

    /** El servidor existe pero está sin dirección: ni la de la red local ni la de fuera. */
    SERVIDOR_SIN_DIRECCION,

    /** No cuelga de ningún servidor y tampoco tiene dirección web propia. */
    NI_SERVIDOR_NI_DIRECCION
}

fun buildUrl(scheme: String, host: String, port: Int, path: String): String {
    if (host.isBlank()) return ""

    val esquema = scheme.ifBlank { "http" }.trimEnd(':', '/')
    val puerto = when {
        port <= 0 -> ""
        esquema == "http" && port == 80 -> ""
        esquema == "https" && port == 443 -> ""
        else -> ":$port"
    }
    val ruta = path.trim().let {
        when {
            it.isEmpty() -> "/"
            it.startsWith("/") -> it
            else -> "/$it"
        }
    }

    return "$esquema://${host.trim()}$puerto$ruta"
}

/** Equipo de una dirección, para avisos y para agrupar excepciones de certificado. */
fun hostOf(url: String): String = runCatching {
    java.net.URI(url).host.orEmpty()
}.getOrDefault("")

/** True si la dirección viaja sin cifrar. */
fun isCleartext(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

/**
 * True si la dirección apunta a la red privada del usuario. Sirve para avisar de que un
 * servicio así no se va a alcanzar desde fuera de casa sin una dirección alternativa.
 */
fun isPrivateHost(url: String): Boolean {
    val host = hostOf(url).lowercase()
    if (host.isEmpty()) return false

    return host == "localhost" ||
        host.endsWith(".local") ||
        host.endsWith(".lan") ||
        host.endsWith(".home") ||
        host.startsWith("192.168.") ||
        host.startsWith("10.") ||
        host.startsWith("127.") ||
        Regex("""^172\.(1[6-9]|2\d|3[01])\.""").containsMatchIn(host)
}

/**
 * El color que toca según el tema.
 *
 * El del tema oscuro en blanco significa «el mismo que en el claro»: así, quien nunca haya
 * tocado los colores del oscuro ve exactamente lo de siempre, y quien los toque manda.
 */
private fun porTema(claro: String, oscuro: String, dark: Boolean): String =
    if (dark) oscuro.ifBlank { claro } else claro
