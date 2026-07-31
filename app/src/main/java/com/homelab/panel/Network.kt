package com.homelab.panel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * Comprobación de si una dirección responde.
 *
 * Se abre una conexión al puerto en vez de pedir la página por HTTP: es mucho más
 * rápido, no arrastra autenticaciones ni redirecciones, y para saber si un servicio
 * está en pie basta con que su puerto atienda.
 */
object Reachability {

    /** Milisegundos que tardó en responder, o null si no respondió. */
    suspend fun probe(url: String, timeoutMs: Int = 1500): Long? = withContext(Dispatchers.IO) {
        val destino = parse(url) ?: return@withContext null
        val inicio = System.nanoTime()

        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(destino.first, destino.second), timeoutMs)
            }
            (System.nanoTime() - inicio) / 1_000_000
        }.getOrNull()
    }

    /** Versión directa para el buscador de red, que ya sabe equipo y puerto. */
    suspend fun probePort(host: String, port: Int, timeoutMs: Int): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), timeoutMs)
                }
                true
            }.getOrDefault(false)
        }

    /** Equipo y puerto de una dirección, resolviendo el puerto implícito del esquema. */
    private fun parse(url: String): Pair<String, Int>? = runCatching {
        val uri = URI(url.trim())
        val host = uri.host ?: return@runCatching null
        if (host.isBlank()) return@runCatching null

        val port = when {
            uri.port > 0 -> uri.port
            uri.scheme.equals("https", ignoreCase = true) -> 443
            else -> 80
        }
        host to port
    }.getOrNull()
}

/**
 * El nombre de la red WiFi a la que está conectado el móvil.
 *
 * Hace falta porque «alguien responde en la dirección de mi NAS» no significa que sea mi
 * NAS: `192.168.1.254:8080` existe en media España, y en casa de un amigo con homelab la
 * comprobación de alcance daba «estoy en casa» y el panel acababa entrando en su máquina
 * —y mandándole las contraseñas guardadas—.
 *
 * Se intentó primero deducir la red sin permisos, por su subred, su puerta de enlace y sus
 * servidores DNS. No vale: `192.168.1.0/24` con el router en el `.1` y el DNS de Cloudflare
 * es la instalación por omisión de medio mundo, así que dos casas salían idénticas. El
 * nombre de la WiFi sí las distingue, y además el usuario lo entiende y lo controla.
 *
 * **El precio es el permiso de ubicación**, que es lo que Android exige para leerlo. Ver
 * `AndroidManifest.xml`.
 */
object WifiNetwork {

    /** Permisos que Android pide para dejar leer el nombre de la red. */
    val PERMISSIONS = arrayOf(
        android.Manifest.permission.ACCESS_FINE_LOCATION,
        android.Manifest.permission.ACCESS_COARSE_LOCATION
    )

    fun hasPermission(context: Context): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * Si el móvil tiene la ubicación encendida.
     *
     * No basta con conceder el permiso: con el interruptor de ubicación apagado, Android
     * devuelve `<unknown ssid>` igualmente. Sin comprobarlo, la aplicación fallaría en
     * silencio y el usuario no sabría por qué.
     */
    fun locationEnabled(context: Context): Boolean = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) ||
                lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
        }
    }.getOrDefault(false)

    /** Nombre de la WiFi actual, o cadena vacía si no hay, no se puede leer, o va por cable. */
    fun currentSsid(context: Context): String = runCatching {
        if (!hasPermission(context) || !locationEnabled(context)) return ""

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        // Con la VPN levantada `activeNetwork` devuelve el túnel, que no tiene nombre de
        // WiFi: hay que buscar la red física.
        @Suppress("DEPRECATION")
        val hayWifi = cm.allNetworks.any { red ->
            cm.getNetworkCapabilities(red)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (!hayWifi) return ""

        val wifi = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager

        @Suppress("DEPRECATION")
        val nombre = wifi.connectionInfo?.ssid.orEmpty().trim('"')

        // Android devuelve este literal cuando no puede o no quiere decirlo.
        if (nombre.isBlank() || nombre == DESCONOCIDA) "" else nombre
    }.getOrDefault("")

    private const val DESCONOCIDA = "<unknown ssid>"
}

/**
 * En qué red estamos y qué hacer al respecto.
 *
 * @param away si toca usar las direcciones de fuera.
 * @param ask WiFi desconocida en la que algo responde: hay que preguntar antes de fiarse.
 */
data class NetworkVerdict(
    val away: Boolean,
    val ask: String? = null
)

/**
 * Decide si hay que usar las direcciones de fuera.
 *
 * Manda **el nombre de la WiFi** (ver [WifiNetwork]): si estás en una de las que has
 * registrado, direcciones de casa; en cualquier otra, las de fuera, aunque algo responda.
 * Que una dirección conteste no prueba nada, porque la del vecino puede ser la misma.
 *
 * Cuando no hay nombre que leer —por cable, sin permiso, o con la ubicación apagada— se
 * cae al método de siempre: comprobar si la dirección de casa responde. Es menos seguro,
 * pero es preferible a dejar el panel inservible.
 *
 * El resultado se recuerda mientras no cambie la red, para no repetir la comprobación
 * cada vez que se pinta el panel.
 */
object NetworkResolver {

    private const val VALIDEZ_MS = 30_000L

    private var ultimoResultado: NetworkVerdict? = null
    private var ultimoMomento = 0L
    private var ultimaFirma = ""

    /** Redes que el usuario ha dicho que no son suyas. Solo mientras dure la sesión. */
    private val descartadas = mutableSetOf<String>()

    suspend fun resolve(context: Context, config: PanelConfig): NetworkVerdict {
        when (config.networkProfile) {
            NetworkProfile.HOME -> return NetworkVerdict(away = false)
            NetworkProfile.AWAY -> return NetworkVerdict(away = true)
            NetworkProfile.AUTO -> Unit
        }

        // Sin ninguna dirección alternativa configurada no hay nada que decidir.
        val testigos = direccionesTestigo(config)
        if (testigos.isEmpty()) return NetworkVerdict(away = false)

        val wifi = WifiNetwork.currentSsid(context)
        val firma = "$wifi|${redActual(context)}|${testigos.joinToString(",")}"
        val ahora = System.currentTimeMillis()

        val cache = ultimoResultado
        if (cache != null && firma == ultimaFirma && ahora - ultimoMomento < VALIDEZ_MS) {
            return cache
        }

        // Estando en una WiFi de las suyas no hace falta tocar la red para nada: el nombre
        // ya lo dice todo. Solo se sondea cuando la respuesta depende de ello.
        val enSuRed = wifi.isNotBlank() && wifi in config.homeSsids
        val responde = if (enSuRed) {
            true
        } else {
            testigos.any { Reachability.probe(it, timeoutMs = 1200) != null }
        }

        val veredicto = decidir(
            wifi = wifi,
            redesDeCasa = config.homeSsids,
            descartada = wifi.isNotBlank() && wifi in descartadas,
            respondeAlgo = responde
        )

        ultimoResultado = veredicto
        ultimoMomento = ahora
        ultimaFirma = firma
        return veredicto
    }

    /**
     * La decisión, sin red ni Android de por medio: entran datos y sale el veredicto.
     *
     * Está separada para poder probarla. Comprobar a mano el caso que importa —estar en una
     * WiFi ajena donde algo responde en la dirección de casa— exige irse físicamente a otra
     * casa; aquí son cuatro datos inventados. Ver `NetworkResolverTest`.
     *
     * @param wifi nombre de la WiFi actual, o vacío si no se puede leer o se va por cable.
     * @param descartada si el usuario ya dijo que esta WiFi no es suya.
     * @param respondeAlgo si alguna dirección de casa contesta.
     */
    fun decidir(
        wifi: String,
        redesDeCasa: List<String>,
        descartada: Boolean,
        respondeAlgo: Boolean
    ): NetworkVerdict {
        // Con el nombre a la vista y redes declaradas, manda el nombre.
        if (wifi.isNotBlank() && redesDeCasa.isNotEmpty()) {
            return when {
                wifi in redesDeCasa -> NetworkVerdict(away = false)
                descartada -> NetworkVerdict(away = true)
                // Solo se pregunta si de verdad hay algo respondiendo: en una WiFi
                // cualquiera donde no contesta nada, no hay nada que aclarar.
                respondeAlgo -> NetworkVerdict(away = true, ask = wifi)
                else -> NetworkVerdict(away = true)
            }
        }

        // Sin ninguna red declarada, por cable, o sin poder leer el nombre: lo de siempre,
        // decidir por quién contesta.
        //
        // Aquí **no** se aprende la red sola. Se hacía, y el resultado era que al pulsar
        // «Olvidar» la lista se quedaba vacía, esta rama la volvía a aprender en el acto y
        // el botón parecía roto. Y de fondo: que la red sea la tuya lo dice el usuario,
        // desde la tarjeta del final de la presentación o desde Ajustes › Seguridad.
        // Adivinarlo es lo que se quería evitar.
        return NetworkVerdict(away = !respondeAlgo)
    }

    /** El usuario ha dicho que esta red no es la suya: no se vuelve a preguntar por ella. */
    fun dismiss(fingerprint: String) {
        descartadas += fingerprint
        invalidate()
    }

    /** Fuerza que la próxima consulta vuelva a comprobarlo. */
    fun invalidate() {
        ultimoResultado = null
        ultimaFirma = ""
    }

    /**
     * Direcciones de casa que sirven para saber si estamos en la red local: las de los
     * servidores que tienen una dirección distinta para fuera, ya que en los demás
     * casos la respuesta no cambiaría nada.
     */
    private fun direccionesTestigo(config: PanelConfig): List<String> {
        val servidores = config.servers.filter {
            it.hostHome.isNotBlank() && it.hostAway.isNotBlank()
        }

        return servidores.mapNotNull { servidor ->
            config.allServices
                .firstOrNull { !it.isExample && it.serverId == servidor.id }
                ?.let { config.homeUrlOf(it) }
                ?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    /** Identificador aproximado de la red actual, para invalidar la caché al cambiar. */
    private fun redActual(context: Context): String = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val red = cm.activeNetwork ?: return "sin-red"
        val caps = cm.getNetworkCapabilities(red) ?: return "desconocida"

        buildString {
            append(red.toString())
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) append("-wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) append("-datos")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) append("-cable")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) append("-vpn")
        }
    }.getOrDefault("desconocida")
}
