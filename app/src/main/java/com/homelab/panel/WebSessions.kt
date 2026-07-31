package com.homelab.panel

import android.webkit.CookieManager
import android.webkit.WebStorage

/**
 * Sesiones abiertas en las pestañas del panel.
 *
 * Lo que mantiene la sesión de un servicio entre visitas son sus cookies y lo que guarde
 * la página en el almacenamiento del navegador. Es lo que hace que volver a una pestaña
 * te encuentre dentro, así que se conserva por omisión; quien prefiera lo contrario lo
 * apaga en Ajustes › Seguridad.
 *
 * Las contraseñas guardadas no se tocan aquí: viven cifradas y aparte, y se borran desde
 * su propio botón.
 */
object WebSessions {

    fun clear() {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }
}
