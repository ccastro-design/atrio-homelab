package com.homelab.panel

import android.content.Context
import android.content.Intent

/**
 * Ayudas para probar la aplicación desde el ordenador. Este móvil no permite simular
 * toques por adb, así que se abren las pantallas por intent:
 *
 * Ojo al nombre del componente: la parte de la izquierda es el `applicationId` y la de
 * la derecha el paquete del código, que **no coinciden**.
 *
 *   adb shell am start -n io.github.ccastrodesign.atrio/com.homelab.panel.MainActivity -e pantalla ajustes
 *
 * Solo funciona en compilaciones de depuración: en la de publicación `BuildConfig.DEBUG` es
 * siempre falso y R8 elimina todo esto al minificar.
 */
internal object Depuracion {

    /** Lo que se ha pedido abrir desde el ordenador. */
    data class Peticion(val pantalla: String?, val servicio: String?)

    /**
     * El salto del desbloqueo existe solo para poder verificar la aplicación desde el
     * ordenador, y solo en compilaciones de depuración: en la de publicación
     * `BuildConfig.DEBUG` es falso y la condición nunca se cumple.
     */
    fun saltarDesbloqueo(intent: Intent?): Boolean =
        BuildConfig.DEBUG && intent?.getStringExtra("sinhuella") != null

    /**
     * Aplica los extras del intent —sembrar, importar— y devuelve qué pantalla o servicio
     * se ha pedido abrir. Null en la versión publicada.
     *
     * **Ojo: sembrar e importar pisan la configuración de verdad** del móvil en que se usen.
     */
    fun aplicarExtras(context: Context, intent: Intent?): Peticion? {
        if (!BuildConfig.DEBUG || intent == null) return null

        when (intent.getStringExtra("sembrar")) {
            null -> Unit

            // Solo los destinos de descarga, sin tocar el panel: sirve para recuperarlos
            // conservando su identificador, y con él la contraseña ya guardada.
            "destinos" -> {
                val actual = ConfigStore.load(context)
                val nasDelPanel = actual.servers.firstOrNull { it.hostHome == "192.168.1.254" }

                val nuevos = DebugSeed.create().downloadTargets
                    .filter { candidato -> actual.downloadTargets.none { it.id == candidato.id } }
                    .map { destino ->
                        if (nasDelPanel != null) destino.copy(serverId = nasDelPanel.id)
                        else destino
                    }

                ConfigStore.save(
                    context,
                    actual.copy(downloadTargets = actual.downloadTargets + nuevos)
                )
            }

            else -> ConfigStore.save(context, DebugSeed.create())
        }

        // Importa un panel desde un fichero del dispositivo, para poder probar la lectura
        // sin recorrer el selector de ficheros a mano:
        //   adb push config.yml /data/local/tmp/config.yml
        //   adb shell am start ... -e importar /data/local/tmp/config.yml
        intent.getStringExtra("importar")?.let { ruta ->
            val leido = runCatching { java.io.File(ruta).readText() }
                .getOrNull()
                ?.let { PanelImport.parse(it) }

            if (leido == null) {
                android.util.Log.w("Panel", "No se pudo importar $ruta")
            } else {
                android.util.Log.i(
                    "Panel",
                    "Importados ${leido.serviceCount} servicios, " +
                        "${leido.groups.size} grupos, ${leido.servers.size} servidores, " +
                        "${leido.logoPaths.size} iconos declarados"
                )

                val anterior = ConfigStore.load(context)
                ConfigStore.keepPrevious(context, anterior)

                // Se parte de la configuración actual, igual que hace la pantalla de
                // ajustes: construirla de cero se llevaba por delante los destinos de
                // descarga y los demás ajustes.
                var nueva = anterior.copy(
                    title = leido.title,
                    subtitle = leido.subtitle,
                    servers = leido.servers,
                    groups = leido.groups
                )

                // Con -e origen http://... se traen también los iconos del panel viejo.
                val origen = intent.getStringExtra("origen")
                if (origen != null) {
                    kotlinx.coroutines.runBlocking {
                        nueva = PanelImport.withOriginIcons(context, nueva, leido, origen)
                    }
                }

                ConfigStore.save(context, nueva)
            }
        }

        // Segundo fichero con las direcciones de fuera:
        //   adb shell am start ... -e importarfuera /data/local/tmp/vpn.yml
        intent.getStringExtra("importarfuera")?.let { ruta ->
            val leido = runCatching { java.io.File(ruta).readText() }
                .getOrNull()
                ?.let { PanelImport.parse(it) }

            if (leido != null) {
                val (nueva, cuenta) = PanelImport.applyAwayAddresses(ConfigStore.load(context), leido)
                android.util.Log.i("Panel", "Direcciones de fuera aplicadas a $cuenta servidores")
                ConfigStore.save(context, nueva.copy(profile = NetworkProfile.AUTO.name))
            }
        }

        return Peticion(intent.getStringExtra("pantalla"), intent.getStringExtra("servicio"))
    }
}
