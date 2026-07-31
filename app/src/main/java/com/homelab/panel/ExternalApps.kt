package com.homelab.panel

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.text.Collator

/** Una aplicación instalada que el usuario puede abrir. */
data class InstalledApp(
    val packageName: String,
    val label: String
)

/**
 * Abrir un servicio en la aplicación nativa que tenga instalada el usuario.
 *
 * **Por qué hay que elegir la aplicación a mano.** Lo natural sería lanzar la dirección
 * del servicio con `ACTION_VIEW` y dejar que Android decida; eso es lo que se hacía y no
 * funcionaba nunca. Desde Android 12, una dirección `http(s)` solo llega a una aplicación
 * que no sea un navegador si esa aplicación tiene *verificado* el dominio: su desarrollador
 * ha publicado un `assetlinks.json` en ese servidor. Un servicio autoalojado en
 * `http://192.168.1.254:2283` no tiene —ni puede tener— nada de eso, así que el enlace
 * acaba siempre en el navegador. Immich, Plex, Jellyfin y compañía declaran como mucho su
 * dominio público, no la máquina del usuario.
 *
 * La única vía que funciona de verdad es que el usuario diga qué aplicación corresponde a
 * cada servicio y abrirla por su nombre de paquete.
 */
object ExternalApps {

    /** Iconos ya cargados. Son pocos y pequeños, y evita releerlos al desplazar la lista. */
    private val iconos = mutableMapOf<String, ImageBitmap?>()

    /**
     * Aplicaciones que aparecen en el lanzador del móvil, ordenadas por nombre.
     *
     * Se excluye el propio panel: abrirse a sí mismo no tiene sentido.
     */
    fun installed(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val lanzables = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val orden = Collator.getInstance()

        return runCatching {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(lanzables, 0)
                .mapNotNull { info ->
                    val paquete = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (paquete == context.packageName) return@mapNotNull null
                    InstalledApp(paquete, info.loadLabel(pm).toString())
                }
                .distinctBy { it.packageName }
                .sortedWith { a, b -> orden.compare(a.label, b.label) }
        }.getOrDefault(emptyList())
    }

    /** Nombre visible de una aplicación, o `null` si ya no está instalada. */
    fun label(context: Context, packageName: String): String? {
        if (packageName.isBlank()) return null
        val pm = context.packageManager
        return runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrNull()
    }

    /** Icono de una aplicación instalada, ya convertido para pintarlo en Compose. */
    fun icon(context: Context, packageName: String): ImageBitmap? {
        if (packageName.isBlank()) return null
        return iconos.getOrPut(packageName) {
            runCatching {
                // Tamaño explícito: los iconos adaptativos no siempre declaran uno
                // propio, y sin él la conversión falla.
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(96, 96)
                    .asImageBitmap()
            }.getOrNull()
        }
    }

    /**
     * Abre el servicio en la aplicación elegida. Devuelve `false` si no se pudo, que en la
     * práctica significa que el usuario la ha desinstalado.
     */
    fun open(context: Context, packageName: String, url: String): Boolean {
        if (packageName.isBlank()) return false
        val pm = context.packageManager

        // Primero, con la dirección: las pocas aplicaciones que sepan abrirla entran
        // directamente en la página que toca en vez de en su pantalla de inicio.
        if (url.isNotBlank()) {
            val conDireccion = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (conDireccion.resolveActivity(pm) != null &&
                runCatching { context.startActivity(conDireccion) }.isSuccess
            ) {
                return true
            }
        }

        // Lo normal: abrirla como la abriría el usuario desde el escritorio. Estas
        // aplicaciones ya saben a qué servidor conectarse, así que con esto basta.
        val lanzar = pm.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: return false

        return runCatching { context.startActivity(lanzar) }.isSuccess
    }
}
