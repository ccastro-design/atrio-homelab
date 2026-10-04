package com.homelab.panel

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Registro de lo que pasa por dentro, en un fichero propio.
 *
 * El del sistema no sirve para un fallo de hace horas: Android lo recicla, y el 04/10/2026,
 * cuando se perdió un enlace de cuatro, del de la aplicación solo quedaba la última hora.
 *
 * Solo escribe con [BuildConfig.REGISTRO], encendido en la versión de prueba y en la de
 * desarrollo. En la publicada [traza] no deja ni la llamada, y [info], [aviso] y [error] van al
 * registro del sistema exactamente como antes.
 *
 * ⚠ Nunca se apunta una contraseña, una cookie ni una clave: todo lo que llega al fichero pasa
 * por [sinSecretos], las excepciones por [motivo] y los enlaces se resumen con [enlace]. Los
 * errores de consola de las páginas no entran: los decide la web, llegan a ráfagas y citan
 * direcciones enteras.
 *
 * Se lee por cable, sin hacer depurable la aplicación; la carpeta entera, que al llenarse el
 * fichero lo anterior pasa a `registro-anterior.txt`:
 *
 *     adb pull /sdcard/Android/data/io.github.ccastrodesign.atrio.min/files/
 *
 * Un cierre inesperado solo se lee con el `mapping.txt` de esa misma compilación.
 */
object Registro {

    /** Al pasar de aquí el fichero pasa a ser el anterior: en total unos 2 MB, días de uso. */
    private const val TOPE_BYTES = 1_000_000L

    /** Más no aporta nada, y acota lo que tarda el tachado. */
    private const val TOPE_TEXTO = 2_000

    private const val ACTUAL = "registro.txt"
    private const val ANTERIOR = "registro-anterior.txt"

    @Volatile
    private var carpeta: File? = null

    /** Abierto mientras viva el proceso: si muere, lo escrito ya está en manos del sistema. */
    private var salida: FileOutputStream? = null
    private var escritos = 0L

    /** Fallos cuyo recorrido entero ya se apuntó: a partir del segundo basta el motivo. */
    private val recorridos = mutableSetOf<String>()

    fun iniciar(context: Context) {
        if (!BuildConfig.REGISTRO || carpeta != null) return

        // Hasta Android 10, otras aplicaciones con permiso de almacenamiento pueden leer la
        // carpeta externa: ahí se queda en la privada, aunque no se pueda sacar por cable.
        val externa = if (Build.VERSION.SDK_INT >= 30) context.getExternalFilesDir(null) else null
        carpeta = externa ?: context.filesDir

        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, e ->
            // Pase lo que pase al apuntarlo, Android tiene que poder cerrar la aplicación.
            try {
                apuntar("E", "Cierre", "Error sin atender en el hilo «${hilo.name}»", e, entero = true)
            } catch (ignorado: Throwable) {
            } finally {
                anterior?.uncaughtException(hilo, e)
            }
        }

        traza("Ciclo") { "Arranca Atrio ${BuildConfig.VERSION_NAME} (proceso ${android.os.Process.myPid()})" }
    }

    /** El detalle, solo para la versión de prueba. En la publicada no queda ni la llamada. */
    inline fun traza(area: String, texto: () -> String) {
        if (BuildConfig.REGISTRO) {
            val linea = sinSecretos(texto())
            Log.d(area, linea)
            apuntar("D", area, linea, null)
        }
    }

    fun info(area: String, texto: String) {
        Log.i(area, texto)
        apuntar("I", area, texto, null)
    }

    fun aviso(area: String, texto: String, e: Throwable? = null) {
        Log.w(area, texto, e)
        apuntar("W", area, texto, e)
    }

    fun error(area: String, texto: String, e: Throwable? = null) {
        Log.e(area, texto, e)
        apuntar("E", area, texto, e, entero = true)
    }

    /** La línea se arma fuera del cerrojo: dentro solo se escribe. */
    @PublishedApi
    internal fun apuntar(nivel: String, area: String, texto: String, e: Throwable?, entero: Boolean = false) {
        if (!BuildConfig.REGISTRO || carpeta == null) return

        runCatching {
            val linea = buildString {
                append(OffsetDateTime.now().format(Patrones.HORA)).append(' ').append(nivel).append(' ')
                append(area).append(": ").append(sinSecretos(texto)).append('\n')
                if (e != null) {
                    append("    ").append(motivo(e)).append('\n')
                    if (entero && primeraVez("$area ${e.javaClass.name}")) append(recorrido(e))
                }
            }
            escribir(linea)
        }
    }

    @Synchronized
    private fun primeraVez(fallo: String): Boolean = recorridos.add(fallo)

    @Synchronized
    private fun escribir(linea: String) {
        val dir = carpeta ?: return

        try {
            var flujo = salida
            if (flujo == null) {
                val fichero = File(dir, ACTUAL)
                escritos = fichero.length()
                flujo = FileOutputStream(fichero, true)
                salida = flujo
            }

            if (escritos > TOPE_BYTES) {
                flujo.close()
                val anterior = File(dir, ANTERIOR)
                anterior.delete()
                File(dir, ACTUAL).renameTo(anterior)
                flujo = FileOutputStream(File(dir, ACTUAL), true)
                salida = flujo
                escritos = 0
            }

            val bytes = linea.toByteArray()
            flujo.write(bytes)
            escritos += bytes.size
        } catch (e: IOException) {
            // Se vuelve a abrir en la línea siguiente.
            runCatching { salida?.close() }
            salida = null
        }
    }

    /**
     * La excepción y sus causas en una línea: «ServidorColgo ← SocketException: Connection
     * reset». Sin secretos: un 404 de `HttpURLConnection` lleva la dirección en el mensaje.
     */
    fun motivo(e: Throwable): String {
        val cadena = generateSequence(e) { actual -> actual.cause?.takeIf { it !== actual } }.take(6)

        return sinSecretos(
            cadena.joinToString(" ← ") { t ->
                // Quien envuelve otra excepción repite su texto como mensaje: sobra.
                val mensaje = t.message?.takeIf { it != t.cause?.toString() }?.let(::recortarMensaje)
                t.javaClass.simpleName + (mensaje?.let { ": $it" } ?: "")
            }
        )
    }

    /**
     * Los lectores de JSON copian en el mensaje el texto que no entendieron: entero el de
     * Android («… at character 61 of {…}») y un trozo kotlinx («JSON input: …»). Un panel
     * importado puede llevar claves, así que se corta ahí.
     */
    private fun recortarMensaje(mensaje: String): String {
        val util = mensaje.replace(Patrones.ENTRADA_ORG_JSON, "$1").substringBefore("JSON input:").trim()
        return if (util.length > 300) util.take(300) + "…" else util
    }

    /** Por dónde pasó, sin los mensajes, que ya van recortados en [motivo]. */
    private fun recorrido(e: Throwable): String = buildString {
        generateSequence(e) { actual -> actual.cause?.takeIf { it !== actual } }.take(6).forEachIndexed { i, t ->
            if (i > 0) append("    causado por ").append(t.javaClass.name).append('\n')
            t.stackTrace.take(25).forEach { append("        at ").append(it).append('\n') }
        }
    }

    /**
     * Solo esquema, equipo y puerto: de una pestaña no se apunta qué página se visita.
     * `rawAuthority` y no `host`, que deja en blanco un nombre con «_» como `mi_nas`.
     */
    fun origen(url: String): String = runCatching {
        val uri = java.net.URI(enderezarUrl(url))
        val maquina = uri.rawAuthority?.substringAfterLast('@') ?: return@runCatching "(sin servidor)"
        "${uri.scheme}://$maquina"
    }.getOrDefault("(dirección ilegible)")

    /**
     * Un enlace de descarga, reconocible y sin secretos. De un ed2k o un magnet, el nombre del
     * fichero, y de un magnet nunca los rastreadores, que llevan la clave del usuario. De una
     * dirección web, el servidor y una huella para seguirla de línea en línea: buscadores y
     * rastreadores meten su clave en cualquier parte, también en la ruta.
     */
    fun enlace(link: String): String {
        val t = link.trim()

        return when (Links.detect(t)) {
            LinkKind.ED2K -> {
                // ed2k://|file|NOMBRE|TAMAÑO|HASH|/
                val partes = t.split('|')
                if (partes.size > 4 && partes[1].equals("file", ignoreCase = true)) {
                    // En un ed2k el «+» es parte del nombre, no un espacio.
                    "ed2k «${descodificar(partes[2].replace("+", "%2B"))}» (${partes[3]} bytes)"
                } else {
                    "ed2k (${t.length} caracteres)"
                }
            }

            LinkKind.MAGNET -> {
                val nombre = Patrones.MAGNET_NOMBRE.find(t)?.groupValues?.get(1)?.let(::descodificar)
                val hash = Patrones.MAGNET_HASH.find(t)?.groupValues?.get(1)
                "magnet «${nombre ?: "sin nombre"}» (btih ${hash?.take(12) ?: "?"}…)"
            }

            else -> if (t.startsWith("data:", ignoreCase = true)) {
                "fichero dentro de la dirección (${t.length} caracteres)"
            } else {
                "${origen(t)} (huella ${Integer.toHexString(t.hashCode())})"
            }
        }
    }

    /**
     * Tacha el usuario de una dirección, las direcciones metidas dentro de otra (el `name=` de
     * SABnzbd lleva la del buscador) y el valor de todo lo que parezca secreto. Tachar de más no
     * importa; de menos, sí.
     */
    fun sinSecretos(texto: String): String {
        val corto = if (texto.length > TOPE_TEXTO) texto.take(TOPE_TEXTO) + "…" else texto
        return corto
            .replace(Patrones.USUARIO, "•••@")
            .replace(Patrones.ENLACE_DENTRO) { "${it.groupValues[1]}(enlace)" }
            .replace(Patrones.SECRETO) { "${it.groupValues[1]}•••" }
            .replace(Patrones.SECRETO_JSON) { "${it.groupValues[1]}•••" }
    }

    private fun descodificar(texto: String): String =
        runCatching { URLDecoder.decode(texto, "UTF-8") }.getOrDefault(texto)

    /**
     * Aparte para que la versión publicada, que nunca los usa, no los prepare al arrancar.
     *
     * Los nombres llevan tope de largo: sin él, un texto raro hace que el tachado tarde
     * segundos (vuelve atrás sobre cada letra una y otra vez).
     */
    private object Patrones {
        val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS xxx", Locale.ROOT)

        val USUARIO = Regex("(?<=://)[^/\\s]+@")

        val ENLACE_DENTRO = Regex(
            "(=|%3D)(?:https?|ftp|magnet|ed2k)(?::|%3A)[^&\\s#\"']*",
            RegexOption.IGNORE_CASE
        )

        /** `r` e `i` a secas son la clave y el usuario en los enlaces de los buscadores Newznab. */
        val SECRETO = Regex(
            "((?:^|[?&;#,(\\s\"'{]|%26|%3F|%23)" +
                "(?:[a-z0-9_.\\-]{0,40}(?:key|pass|pwd|token|auth|sid|session|secret|sig|cred)[a-z0-9_.\\-]{0,40}|r|i)" +
                "(?:=|%3D)[\"']?)[^&\\s#\"',;}]*",
            RegexOption.IGNORE_CASE
        )

        val SECRETO_JSON = Regex(
            "(\"[^\"\\s]{0,40}(?:key|pass|pwd|token|auth|secret|cred)[^\"\\s]{0,40}\"\\s{0,5}:\\s{0,5}\")[^\"]*",
            RegexOption.IGNORE_CASE
        )

        val ENTRADA_ORG_JSON = Regex("(?s)( at character \\d+) of .*")

        val MAGNET_NOMBRE = Regex("[?&]dn=([^&\\s]*)")

        val MAGNET_HASH = Regex("btih:([^&\\s]*)", RegexOption.IGNORE_CASE)
    }
}
