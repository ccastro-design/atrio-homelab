package com.homelab.panel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Iconos guardados en el dispositivo: los que aporta el usuario y los que sirve cada
 * servicio.
 *
 * El icono del servicio se le pide al propio servidor del usuario, igual que hace un
 * navegador al mostrar una pestaña. No se consulta ningún repositorio de iconos ni
 * ningún servidor ajeno: la aplicación solo habla con las máquinas que el usuario ha
 * configurado.
 */
/** Qué hacer con un icono ya guardado. Ver [IconStore.decidirSobreElIcono]. */
enum class QueHacerConElIcono {
    /** Sirve: se pinta y no se molesta al servidor. */
    USARLO,

    /** Ya no vale porque el servicio cambió de dirección: se borra y se vuelve a pedir. */
    TIRARLO,

    /** No hay ninguno guardado: hay que pedírselo al servicio. */
    PEDIRLO
}

object IconStore {

    private const val TAG = "Panel"
    /**
     * Lado mayor con el que se guarda un icono.
     *
     * 192 y no 96: desde que la imagen llena la tarjeta entera —hasta 54 dp en tamaño
     * cómodo, que en una pantalla densa son más de 160 píxeles— a 96 se veía blanda.
     */
    private const val TAMANO = 192
    /** Por debajo de esto un icono ya guardado se considera de la época de los 96 px. */
    private const val TAMANO_MINIMO = 128
    /** Lado mayor de la imagen de fondo. De sobra para cualquier pantalla de móvil. */
    private const val TAMANO_FONDO = 1440
    private const val MAX_HTML = 64 * 1024
    /**
     * Tope de descarga de una imagen. Generoso a propósito: el logotipo que tenía el
     * autor en su Homer pesaba 684 kB y con un límite de 512 kB se descartaba sin decir
     * nada. Como después se escala a 96 px, el peso original da igual.
     */
    private const val MAX_IMAGEN = 4 * 1024 * 1024
    private const val TIMEOUT = 4_000
    private const val CADUCIDAD_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * Cuánto se recuerda que un servicio **no** tiene icono. Mucho menos que un icono bueno:
     * ese «no tiene» puede ser mentira —el servicio estaba apagado, la red falló, o la
     * aplicación lo pidió mal— y con treinta días se quedaba un mes sin icono por un tropiezo
     * de un segundo. Tres días son suficientes para no repetir la pregunta a cada rato.
     */
    private const val CADUCIDAD_SIN_ICONO_MS = 3L * 24 * 60 * 60 * 1000

    private fun carpetaUsuario(context: Context) =
        File(context.filesDir, "icons").apply { mkdirs() }

    private fun carpetaFavicons(context: Context) =
        File(context.filesDir, "favicons").apply { mkdirs() }

    /** Fichero de la imagen que aportó el usuario para un servicio. */
    fun userIcon(context: Context, name: String): File? {
        if (name.isBlank()) return null
        return File(carpetaUsuario(context), name).takeIf { it.exists() }
    }

    /**
     * Copia al almacenamiento de la aplicación la imagen que el usuario ha elegido de
     * su galería, y devuelve el nombre con el que se guardó.
     */
    fun saveUserIcon(context: Context, uri: Uri, serviceId: String): String? =
        guardarImagen(context, uri, serviceId, TAMANO)

    /**
     * Igual, pero para la imagen de fondo del panel.
     *
     * Se guarda mucho más grande que un icono: a 96 píxeles, estirada a pantalla completa,
     * lo que se ve es una mancha de colores.
     */
    fun saveBackground(context: Context, uri: Uri): String? =
        guardarImagen(context, uri, "fondo", TAMANO_FONDO)

    private fun guardarImagen(context: Context, uri: Uri, id: String, tamano: Int): String? =
        runCatching {
            val nombre = "$id-${System.currentTimeMillis()}.png"
            val destino = File(carpetaUsuario(context), nombre)

            val original = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return null

            escalar(original, tamano).let { escalado ->
                destino.outputStream().use { escalado.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }

            nombre
        }.onFailure { Log.w(TAG, "No se pudo guardar la imagen elegida", it) }.getOrNull()

    /**
     * Descarga una imagen y la guarda como icono de un servicio.
     *
     * Se usa al importar un panel: los iconos que el usuario tenía puestos en él son
     * ficheros de su propio servidor, y traerlos es la única forma de que su panel se vea
     * igual que antes.
     */
    suspend fun downloadUserIcon(
        context: Context,
        url: String,
        serviceId: String
    ): String? = withContext(Dispatchers.IO) {
        val bitmap = descargarBitmap(url) ?: return@withContext null
        if (bitmap.width < 8 || bitmap.height < 8) return@withContext null

        runCatching {
            val nombre = "$serviceId-${System.currentTimeMillis()}.png"
            val destino = File(carpetaUsuario(context), nombre)
            destino.outputStream().use { escalar(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it) }
            nombre
        }.onFailure { Log.w(TAG, "No se pudo guardar el icono de $url", it) }.getOrNull()
    }

    fun deleteUserIcon(context: Context, name: String) {
        if (name.isBlank()) return
        runCatching { File(carpetaUsuario(context), name).delete() }
    }

    /**
     * Borra la imagen propia de un servicio que se elimina.
     *
     * Al cambiar la imagen de un servicio la anterior sí se borraba, pero al borrar el
     * servicio entero se quedaba en el móvil para siempre. Se comprueba antes que no la
     * esté usando otro: normalmente cada servicio tiene la suya, con su identificador en
     * el nombre, pero un panel importado puede traer la misma para varios.
     */
    fun deleteServiceIcon(context: Context, config: PanelConfig, service: Service) {
        if (service.iconFile.isBlank()) return

        val laUsaOtro = config.allServices.any {
            it.id != service.id && it.iconFile == service.iconFile
        }
        if (!laUsaOtro) deleteUserIcon(context, service.iconFile)
    }

    /**
     * Fichero del icono ya descargado de un servicio, si lo hay.
     *
     * Se guarda con el **identificador del servicio**, no con su dirección. Un servicio
     * tiene dos —la de casa y la de la VPN—, así que atándolo a la dirección el icono
     * desaparecía al salir de casa: para la aplicación pasaba a ser otro servicio, y había
     * que volver a pedírselo por el túnel. Es el mismo icono, y se descarga una vez.
     */
    fun cachedFavicon(context: Context, serviceId: String, origen: String?): File? {
        val destino = ficheroDeFavicon(context, serviceId)

        return when (
            decidirSobreElIcono(
                existe = destino.exists(),
                origenAnotado = origenAnotado(context, serviceId),
                origenActual = origen
            )
        ) {
            QueHacerConElIcono.TIRARLO -> {
                runCatching { destino.delete() }
                null
            }

            QueHacerConElIcono.USARLO -> {
                // Un icono de antes de que esto existiera no lleva dirección apuntada. Se
                // le pone la de ahora: sin esto se quedaría para siempre sin nada con lo
                // que comparar y **no se enteraría nunca de un cambio**.
                if (origen != null && origenAnotado(context, serviceId) == null) {
                    anotarOrigen(context, serviceId, origen)
                }
                destino
            }

            // Antes se guardaban con el nombre de la dirección: si está el de entonces, se
            // hereda en vez de volver a molestar al servidor.
            QueHacerConElIcono.PEDIRLO -> origen?.let {
                heredarIconoViejo(context, it, destino)?.also { _ ->
                    anotarOrigen(context, serviceId, it)
                }
            }
        }
    }

    private fun ficheroDeFavicon(context: Context, serviceId: String): File =
        File(carpetaFavicons(context), "${claveDeServicio(serviceId)}.png")

    /** Nombre de fichero seguro a partir del identificador del servicio. */
    private fun claveDeServicio(serviceId: String): String =
        serviceId.replace(Regex("[^A-Za-z0-9.-]"), "_").take(80).ifBlank { "sin-id" }

    /**
     * Qué hacer con el icono que hay guardado, mirando solo lo que se sabe de él.
     *
     * Está aparte, sin disco ni Android de por medio, porque **aquí ya se colaron dos
     * fallos** y los dos daban la misma cara —el servicio sin icono— por motivos opuestos:
     * primero atarlo a la dirección, que lo perdía al salir de casa; y después tirarlo
     * cuando quien lo pedía no sabía las direcciones, que lo borraba con solo abrir la
     * ficha del servicio. Ver `IconStoreTest`.
     */
    fun decidirSobreElIcono(
        existe: Boolean,
        /** Las direcciones con las que se guardó, o null si nunca se apuntaron. */
        origenAnotado: String?,
        /** Las de ahora, o **null si quien lo pide no las conoce**. */
        origenActual: String?
    ): QueHacerConElIcono = when {
        // Sin saber las direcciones no se juzga: no se puede decidir que algo ha caducado
        // comparándolo con lo que no se sabe.
        origenActual == null -> if (existe) QueHacerConElIcono.USARLO else QueHacerConElIcono.PEDIRLO

        // Se mudó de máquina o de puerto: lo guardado puede ser de otro programa.
        origenAnotado != null && origenAnotado != origenActual -> QueHacerConElIcono.TIRARLO

        existe -> QueHacerConElIcono.USARLO
        else -> QueHacerConElIcono.PEDIRLO
    }

    /**
     * Dónde se apunta con qué direcciones se consiguió el icono.
     *
     * El icono se guarda por servicio para que no se pierda al salir de casa, pero eso solo
     * no basta: si el servicio cambia de máquina o de puerto, lo guardado deja de valer y
     * **esperar a que caduque serían treinta días** con el icono de otro programa. Anotando
     * la dirección, un cambio se nota en el acto.
     */
    private fun ficheroDeOrigen(context: Context, serviceId: String): File =
        File(carpetaFavicons(context), "${claveDeServicio(serviceId)}.origen")

    /**
     * True si el icono guardado se consiguió con estas mismas direcciones.
     *
     * Sin nada anotado se da por bueno: son los iconos de antes de que esto existiera, y
     * tirarlos obligaría a pedírselos otra vez a todos los servicios sin motivo.
     */
    private fun origenSigueValiendo(context: Context, serviceId: String, origen: String): Boolean {
        val anotado = origenAnotado(context, serviceId)
        return anotado == null || anotado == origen
    }

    /** La dirección apuntada para este servicio, o null si nunca se apuntó ninguna. */
    private fun origenAnotado(context: Context, serviceId: String): String? = runCatching {
        ficheroDeOrigen(context, serviceId).takeIf { it.exists() }?.readText()
    }.getOrNull()

    private fun anotarOrigen(context: Context, serviceId: String, origen: String) {
        runCatching { ficheroDeOrigen(context, serviceId).writeText(origen) }
    }

    /**
     * Recupera el icono que se guardó con el nombre de la dirección, de antes de atarlos al
     * servicio.
     *
     * **Solo hereda los que tienen algo dentro.** Un fichero vacío es la marca de «este
     * servicio no da icono», y esa marca puede ser de la otra red: qBittorrent sirve el
     * suyo en casa y no por el túnel, así que heredarla dejaría al servicio sin icono en
     * los dos sitios. Sin marca se vuelve a intentar, y en cuanto salga bien una vez queda
     * guardado para las dos direcciones.
     */
    private fun heredarIconoViejo(context: Context, origen: String, destino: File): File? {
        // El origen lleva las dos direcciones del servicio; el icono viejo puede estar
        // guardado con cualquiera de ellas, según dónde estuviera el usuario aquel día.
        val viejo = origen.split('|')
            .mapNotNull { clave(it) }
            .map { File(carpetaFavicons(context), "$it.png") }
            .firstOrNull { it.exists() && it.length() > 0L }
            ?: return null

        return if (runCatching { viejo.renameTo(destino) }.getOrDefault(false)) destino else viejo
    }

    /**
     * Se asegura de tener el icono del servicio, descargándolo si hace falta.
     * Devuelve el fichero, o null si el servicio no ofrece ninguno utilizable.
     */
    suspend fun ensureFavicon(
        context: Context,
        /** Identificador del servicio: es lo que da nombre al fichero. */
        serviceId: String,
        url: String,
        /**
         * Las direcciones del servicio. Si cambian, lo guardado deja de valer. `null`
         * cuando quien pide el icono no las conoce: entonces no se compara ni se anota
         * nada, para no tirar por error un icono que estaba bien.
         */
        origen: String?,
        /** Cabecera `Authorization` del servicio, si tiene credenciales guardadas. */
        auth: String? = null
    ): File? = withContext(Dispatchers.IO) {
        if (clave(url) == null) return@withContext null

        val destino = ficheroDeFavicon(context, serviceId)

        if (origen == null || origenSigueValiendo(context, serviceId, origen)) {
            if (origen != null) heredarIconoViejo(context, origen, destino)
        } else {
            // El servicio ha cambiado de dirección: lo guardado era de otra máquina o de
            // otro puerto, y puede no tener nada que ver con lo que hay ahora.
            runCatching { destino.delete() }
        }

        // Ya descargado y reciente: no se vuelve a molestar al servidor. Salvo que sea uno
        // de los pequeños que se guardaron antes, que se vuelve a pedir para que la
        // tarjeta no lo enseñe ampliado y borroso.
        //
        // Un fichero vacío recuerda que este servicio no tiene icono, para no reintentarlo
        // en cada arranque. Esa marca vale mucho menos tiempo que un icono bueno: ver
        // [CADUCIDAD_SIN_ICONO_MS].
        val edad = System.currentTimeMillis() - destino.lastModified()
        val vacio = destino.exists() && destino.length() == 0L

        if (destino.exists() && (
                (vacio && edad < CADUCIDAD_SIN_ICONO_MS) ||
                    (!vacio && edad < CADUCIDAD_MS && ladoMayor(destino) >= TAMANO_MINIMO)
                )
        ) {
            return@withContext destino.takeIf { it.length() > 0 }
        }

        val bitmap = descargarIcono(url, auth)

        // Se anota con qué direcciones se preguntó, haya salido bien o mal: también la
        // marca de «no tiene icono» deja de valer si el servicio se muda.
        if (origen != null) anotarOrigen(context, serviceId, origen)

        if (bitmap == null) {
            runCatching { destino.writeBytes(ByteArray(0)) }
            return@withContext null
        }

        runCatching {
            destino.outputStream().use { escalar(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it) }
            destino
        }.getOrNull()
    }

    /**
     * Busca el icono por el mismo camino que un navegador: primero lo que declare el
     * HTML, y si no dice nada, las rutas habituales.
     *
     * **Se intenta primero sin credenciales y solo después con ellas.** Parece al revés de
     * lo lógico y no lo es: mandar una cabecera `Authorization` a un servicio que no usa
     * autenticación básica puede **cerrar** una puerta que estaba abierta. qBittorrent es
     * el caso: sirve su icono a cualquiera sin pedir nada, pero al recibir un `Authorization`
     * que no entiende lo toma por un acceso con credenciales malas y responde 401 a todo,
     * incluido el icono. Transmission es el contrario: sin credenciales no da ni el
     * `favicon.ico`. Probando en este orden funcionan los dos.
     */
    private fun descargarIcono(url: String, auth: String?): Bitmap? {
        val base = runCatching { URI(url) }.getOrNull() ?: return null
        val origen = runCatching {
            val puerto = if (base.port > 0) ":${base.port}" else ""
            "${base.scheme}://${base.host}$puerto"
        }.getOrNull() ?: return null

        // Sin credenciales, que es lo que funciona en la mayoría.
        buscar(origen, null)?.let { return it }

        // Y con ellas, para los que no sueltan nada sin identificarse.
        return if (auth == null) null else buscar(origen, auth)
    }

    /** Un intento completo de encontrar el icono, con o sin credenciales. */
    private fun buscar(origen: String, auth: String?): Bitmap? {
        val candidatos = buildList {
            declaradoEnHtml(origen, auth)?.let { add(it) }
            add("$origen/apple-touch-icon.png")
            add("$origen/apple-touch-icon-precomposed.png")
            add("$origen/favicon.png")
            add("$origen/favicon.ico")
        }

        return candidatos.firstNotNullOfOrNull { candidato ->
            descargarBitmap(candidato, auth)?.takeIf { it.width >= 16 && it.height >= 16 }
        }
    }

    /**
     * Lee el principio del HTML y saca el href del primer `<link rel="...icon...">`.
     *
     * La dirección del icono se resuelve contra **la página que se acabó leyendo**, no
     * contra la raíz del servidor. Transmission lo dejó claro: redirige de `/` a
     * `/transmission/web/` y declara su icono como `./images/favicon.ico`, así que
     * pegándolo a la raíz salía `/./images/favicon.ico`, que no existe.
     */
    private fun declaradoEnHtml(origen: String, auth: String?): String? = runCatching {
        // La definitiva tras las redirecciones, que es contra la que hay que resolver.
        var base = origen

        val html = abrir(origen, auth)?.use { conexion ->
            val texto = conexion.inputStream.use { entrada ->
                // En bucle: una sola lectura devuelve solo lo que haya llegado ya, y la
                // etiqueta del icono se perdía. Se corta al cerrar la cabecera.
                val acumulado = StringBuilder()
                val buffer = ByteArray(4096)

                while (acumulado.length < MAX_HTML) {
                    val leidos = entrada.read(buffer)
                    if (leidos <= 0) break
                    acumulado.append(String(buffer, 0, leidos))
                    if (acumulado.contains("</head", ignoreCase = true)) break
                }

                acumulado.toString().takeIf { it.isNotEmpty() }
            }

            // Después de leer, no antes: hasta que no se consume la respuesta la conexión
            // ni siquiera se ha hecho, y `url` sigue siendo la que se pidió, sin las
            // redirecciones. Ahí estaba el fallo con Transmission.
            base = conexion.url?.toString() ?: origen
            texto
        } ?: return null

        val etiqueta = Regex(
            """<link[^>]+rel\s*=\s*["'][^"']*icon[^"']*["'][^>]*>""",
            RegexOption.IGNORE_CASE
        ).find(html)?.value ?: return null

        val href = Regex("""href\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(etiqueta)?.groupValues?.get(1)?.trim() ?: return null

        resolverIcono(base, href)
    }.getOrNull()

    /**
     * La dirección absoluta del icono, a partir de la página donde se declaró.
     *
     * **La base se normaliza para que siempre tenga ruta, aunque sea solo `/`.** No es un
     * detalle: `java.net.URI.resolve` de Android, con una base sin ruta como
     * `http://192.168.1.254:8085`, pega el relativo sin separador y devuelve
     * `http://192.168.1.254:8085images/qbittorrent32.png`. El JDK del ordenador hace lo
     * correcto con la misma llamada, así que el fallo solo aparece en el móvil y una prueba
     * de escritorio lo da por bueno. Así se cayó el icono de qBittorrent, que declara el
     * suyo como `images/qbittorrent32.png`.
     */
    fun resolverIcono(base: String, href: String): String? = runCatching {
        val uri = URI(base)
        val conRuta = if (uri.path.isNullOrEmpty()) URI("$base/") else uri

        conRuta.resolve(href).toString()
    }.getOrNull()

    private fun descargarBitmap(url: String, auth: String? = null): Bitmap? = runCatching {
        abrir(url, auth)?.use { conexion ->
            if (conexion.responseCode !in 200..299) return null

            // Muchos paneles sirven su página para cualquier ruta en vez de devolver 404,
            // así que «ha respondido 200» no significa que haya un icono ahí.
            if (conexion.contentType?.startsWith("text/html", ignoreCase = true) == true) {
                return null
            }

            val bytes = conexion.inputStream.use { entrada ->
                val salida = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val leidos = entrada.read(buffer)
                    if (leidos <= 0) break
                    total += leidos
                    if (total > MAX_IMAGEN) return null
                    salida.write(buffer, 0, leidos)
                }
                salida.toByteArray()
            }

            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }.getOrNull()

    /**
     * Abre la conexión con tiempos de espera cortos: un servicio caído no debe esperar.
     *
     * Con credenciales guardadas se mandan desde la primera petición. Sin ellas, los
     * servicios que piden contraseña —Transmission, qBittorrent, SABnzbd— responden 401
     * hasta al `favicon.ico`, así que se quedaban sin icono para siempre y no había forma
     * de saber por qué.
     */
    private fun abrir(url: String, auth: String? = null): HttpURLConnection? = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT
            readTimeout = TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*,text/html;q=0.9,*/*;q=0.5")
            auth?.let { setRequestProperty("Authorization", it) }
        }
    }.getOrNull()

    /**
     * Cabecera `Authorization` de un servicio, o null si no tiene contraseña guardada.
     *
     * Se usa lo que haya en [SecureStore] aunque el autocompletado esté apagado: son las
     * credenciales de esa máquina, y pedirle su propio icono es justo para lo que valen.
     */
    fun basicAuth(context: Context, serviceId: String): String? {
        if (serviceId.isBlank()) return null

        val contrasena = SecureStore.read(context, SecureStore.servicePasswordKey(serviceId))
            ?.takeIf { it.isNotEmpty() } ?: return null
        val usuario = SecureStore.read(context, SecureStore.serviceUserKey(serviceId)).orEmpty()

        val credenciales = "$usuario:$contrasena".toByteArray(Charsets.UTF_8)
        return "Basic " + android.util.Base64.encodeToString(
            credenciales,
            android.util.Base64.NO_WRAP
        )
    }

    /** Lado mayor de una imagen guardada, sin llegar a cargarla en memoria. */
    private fun ladoMayor(fichero: File): Int = runCatching {
        val opciones = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(fichero.absolutePath, opciones)
        maxOf(opciones.outWidth, opciones.outHeight)
    }.getOrDefault(0)

    private fun escalar(original: Bitmap, tamano: Int = TAMANO): Bitmap {
        if (original.width <= tamano && original.height <= tamano) return original

        val escala = tamano.toFloat() / maxOf(original.width, original.height)
        return Bitmap.createScaledBitmap(
            original,
            (original.width * escala).toInt().coerceAtLeast(1),
            (original.height * escala).toInt().coerceAtLeast(1),
            true
        )
    }

    /** Nombre de fichero seguro a partir del equipo y el puerto de la dirección. */
    private fun clave(url: String): String? = runCatching {
        val uri = URI(url.trim())
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val puerto = if (uri.port > 0) uri.port else 0
        "${host.replace(Regex("[^A-Za-z0-9.-]"), "_")}_$puerto"
    }.getOrNull()

    /** Usa Closeable con HttpURLConnection, que no lo implementa. */
    private inline fun <T> HttpURLConnection.use(bloque: (HttpURLConnection) -> T): T =
        try {
            bloque(this)
        } finally {
            disconnect()
        }
}
