package com.homelab.panel

import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Una pestaña abierta. Guarda su propio WebView para que cambiar de pestaña no recargue
 * la página ni pierda la sesión.
 */
class TabState(val service: Service, url: String) {

    /** Identificador estable, aunque se abra el mismo servicio dos veces. */
    val key: String = "${service.id}-${System.nanoTime()}"

    /** Cambia al cambiar de perfil de red: entonces hay que recargar. */
    var url by mutableStateOf(url)
        private set

    /**
     * Sube en cada carga. Sirve para rearmar el tiempo límite: vigilando solo la
     * primera carga, una recarga que se colgaba dejaba la pantalla en negro para
     * siempre.
     */
    var loadId by mutableIntStateOf(0)
        private set

    var loading by mutableStateOf(true)
    var progress by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)

    /** El WebView vive fuera de la composición, en un contenedor propio. */
    var view: WebView? = null

    /** El autocompletado se intenta una sola vez por pestaña. */
    var autoLoginTried = false

    /**
     * Las credenciales guardadas se prueban una sola vez ante el aviso del servidor. Si no
     * valen, el servidor vuelve a pedirlas y hay que preguntar en vez de reintentar en
     * bucle con lo mismo.
     */
    var httpAuthTried = false

    /** Carga una dirección, o recarga la actual, dejando el estado limpio. */
    fun load(nueva: String = url) {
        url = nueva
        loading = true
        progress = 0
        error = null
        autoLoginTried = false
        httpAuthTried = false
        loadId++
        view?.loadUrl(nueva)
    }

    fun destroy() {
        view?.apply {
            stopLoading()
            (parent as? android.view.ViewGroup)?.removeView(this)
            destroy()
        }
        view = null
    }
}
