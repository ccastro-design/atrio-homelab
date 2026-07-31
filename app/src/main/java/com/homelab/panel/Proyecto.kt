package com.homelab.panel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Las direcciones públicas del proyecto, en un solo sitio.
 *
 * Están vacías a propósito hasta que se decida el nombre comercial: de él dependen el
 * repositorio, la dirección donde se publique la política de privacidad y la página de
 * donaciones. Las filas de «Acerca de» que apuntan a una dirección vacía **no aparecen**,
 * así que la pantalla es correcta hoy y se completa sola en cuanto se rellene esto.
 *
 * La política de privacidad es obligatoria para publicar en Google Play y tiene que estar
 * en una dirección pública y accesible sin instalar nada.
 */
object Proyecto {

    /**
     * Repositorio del código fuente.
     *
     * `atrio-homelab` y no `atrio` a secas: el nombre corto es la marca del icono, pero un
     * repositorio se encuentra por lo que hace, y «atrio» solo no lo dice.
     */
    const val CODIGO = "https://github.com/ccastro-design/atrio-homelab"

    /**
     * Política de privacidad, en una página pública.
     *
     * Apunta al fichero del repositorio, que GitHub sirve ya renderizado y sin pedir
     * cuenta. Google Play exige que esta dirección exista y sea accesible para cualquiera
     * antes de poder publicar.
     */
    const val PRIVACIDAD = "https://github.com/ccastro-design/atrio-homelab/blob/main/PRIVACY.md"

    /**
     * Donación suelta, en Ko-fi. Sin nada a cambio: Google no permite otra cosa.
     *
     * Ko-fi no se queda comisión de las donaciones sueltas, pero **sí la pasarela**:
     * alrededor de un 2,9 % más 0,30 € por operación, que en una donación de 3 € es el
     * 13 %. Ojo con el programa «Contributor» de Ko-fi, que viene activado en las cuentas
     * nuevas y se lleva un 5 % adicional de todo.
     */
    const val DONACION = "https://ko-fi.com/elhumoviral"

    /**
     * Patrocinio recurrente, en GitHub Sponsors.
     *
     * Es lo más rentable con diferencia: GitHub no cobra nada de lo que viene de cuentas
     * personales. A cambio, quien dona necesita cuenta de GitHub, cosa que en este público
     * —gente con homelab— tiene casi todo el mundo.
     *
     * **Vacío a propósito**: la solicitud de GitHub Sponsors está enviada y sin aprobar, y
     * la dirección todavía da 404. Estando vacío, su fila **no aparece** en «Apoyar el
     * proyecto», que es mejor que un «próximamente» —no sirve de nada al que quiere donar—
     * y mucho mejor que un enlace roto, que en la revisión de Play cuenta como defecto.
     * En cuanto lo aprueben, se pone la dirección aquí y la fila sale sola.
     */
    const val PATROCINIO = ""

    /** Si hay algo que enseñar en la sección de apoyo. */
    val hayApoyo: Boolean get() = DONACION.isNotBlank() || PATROCINIO.isNotBlank()

    /**
     * Abre una dirección en el navegador del móvil.
     *
     * Aquí sí vale lanzarla y dejar que decida Android —al revés que con los servicios del
     * usuario, ver [ExternalApps]—: son direcciones públicas `https`, que es justo lo que
     * cualquier navegador acepta.
     */
    fun abrir(context: Context, url: String) {
        if (url.isBlank()) return

        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        runCatching { context.startActivity(intent) }.onFailure {
            Toast.makeText(context, R.string.about_no_browser, Toast.LENGTH_LONG).show()
        }
    }
}
