package com.homelab.panel

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Guarda y recupera la configuración del panel en un fichero JSON dentro del
 * almacenamiento privado de la aplicación.
 *
 * Las contraseñas no están aquí: viven cifradas en [SecureStore]. Así el fichero que
 * el usuario exporta se puede compartir o guardar en la nube sin regalar las claves de
 * su servidor.
 */
object ConfigStore {

    private const val TAG = "Panel"
    private const val FILE = "config.json"
    private const val FILE_ANTERIOR = "config-anterior.json"

    /** Última configuración que se pudo leer entera. La red de seguridad. */
    private const val FILE_COPIA = "config-copia.json"

    /** Donde se aparta una configuración que no se ha podido leer. Nunca se borra. */
    private const val FILE_ROTO = "config-roto.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Qué pasó al leer la configuración, para poder decírselo al usuario. */
    enum class Estado { NORMAL, RECUPERADA, PERDIDA }

    /**
     * Aviso pendiente de dar. **Una lectura correcta no lo borra**: la configuración se lee
     * dos veces al arrancar —una para saber si hay que pedir la huella y otra para la
     * pantalla—, y la segunda ya encuentra el fichero arreglado. Se limpia cuando el
     * usuario ha visto el aviso, con [avisoVisto].
     */
    var ultimoEstado: Estado = Estado.NORMAL
        private set

    fun avisoVisto() {
        ultimoEstado = Estado.NORMAL
    }

    /**
     * Lee la configuración, recuperándose de un fichero dañado.
     *
     * Antes, si el fichero no se podía leer se partía de cero **sin decir nada**, y en
     * cuanto el usuario tocaba cualquier ajuste esa configuración vacía se guardaba encima:
     * quien tuviera veinte servicios metidos a mano los perdía todos sin enterarse. Ahora
     * el fichero ilegible se aparta en vez de machacarse, se prueba con la copia, y el
     * resultado queda en [ultimoEstado] para avisar.
     */
    fun load(context: Context): PanelConfig =
        loadFrom(context.filesDir) { DefaultConfig.create(context) }

    /**
     * El trabajo de verdad, sobre una carpeta cualquiera y sin depender de Android.
     *
     * Está separado del [Context] para poder probarlo: es la pieza donde un fallo cuesta la
     * configuración entera del usuario, y comprobarlo a mano exige corromper el fichero en
     * un móvil de verdad. Ver `ConfigStoreTest`.
     */
    fun loadFrom(carpeta: File, deFabrica: () -> PanelConfig): PanelConfig {
        val fichero = File(carpeta, FILE)

        if (!fichero.exists()) {
            val inicial = deFabrica()
            saveIn(carpeta, inicial)
            return inicial
        }

        leer(fichero)?.let { buena ->
            // Esta vale, así que pasa a ser la red de seguridad. Se hace al arrancar y no
            // en cada guardado: guardar es constante —cada interruptor, cada color— y
            // triplicar las escrituras se nota al arrastrar un deslizador.
            runCatching { fichero.copyTo(File(carpeta, FILE_COPIA), overwrite = true) }
            return buena
        }

        Log.w(TAG, "La configuración no se puede leer; se aparta y se prueba con la copia")
        runCatching { fichero.copyTo(File(carpeta, FILE_ROTO), overwrite = true) }

        leer(File(carpeta, FILE_COPIA))?.let { deLaCopia ->
            ultimoEstado = Estado.RECUPERADA
            saveIn(carpeta, deLaCopia)
            return deLaCopia
        }

        ultimoEstado = Estado.PERDIDA
        Log.e(TAG, "Tampoco hay copia utilizable; se parte de cero")
        val inicial = deFabrica()
        saveIn(carpeta, inicial)
        return inicial
    }

    /**
     * Guarda la configuración sin poder dejarla a medias.
     *
     * Se escribe en un fichero aparte, se comprueba que lo escrito se puede volver a leer y
     * solo entonces se pone en su sitio con un renombrado, que el sistema hace de una pieza.
     * Escribiendo directamente sobre el fichero bueno, un apagón a mitad lo dejaba cortado
     * y la configuración se perdía.
     */
    fun save(context: Context, config: PanelConfig) = saveIn(context.filesDir, config)

    /** Ver [save]. Separado del [Context] para poder probarlo. */
    fun saveIn(carpeta: File, config: PanelConfig) {
        val destino = File(carpeta, FILE)
        val temporal = File(carpeta, "$FILE.tmp")

        runCatching {
            temporal.writeText(json.encodeToString(PanelConfig.serializer(), config))

            // Si lo que acaba de escribirse no se puede leer, no se toca el fichero bueno.
            if (leer(temporal) == null) error("lo escrito no se puede volver a leer")

            if (!temporal.renameTo(destino)) {
                // Algunos sistemas no renombran sobre un fichero que ya existe.
                destino.delete()
                if (!temporal.renameTo(destino)) error("no se pudo reemplazar el fichero")
            }
        }.onFailure {
            Log.e(TAG, "No se pudo guardar la configuración; se deja la anterior", it)
            runCatching { temporal.delete() }
        }
    }

    /** Lee un fichero de configuración, o null si no está o no se entiende. */
    private fun leer(fichero: File): PanelConfig? {
        if (!fichero.exists() || fichero.length() == 0L) return null

        return runCatching {
            json.decodeFromString(PanelConfig.serializer(), fichero.readText())
        }.getOrNull()
    }

    /**
     * Guarda una copia de la configuración actual antes de una operación que la reemplaza.
     *
     * Es la red de seguridad de las importaciones: traer un panel de fuera o restaurar una
     * copia machaca lo que había, y sin esto no habría forma de volver atrás.
     */
    fun keepPrevious(context: Context, config: PanelConfig) {
        runCatching {
            File(context.filesDir, FILE_ANTERIOR)
                .writeText(json.encodeToString(PanelConfig.serializer(), config))
        }.onFailure { Log.w(TAG, "No se pudo guardar la configuración anterior", it) }
    }

    /**
     * Cuándo se guardó la configuración anterior, o `null` si no hay ninguna.
     *
     * Se enseña junto al botón de deshacer: «volver a la anterior» sin decir a cuándo
     * corresponde obliga a probar para saber qué se va a recuperar.
     */
    fun previousDate(context: Context): Long? =
        File(context.filesDir, FILE_ANTERIOR)
            .takeIf { it.exists() && it.length() > 0 }
            ?.lastModified()

    /** Configuración anterior guardada, o null si no hay ninguna. */
    fun previous(context: Context): PanelConfig? {
        val file = File(context.filesDir, FILE_ANTERIOR)
        if (!file.exists()) return null

        return runCatching { json.decodeFromString(PanelConfig.serializer(), file.readText()) }
            .onFailure { Log.w(TAG, "La configuración anterior no se pudo leer", it) }
            .getOrNull()
    }

    fun forgetPrevious(context: Context) {
        runCatching { File(context.filesDir, FILE_ANTERIOR).delete() }
    }

    /** Texto para exportar. Nunca contiene contraseñas. */
    fun export(config: PanelConfig): String =
        json.encodeToString(PanelConfig.serializer(), config)

    /** Lee una configuración exportada. Devuelve null si el texto no es válido. */
    fun import(texto: String): PanelConfig? =
        runCatching { json.decodeFromString(PanelConfig.serializer(), texto) }
            .onFailure { Log.w(TAG, "El fichero no contiene una configuración válida", it) }
            .getOrNull()
}
