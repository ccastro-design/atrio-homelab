package com.homelab.panel

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebViewDatabase

/**
 * Sesiones abiertas en las pestañas del panel. Las contraseñas guardadas no se tocan aquí:
 * viven cifradas y aparte, y se borran desde su propio botón.
 *
 * **Lo que esto NO puede borrar:** un servicio que pide la contraseña por la ventana del
 * navegador —autenticación HTTP, como Transmission— no guarda su sesión en cookies, sino
 * en una memoria interna del motor a la que Android no da acceso. Se corta al cerrar la
 * aplicación del todo, que es lo que hace el interruptor de «borrar sesiones al salir».
 */
object WebSessions {

    fun clear(context: Context) {
        CookieManager.getInstance().apply {
            // `flush` va aquí, seguido, y **no** dentro del aviso de que el borrado ha
            // terminado. Los dos van a la misma cola del motor y se hacen en orden; en el
            // aviso queda pendiente de un mensaje que puede no llegar a ejecutarse nunca,
            // y estas dos llamadas terminan **matando el proceso** a propósito.
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()

        // Usuario y contraseña que el navegador guarda por su cuenta para la autenticación
        // HTTP, y lo que hubiera escrito en formularios. Hoy la aplicación no los usa
        // —nunca llama a `setHttpAuthUsernamePassword`—, pero un WebView puede guardarlos
        // solo, y este botón promete no dejar rastro.
        runCatching {
            WebViewDatabase.getInstance(context).apply {
                clearHttpAuthUsernamePassword()
                clearFormData()
            }
        }
    }
}
