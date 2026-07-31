package com.homelab.panel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import javax.net.ssl.SSLException

/**
 * Busca servicios en la red local, en dos pasos.
 *
 * El primer diseño buscaba una lista fija de puertos en toda la red y presentaba cada
 * puerto abierto como un servicio. Falló por tres motivos, todos comprobados en una red
 * doméstica real:
 *
 *  - Una lista de puertos **no encuentra los puertos personalizados**. Un aMule en el
 *    58711 en vez del 4711 de fábrica era invisible.
 *  - Una impresora con el 80, el 443 y el 8080 abiertos **salía tres veces**, igual que
 *    el router o el Pi-hole. Un equipo no es un servicio por cada puerto que tenga.
 *  - Ofrecía puertos que no son paneles web (SSH, carpetas compartidas, impresión), que
 *    solo hacen ruido.
 *
 * Ahora: primero se listan los **equipos** de la red, y cuando el usuario elige uno se
 * escanean **todos** sus puertos, se descarta lo que no hable HTTP y se identifica cada
 * panel por su título o por la cabecera `Server`, que delata aparatos sin título.
 */
object NetworkScan {

    private const val TAG = "Panel"

    /** Puertos para saber si una máquina está encendida. */
    private val PUERTOS_DE_TANTEO =
        listOf(80, 443, 8080, 22, 445, 5000, 9000, 631, 9100, 32400, 8006)

    /** Puertos web habituales, para nombrar el equipo en el primer paso. */
    private val PUERTOS_WEB_DE_TANTEO = listOf(80, 8080, 443)

    private const val TIMEOUT_TANTEO = 350
    /** En red local un servicio responde en milisegundos; esperar más solo alarga todo. */
    private const val TIMEOUT_PUERTO = 300

    // Por un túnel la ida y vuelta ya cuesta lo que en casa cuesta todo. Con los tiempos
    // de red local, una máquina que sí está se daba por apagada.
    private const val TIMEOUT_TANTEO_LEJOS = 1_500
    private const val TIMEOUT_PUERTO_LEJOS = 900
    private const val TIMEOUT_HTTP = 2_500
    private const val MAX_HTML = 32 * 1024

    /** Un equipo encontrado en la red. */
    data class Device(
        val host: String,
        val name: String,
        /** Primer puerto web que se le vio abierto, si alguno. */
        val webPort: Int?
    )

    /** Un panel web encontrado en un equipo. */
    data class WebService(
        val host: String,
        val port: Int,
        val scheme: String,
        val name: String,
        val category: String,
        val path: String,
        val desktop: Boolean
    )

    data class Progress(val done: Int, val total: Int)

    // ---- De qué red se trata ----

    /** De dónde sale un rango que se puede recorrer. */
    enum class NetworkKind { WIFI, ETHERNET, VPN, ROUTED, MESH, MANUAL, OTHER }

    /**
     * Un rango de direcciones donde buscar, del tipo `192.168.1.`
     *
     * No basta con mirar la red activa del móvil. Con una VPN levantada, **la red activa
     * es la VPN**: se leía su dirección (del rango `100.64.0.0/10` en el caso de Tailscale) y se
     * recorría ese rango, que no es una red de verdad —las direcciones de una malla se
     * reparten por todo el espacio, no por subredes—, así que no aparecía nada. Y al
     * encender la WiFi seguía saliendo la VPN, porque el sistema la mantiene como red por
     * omisión.
     */
    data class NetworkOption(
        /** Los tres primeros octetos con el punto: `192.168.1.` Vacío si hay [hosts]. */
        val prefix: String,
        val kind: NetworkKind,
        /** Se llega por un túnel: hay que dar mucho más tiempo a cada respuesta. */
        val remote: Boolean,
        /** Dirección del propio móvil en ese rango, si la tiene. */
        val own: String? = null,
        /**
         * Máquinas concretas, cuando no hay rango que recorrer. Es el caso de una VPN de
         * malla: sus equipos no son vecinos de subred, pero el propio túnel lleva una ruta
         * por cada uno, así que la lista viene dada.
         */
        val hosts: List<String> = emptyList()
    )

    /**
     * Rangos donde tiene sentido buscar, el más probable primero.
     *
     * Se miran **todas** las redes conectadas, no solo la activa, y se añaden las subredes
     * que la VPN trae de otro sitio: con un enrutador de subred, la red de casa se ve
     * desde fuera y buscar en ella funciona igual, solo que más despacio.
     */
    fun networks(context: Context): List<NetworkOption> = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val opciones = mutableListOf<NetworkOption>()

        @Suppress("DEPRECATION")
        val redes = cm.allNetworks

        redes.forEach { red ->
            val capacidades = cm.getNetworkCapabilities(red) ?: return@forEach
            val propiedades = cm.getLinkProperties(red) ?: return@forEach

            val esVpn = capacidades.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            val tipo = when {
                esVpn -> NetworkKind.VPN
                capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.WIFI
                capacidades.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.ETHERNET
                // Los datos del móvil no son una red que se pueda recorrer.
                capacidades.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> return@forEach
                else -> NetworkKind.OTHER
            }

            val propia = propiedades.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress

            if (propia != null && !(esVpn && esDeMalla(propia))) {
                prefijoDe(propia)?.let { prefijo ->
                    opciones += NetworkOption(prefijo, tipo, remote = esVpn, own = propia)
                }
            }

            if (esVpn) {
                // Subredes que llegan por el túnel: así se ve la red de casa desde fuera
                // cuando hay un enrutador de subred. La ruta general (0.0.0.0/0) y el
                // rango entero de la malla quedan fuera por su tamaño.
                propiedades.routes.forEach { ruta ->
                    val destino = ruta.destination.address
                    if (destino is Inet4Address &&
                        ruta.destination.prefixLength in 24..30 &&
                        esPrivada(destino)
                    ) {
                        prefijoDe(destino.hostAddress)?.let { prefijo ->
                            opciones += NetworkOption(prefijo, NetworkKind.ROUTED, remote = true)
                        }
                    }
                }

                // Equipos de la malla. Aquí no hay rango que recorrer —cada equipo tiene
                // una dirección suelta dentro de un espacio enorme—, pero el túnel trae
                // una ruta de una sola dirección por cada equipo conocido: esa es la
                // lista, y es la única forma de que buscar funcione estando fuera de casa.
                val equipos = propiedades.routes
                    .mapNotNull { it.destination }
                    .filter { it.prefixLength == 32 }
                    .mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                    .filter { esDeMalla(it) && it != propia && it !in DIRECCIONES_DE_SERVICIO }
                    .distinct()

                if (equipos.isNotEmpty()) {
                    opciones += NetworkOption(
                        prefix = "",
                        kind = NetworkKind.MESH,
                        remote = true,
                        hosts = equipos
                    )
                }
            }
        }

        // Si el mismo rango sale por dos sitios, gana el de la red local: es más rápido.
        opciones
            .sortedBy {
                when (it.kind) {
                    NetworkKind.WIFI -> 0
                    NetworkKind.ETHERNET -> 1
                    NetworkKind.OTHER -> 2
                    NetworkKind.ROUTED -> 3
                    NetworkKind.MESH -> 4
                    else -> 5
                }
            }
            .distinctBy { if (it.hosts.isEmpty()) it.prefix else "malla" }
    }.onFailure { Log.w(TAG, "No se pudieron leer las redes del móvil", it) }
        .getOrDefault(emptyList())

    /**
     * Convierte lo que escriba el usuario en un rango: vale `192.168.1`, `192.168.1.` o
     * una dirección completa como `192.168.1.50`. Devuelve null si no hay por dónde
     * cogerlo.
     */
    fun manualNetwork(texto: String): NetworkOption? {
        val trozos = texto.trim().trim('.').split('.')
        if (trozos.size < 3) return null

        val numeros = trozos.take(3).map { it.toIntOrNull() ?: return null }
        if (numeros.any { it !in 0..255 }) return null

        return NetworkOption(
            prefix = numeros.joinToString(".") + ".",
            kind = NetworkKind.MANUAL,
            // Puede ser la red de casa vista desde fuera, así que se espera como si lo
            // fuera: perder equipos por impaciencia es peor que tardar un poco más.
            remote = true
        )
    }

    /** `192.168.1.34` -> `192.168.1.` */
    private fun prefijoDe(direccion: String?): String? {
        val trozos = direccion?.split('.') ?: return null
        return if (trozos.size == 4) "${trozos[0]}.${trozos[1]}.${trozos[2]}." else null
    }

    /**
     * Rango de las VPN de malla (100.64.0.0/10, el de Tailscale). Recorrerlo no sirve de
     * nada: cada equipo recibe una dirección suelta dentro de un espacio enorme, así que
     * los vecinos de subred no existen.
     */
    private fun esDeMalla(direccion: String): Boolean {
        val trozos = direccion.split('.')
        val primero = trozos.getOrNull(0)?.toIntOrNull() ?: return false
        val segundo = trozos.getOrNull(1)?.toIntOrNull() ?: return false
        return primero == 100 && segundo in 64..127
    }

    /** Direcciones de la propia VPN, no de equipos: la de MagicDNS de Tailscale. */
    private val DIRECCIONES_DE_SERVICIO = setOf("100.100.100.100")

    private fun esPrivada(direccion: Inet4Address): Boolean =
        direccion.isSiteLocalAddress && !esDeMalla(direccion.hostAddress.orEmpty())

    // ---- Paso 1: equipos de la red ----

    /**
     * Direcciones de un rango. Se limita a 254 para no tardar horas: casi todas las redes
     * domésticas son de este tamaño.
     */
    private fun direccionesDe(red: NetworkOption): List<String> =
        if (red.hosts.isNotEmpty()) {
            red.hosts
        } else {
            (1..254).map { "${red.prefix}$it" }.filter { it != red.own }
        }

    /** Equipos encendidos en la red elegida, con el mejor nombre que se pueda averiguar. */
    suspend fun findDevices(
        context: Context,
        red: NetworkOption,
        onProgress: (Progress) -> Unit
    ): List<Device> {
        val direcciones = direccionesDe(red)
        val espera = if (red.remote) TIMEOUT_TANTEO_LEJOS else TIMEOUT_TANTEO

        val vivos = enParalelo(direcciones, 128, { onProgress(Progress(it, direcciones.size)) }) { ip ->
            val puertos = PUERTOS_DE_TANTEO.filter { Reachability.probePort(ip, it, espera) }
            if (puertos.isEmpty()) null else ip to puertos
        }

        // El nombre se busca en paralelo, que cada uno implica una consulta de red.
        return enParalelo(vivos, 16, { }) { (ip, puertos) ->
            val puertoWeb = PUERTOS_WEB_DE_TANTEO.firstOrNull { it in puertos }
            Device(host = ip, name = nombreDeEquipo(context, ip, puertoWeb), webPort = puertoWeb)
        }.sortedBy { dispositivo ->
            // Ordenadas por el último número de la dirección, no como texto.
            dispositivo.host.substringAfterLast('.').toIntOrNull() ?: 0
        }
    }

    /**
     * Nombre de un equipo: su nombre de red, y si no lo publica, lo que diga su servidor
     * web en la cabecera `Server`, que identifica muchos aparatos que no tienen título.
     */
    private suspend fun nombreDeEquipo(context: Context, host: String, puertoWeb: Int?): String {
        hostName(host).takeIf { it != host }?.let { return it }

        if (puertoWeb != null) {
            val esquema = if (puertoWeb == 443) "https" else "http"
            cabeceraServer("$esquema://$host:$puertoWeb/")?.let { return it }
            tituloDe("$esquema://$host:$puertoWeb/")?.let { return it }
        }

        return context.getString(R.string.scan_device_unknown, host)
    }

    // ---- Paso 2: paneles web de un equipo ----

    /**
     * Escanea **todos** los puertos de un equipo y devuelve los que sirven un panel web.
     *
     * Se recorre el rango completo a propósito: es la única forma de encontrar servicios
     * en puertos personalizados, que son justo los que el usuario no acertaría a escribir
     * de memoria.
     */
    suspend fun scanDevice(
        host: String,
        remote: Boolean,
        onProgress: (Progress, List<WebService>) -> Unit
    ): List<WebService> {
        val espera = if (remote) TIMEOUT_PUERTO_LEJOS else TIMEOUT_PUERTO

        // Los puertos conocidos primero: así los servicios que se esperan aparecen en los
        // primeros segundos y el resto del rango sigue buscándose de fondo.
        val conocidos = ServiceTemplates.scanPorts.filter { it in 1..65535 }
        val puertos = conocidos + (1..65535).filter { it !in conocidos }

        val encontrados = mutableListOf<WebService>()

        // Se identifica cada puerto en cuanto se ve abierto, en la misma pasada, para que
        // los hallazgos vayan saliendo en pantalla mientras se busca.
        val servicios = enParalelo(
            elementos = puertos,
            // Con 512 a la vez el proceso se queda sin descriptores de fichero, el socket
            // falla al crearse y ese puerto se da por cerrado sin más. 128 va sobrado y
            // deja margen para los sockets del resto de la aplicación.
            concurrencia = 128,
            informar = { hechos ->
                onProgress(Progress(hechos, puertos.size), instantanea(encontrados))
            }
        ) { puerto ->
            if (!Reachability.probePort(host, puerto, espera)) {
                null
            } else {
                describir(host, puerto)?.also { hallazgo ->
                    synchronized(encontrados) { encontrados.add(hallazgo) }
                }
            }
        }

        return servicios.sortedBy { it.port }
    }

    private fun instantanea(lista: MutableList<WebService>): List<WebService> =
        synchronized(lista) { lista.sortedBy { it.port } }

    /**
     * Mira qué hay en un puerto abierto. Devuelve null si no es un panel web.
     */
    private suspend fun describir(host: String, puerto: Int): WebService? =
        withContext(Dispatchers.IO) {
            // Primero en claro, que es lo más común en casa.
            respuestaHttp("http://$host:$puerto/")?.let { info ->
                if (esPanelWeb(info)) return@withContext construir(host, puerto, "http", info)
            }

            // Si no habla HTTP en claro, puede ser HTTPS.
            pruebaTls("https://$host:$puerto/")?.let { info ->
                if (esPanelWeb(info)) return@withContext construir(host, puerto, "https", info)
            }

            // Cualquier otra cosa (SSH, carpetas compartidas, impresión, la API de
            // Docker...) no es un panel que se pueda abrir en una pestaña.
            null
        }

    /**
     * Un panel web sirve páginas. Se exige HTML o un título legible: así se descartan las
     * interfaces de programación que responden JSON, como la de Docker en el 2376, que
     * hablan HTTP pero no son nada que se pueda mirar.
     */
    private fun esPanelWeb(info: Info): Boolean = info.isHtml || info.title != null

    private data class Info(val title: String?, val server: String?, val isHtml: Boolean)

    private fun construir(host: String, puerto: Int, esquema: String, info: Info): WebService {
        // El nombre, por orden de fiabilidad: el título de la página, lo que diga la
        // cabecera Server, y si no hay nada, el puerto si es de un programa inequívoco.
        val leido = info.title ?: info.server
        val fiable = ServiceTemplates.trustedByPort(puerto)

        // Si lo leído delata un programa conocido, se le pone su icono y su ruta: un
        // título «aMule control panel» da la categoría de descargas.
        val reconocido = leido?.let { ServiceTemplates.matchByName(it) } ?: fiable

        return WebService(
            host = host,
            port = puerto,
            scheme = esquema,
            // Vacío significa «no se ha podido averiguar»: la pantalla lo muestra como
            // «Servicio en el puerto N» en el idioma del usuario.
            name = leido ?: reconocido?.name.orEmpty(),
            category = reconocido?.category ?: "generic",
            path = reconocido?.path ?: "/",
            desktop = reconocido?.desktop ?: false
        )
    }

    /** Pide la página y devuelve su título y su cabecera Server, si habla HTTP. */
    private fun respuestaHttp(url: String): Info? = runCatching {
        val conexion = abrir(url) ?: return null

        try {
            val codigo = conexion.responseCode
            val servidor = conexion.getHeaderField("Server")?.trim()?.take(40)
            val tipo = conexion.getHeaderField("Content-Type").orEmpty().lowercase()
            val html = leerPrincipio(conexion, codigo >= 400)

            val titulo = html?.let { tituloDeHtml(it) }
                ?: html?.let { metaRefresh(it, url) }?.let { destino -> tituloDe(destino) }

            Info(
                title = titulo,
                server = servidor?.takeIf { it.isNotBlank() && !esNombreInutil(it) },
                isHtml = tipo.contains("text/html") ||
                    (tipo.isEmpty() && html?.contains("<html", ignoreCase = true) == true)
            )
        } finally {
            conexion.disconnect()
        }
    }.getOrNull()

    /**
     * Comprueba si en el puerto hay un servidor cifrado. Con certificado válido se leen
     * sus datos; con uno autofirmado el error de negociación ya confirma que hay TLS.
     *
     * No se desactiva la validación del certificado a propósito: aceptar cualquier
     * certificado sin preguntar es motivo de rechazo en la revisión de Google Play.
     */
    private fun pruebaTls(url: String): Info? {
        val conexion = abrir(url) ?: return null

        return try {
            val codigo = conexion.responseCode
            val servidor = conexion.getHeaderField("Server")?.trim()?.take(40)
            val tipo = conexion.getHeaderField("Content-Type").orEmpty().lowercase()
            val html = leerPrincipio(conexion, codigo >= 400)

            Info(
                title = html?.let { tituloDeHtml(it) },
                server = servidor?.takeIf { !esNombreInutil(it) },
                isHtml = tipo.contains("text/html")
            )
        } catch (e: SSLException) {
            // Un servidor cifrado de verdad falla por el certificado; un puerto que no
            // habla TLS (SSH, BitTorrent) falla porque lo que llega no es TLS.
            //
            // Aun siendo TLS no se puede mirar dentro, así que no hay forma de saber si
            // es un panel o una interfaz de programación como la de Docker en el 2376.
            // Solo se ofrece si el puerto es uno de los que sirven paneles cifrados.
            val esPuertoDePanel = puerto(url) in PUERTOS_TLS_DE_PANEL
            if (esFalloDeCertificado(e) && esPuertoDePanel) {
                Info(null, null, isHtml = true)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            conexion.disconnect()
        }
    }

    /** Puertos donde un servidor cifrado suele ser un panel y no otra cosa. */
    private val PUERTOS_TLS_DE_PANEL = setOf(443, 8443, 9443, 8006, 5001, 5443, 10443)

    private fun puerto(url: String): Int =
        runCatching { java.net.URI(url).port }.getOrDefault(-1)

    /** Distingue «el certificado no me gusta» de «esto no es TLS». */
    private fun esFalloDeCertificado(e: SSLException): Boolean {
        val texto = generateSequence(e as Throwable) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" ")
            .lowercase()

        if (texto.contains("unrecognized ssl message") ||
            texto.contains("plaintext connection") ||
            texto.contains("record overflow") ||
            texto.contains("protocol version")
        ) {
            return false
        }

        return texto.contains("certificate") ||
            texto.contains("trust anchor") ||
            texto.contains("certpath") ||
            texto.contains("hostname")
    }

    private fun abrir(url: String): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_HTTP
            readTimeout = TIMEOUT_HTTP
            instanceFollowRedirects = true
            setRequestProperty("Accept", "text/html,*/*;q=0.5")
        }
    }.getOrNull()

    /** Título de una dirección concreta. */
    private fun tituloDe(url: String): String? = respuestaHttp(url)?.title

    private fun cabeceraServer(url: String): String? = respuestaHttp(url)?.server

    /**
     * Lee el principio del documento.
     *
     * En bucle: una sola lectura devuelve solo lo que haya llegado en ese momento, muchas
     * veces un par de kilobytes, y el título se perdía casi siempre.
     */
    private fun leerPrincipio(conexion: HttpURLConnection, esError: Boolean): String? = runCatching {
        val flujo = if (esError) conexion.errorStream else conexion.inputStream

        flujo?.use { entrada ->
            val texto = StringBuilder()
            val buffer = ByteArray(4096)

            while (texto.length < MAX_HTML) {
                val leidos = entrada.read(buffer)
                if (leidos <= 0) break
                texto.append(String(buffer, 0, leidos))
                if (texto.contains("</head", ignoreCase = true)) break
            }

            texto.toString().takeIf { it.isNotEmpty() }
        }
    }.getOrNull()

    private fun tituloDeHtml(html: String): String? {
        val titulo = Regex(
            """<title[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)
            ?.groupValues
            ?.get(1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(40)
            ?: return null

        return titulo.takeIf { it.isNotBlank() && !esNombreInutil(it) }
    }

    /** Dirección de un `<meta http-equiv="refresh">`, para páginas que solo redirigen. */
    private fun metaRefresh(html: String, origen: String): String? {
        val contenido = Regex(
            """<meta[^>]+http-equiv\s*=\s*["']refresh["'][^>]*content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) ?: return null

        val destino = Regex("""url\s*=\s*['"]?([^'";]+)""", RegexOption.IGNORE_CASE)
            .find(contenido)?.groupValues?.get(1)?.trim() ?: return null

        return runCatching { java.net.URI(origen).resolve(destino).toString() }.getOrNull()
    }

    /**
     * Nombres que no dicen nada del servicio. Vale más dejarlo sin nombre que llamar
     * «Iniciar sesión» o «nginx» a un panel.
     */
    private fun esNombreInutil(texto: String): Boolean {
        val t = texto.lowercase().trim()

        return t.isEmpty() ||
            t.length < 3 ||
            // Cabeceras que son un número de versión o el sistema operativo, como
            // «3.4.6-generic Microsoft-Windows/6.1»: no dicen qué servicio es.
            t.first().isDigit() ||
            t.contains("microsoft-windows") ||
            t.contains("upnp") ||
            t in setOf(
                "login", "log in", "sign in", "signin", "iniciar sesión", "acceso",
                "index", "home", "inicio", "welcome", "bienvenido", "dashboard",
                "error", "unauthorized", "forbidden", "not found", "document",
                "nginx", "apache", "lighttpd", "httpd", "microsoft-iis", "envoy",
                "cloudflare", "caddy", "gunicorn", "werkzeug", "kestrel", "openresty"
            ) ||
            t.startsWith("index of /") ||
            t.startsWith("nginx/") ||
            t.startsWith("apache/") ||
            // Cualquier página de error HTTP: «400 Bad Request», «502 Bad Gateway»…
            Regex("""^\d{3}\b""").containsMatchIn(t) ||
            t.contains("bad request") || t.contains("bad gateway") ||
            (t.contains("apache2") && t.contains("default page")) ||
            t == "welcome to nginx!"
    }

    /** Nombre de red de una máquina, si lo publica; si no, su dirección. */
    suspend fun hostName(host: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val nombre = InetAddress.getByName(host).canonicalHostName
            if (nombre.equals(host, ignoreCase = true)) host
            else nombre.substringBefore('.').ifBlank { host }
        }.getOrDefault(host)
    }

    /** Ejecuta un trabajo sobre una lista con concurrencia limitada. */
    private suspend fun <T, R> enParalelo(
        elementos: List<T>,
        concurrencia: Int,
        informar: (Int) -> Unit,
        trabajo: suspend (T) -> R?
    ): List<R> = coroutineScope {
        val permisos = Semaphore(concurrencia)
        var hechos = 0

        elementos.map { elemento ->
            async {
                val resultado = permisos.withPermit { trabajo(elemento) }

                val cuenta = synchronized(elementos) { ++hechos }
                // Se informa de vez en cuando: refrescar la pantalla en cada paso la deja
                // pegada. Al principio más a menudo, que es cuando salen los hallazgos.
                val cada = if (cuenta < 2000) 64 else 512
                if (cuenta % cada == 0 || cuenta == elementos.size) informar(cuenta)

                resultado
            }
        }.awaitAll().filterNotNull()
    }
}
