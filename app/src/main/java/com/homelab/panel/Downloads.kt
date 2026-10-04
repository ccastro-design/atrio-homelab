package com.homelab.panel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entrega de enlaces a los servicios de descarga del usuario.
 *
 * La aplicación no descarga nada por su cuenta ni busca contenido en ninguna parte: es
 * un mando a distancia de programas que el usuario ya tiene funcionando en su servidor.
 */
object Links {

    fun detect(text: String): LinkKind? {
        val t = text.trim()

        // La extensión se mira en la ruta, no en la dirección entera: muchos sitios
        // sirven el fichero con parámetros detrás («…/x.torrent?descarga=1»).
        val ruta = t.substringBefore('?').substringBefore('#')

        return when {
            t.startsWith("ed2k://", true) -> LinkKind.ED2K
            t.startsWith("magnet:", true) -> LinkKind.MAGNET
            ruta.endsWith(".torrent", true) -> LinkKind.TORRENT
            ruta.endsWith(".nzb", true) -> LinkKind.NZB
            // Una dirección web a secas no es un enlace de descarga: es una página.
            else -> null
        }
    }

    /**
     * Todos los enlaces reconocibles de un texto, sin repetidos: las webs de enlaces enseñan
     * un cuadro con varios, uno por línea. Un enlace nunca lleva espacios, así que separar
     * por espacios y saltos de línea vale para todos los tipos.
     */
    fun extractAll(text: String?): List<String> {
        if (text.isNullOrBlank()) return emptyList()

        return text.split(Regex("\\s+"))
            .map { it.trim() }
            .filter { detect(it) != null }
            .distinct()
    }

    /** Saca el primer enlace reconocible de un texto compartido. */
    fun extract(text: String?): String? {
        if (text.isNullOrBlank()) return null

        val directo = text.trim()
        if (detect(directo) != null) return directo

        // Al compartir desde un navegador suele llegar «título http://…»
        return directo.split(Regex("\\s+")).firstOrNull { detect(it) != null }
    }
}

/**
 * Un fichero de descarga que ha armado la propia web dentro del móvil.
 *
 * Binsearch genera el `.nzb` con JavaScript y lo ofrece como un `blob:`, una dirección que
 * **solo existe dentro de esa pestaña**: se lee el contenido ahí mismo y se sube entero.
 */
data class DownloadFile(val name: String, val content: String, val kind: LinkKind)

sealed interface SendResult {
    data class Ok(val targetName: String) : SendResult
    data class Failed(val message: String) : SendResult
}

/** Dónde está un programa de descargas y con qué se entra en él. */
data class DatosDeEntrega(
    /** Dirección del servicio, ya resuelta según se esté en casa o fuera. */
    val base: String,
    val usuario: String,
    /** La contraseña, o la clave API en SABnzbd. */
    val clave: String
)

/**
 * Lo que pasó al entregar, todavía sin palabras: lo que se le cuenta al usuario se decide en
 * un solo sitio, [LinkSender.textoDe]. Los fallos de red no están aquí: los conectores los
 * dejan salir como excepción, y también se traducen en un solo sitio.
 */
sealed interface Entrega {
    data object Hecha : Entrega

    /** El programa ya lo tenía. No es un fallo, pero decir «enviado» haría creer que hay algo nuevo. */
    data object YaEstaba : Entrega

    data object ContrasenaMala : Entrega
    data object FaltaContrasena : Entrega
    data object FaltaClaveApi : Entrega
    data object ClaveApiMala : Entrega

    /** Transmission: el móvil no está en su `rpc-whitelist`. */
    data object FueraDeLaListaBlanca : Entrega

    /** SABnzbd: el nombre con que se le llama no está en su `host_whitelist`. */
    data object NombreDeEquipoNoPermitido : Entrega

    data class CodigoHttp(val codigo: Int) : Entrega
    data object RespuestaIninteligible : Entrega

    /** El programa dijo que no. [motivo] es el que dio él, o null si no dio ninguno. */
    data class Rechazada(val motivo: String?) : Entrega

    /** qBittorrent contestó bien pero no añadió nada. */
    data object NoAceptada : Entrega

    data object NoSabeSubirFicheros : Entrega
}

/**
 * Un programa de descargas: sabe hablar su protocolo y nada más.
 *
 * Deja salir los fallos de red tal cual. Qué decir cuando no se llega, o cuando el servidor
 * cuelga, se decide en [LinkSender] para los cuatro.
 */
interface Conector {

    suspend fun entregar(destino: DatosDeEntrega, enlace: String): Entrega

    /**
     * Sube un fichero que la web armó en el móvil, en vez de un enlace.
     *
     * De momento solo SABnzbd, que es donde está probado: Transmission y qBittorrent admiten
     * algo parecido, pero prometerlo sin haberlo comprobado sería fiarse de la documentación.
     */
    suspend fun subir(destino: DatosDeEntrega, fichero: DownloadFile): Entrega =
        Entrega.NoSabeSubirFicheros
}

object LinkSender {

    private const val TAG = "Panel"

    /** @param baseUrl dirección del servicio de descarga, ya resuelta según el perfil. */
    suspend fun send(
        context: Context,
        target: DownloadTarget,
        baseUrl: String,
        link: String
    ): SendResult = entregarCon(
        context, target.targetKind, target.name, baseUrl, target.username, clave(context, target), link
    )

    /** Entrega un fichero que la web armó en el móvil. Ver [Conector.subir]. */
    suspend fun sendFile(
        context: Context,
        target: DownloadTarget,
        baseUrl: String,
        file: DownloadFile
    ): SendResult = subirCon(
        context, target.targetKind, target.name, baseUrl, target.username, clave(context, target), file
    )

    /**
     * La clave API de SABnzbd se guarda donde las contraseñas: cifrada y fuera de la copia de
     * seguridad, que es lo que toca para un secreto.
     */
    private fun clave(context: Context, target: DownloadTarget): String =
        SecureStore.read(context, SecureStore.targetKey(target.id)).orEmpty()

    /** Lo mismo que [send], con la clave ya leída: así se puede probar sin el almacén cifrado. */
    internal suspend fun entregarCon(
        context: Context,
        tipo: TargetKind,
        nombre: String,
        base: String,
        usuario: String,
        clave: String,
        enlace: String
    ): SendResult {
        if (base.isBlank()) return SendResult.Failed(context.getString(R.string.send_no_address, nombre))

        val destino = DatosDeEntrega(base, usuario, clave)
        return intentar(context, nombre, "Fallo entregando el enlace a «$nombre»") {
            conectorDe(tipo).entregar(destino, enlace)
        }
    }

    /** Lo mismo que [sendFile], con la clave ya leída. */
    internal suspend fun subirCon(
        context: Context,
        tipo: TargetKind,
        nombre: String,
        base: String,
        usuario: String,
        clave: String,
        fichero: DownloadFile
    ): SendResult {
        if (base.isBlank()) return SendResult.Failed(context.getString(R.string.send_no_address, nombre))

        val destino = DatosDeEntrega(base, usuario, clave)
        return intentar(context, nombre, "Fallo subiendo el fichero a «$nombre»") {
            conectorDe(tipo).subir(destino, fichero)
        }
    }

    /**
     * Uno por programa. Sin rama «los demás»: si algún día hay un quinto, el compilador obliga
     * a decir aquí quién lo atiende.
     */
    private fun conectorDe(tipo: TargetKind): Conector = when (tipo) {
        TargetKind.AMULE -> AmuleClient
        TargetKind.QBITTORRENT -> QbittorrentClient
        TargetKind.TRANSMISSION -> TransmissionClient
        TargetKind.SABNZBD -> SabnzbdClient
    }

    /**
     * Hace la entrega fuera del hilo de la pantalla y la convierte en lo que ve el usuario.
     * Los fallos de red se traducen aquí, en un solo sitio para los cuatro programas.
     */
    private suspend fun intentar(
        context: Context,
        nombre: String,
        siFalla: String,
        entrega: suspend () -> Entrega
    ): SendResult = withContext(Dispatchers.IO) {
        try {
            val hecho = entrega()
            if (hecho == Entrega.Hecha) {
                Registro.info(TAG, "Enlace entregado a «$nombre»")
            } else {
                Registro.traza(TAG) { "«$nombre» contesta: $hecho" }
            }
            textoDe(hecho, nombre)?.let { SendResult.Failed(it.en(context)) } ?: SendResult.Ok(nombre)
        } catch (e: ServidorColgo) {
            // Sí se contactó: no es la red, es el programa cerrándose. Ver [Reintentos].
            Registro.aviso(TAG, "«$nombre» colgó sin contestar, también al repetir", e)
            SendResult.Failed(context.getString(R.string.error_hung_up, nombre))
        } catch (e: Exception) {
            Registro.aviso(TAG, siFalla, e)
            SendResult.Failed(
                context.getString(R.string.send_unreachable, nombre, e.message ?: e.javaClass.simpleName)
            )
        }
    }

    /** Un texto de la aplicación con sus datos. Un dato puede ser a su vez otro texto. */
    internal data class Texto(val id: Int, val datos: List<Any> = emptyList()) {
        fun en(context: Context): String =
            if (datos.isEmpty()) {
                context.getString(id)
            } else {
                context.getString(id, *datos.map { if (it is Texto) it.en(context) else it }.toTypedArray())
            }
    }

    /**
     * Lo que se le dice al usuario, o null si salió bien. Aquí están los avisos de los cuatro
     * programas.
     */
    internal fun textoDe(entrega: Entrega, nombre: String): Texto? = when (entrega) {
        Entrega.Hecha -> null
        Entrega.YaEstaba -> Texto(R.string.send_duplicate, listOf(nombre))
        Entrega.ContrasenaMala -> Texto(R.string.send_bad_password, listOf(nombre))
        Entrega.FaltaContrasena -> Texto(R.string.send_no_password, listOf(nombre))
        Entrega.FaltaClaveApi -> Texto(R.string.send_no_api_key, listOf(nombre))
        Entrega.ClaveApiMala -> Texto(R.string.send_bad_api_key, listOf(nombre))
        Entrega.FueraDeLaListaBlanca -> Texto(R.string.send_not_whitelisted, listOf(nombre))
        Entrega.NombreDeEquipoNoPermitido -> Texto(R.string.send_host_not_allowed, listOf(nombre))
        is Entrega.CodigoHttp -> Texto(R.string.send_http_error, listOf(nombre, entrega.codigo))
        Entrega.RespuestaIninteligible -> Texto(R.string.send_bad_answer, listOf(nombre))
        is Entrega.Rechazada -> Texto(
            R.string.send_refused,
            listOf(nombre, entrega.motivo ?: Texto(R.string.send_reason_unknown))
        )
        Entrega.NoAceptada -> Texto(R.string.send_not_accepted, listOf(nombre))
        Entrega.NoSabeSubirFicheros -> Texto(R.string.send_file_not_supported, listOf(nombre))
    }
}
