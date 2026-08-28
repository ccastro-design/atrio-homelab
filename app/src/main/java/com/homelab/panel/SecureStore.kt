package com.homelab.panel

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Guarda secretos cifrados con una clave del almacén de claves de Android. En los
 * móviles con chip seguro esa clave no se puede extraer del dispositivo.
 *
 * Se guardan aquí, y no en la configuración, para que el fichero exportado no lleve
 * ninguna contraseña dentro.
 */
object SecureStore {

    private const val TAG = "Panel"
    private const val FILE = "secrets"

    /** Contraseña de un destino de descarga. */
    fun targetKey(targetId: String) = "target.$targetId.password"

    /** Credenciales de autocompletado de un servicio. */
    fun serviceUserKey(serviceId: String) = "service.$serviceId.user"
    fun servicePasswordKey(serviceId: String) = "service.$serviceId.password"

    private fun prefs(context: Context): SharedPreferences? = runCatching {
        val clave = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            FILE,
            clave,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.onFailure { Log.e(TAG, "No se pudo abrir el almacén cifrado", it) }.getOrNull()

    fun save(context: Context, key: String, value: String) {
        prefs(context)?.edit()?.putString(key, value)?.apply()
    }

    fun read(context: Context, key: String): String? = prefs(context)?.getString(key, null)

    fun has(context: Context, key: String): Boolean = !read(context, key).isNullOrEmpty()

    fun delete(context: Context, key: String) {
        prefs(context)?.edit()?.remove(key)?.apply()
    }

    /**
     * Borra todo lo guardado: contraseñas de servicios y de destinos de descarga.
     *
     * Es el botón de pánico de Ajustes › Seguridad, para antes de prestar el móvil o si
     * se ha metido una contraseña donde no tocaba. Sin él había que entrar servicio por
     * servicio.
     */
    // El analizador pide `apply` en vez de `commit`. Aquí está al revés a propósito, y el
    // motivo está justo debajo: es el único borrado tras el que se mata el proceso.
    @SuppressLint("ApplySharedPref")
    fun forgetAll(context: Context) {
        // `commit` y no `apply`: **este borrado termina matando el proceso** (ver Ajustes ›
        // Seguridad), y `apply` solo encola la escritura para que otro la haga más tarde.
        // Si el proceso muere antes, las contraseñas siguen en el fichero; y como el
        // sistema reescribe ese fichero pasando por una copia de respaldo, morir a mitad
        // las **restaura** en el siguiente arranque. Peor todavía: la configuración sí se
        // guarda de forma segura, así que las fichas dirían que no hay credenciales
        // mientras siguen ahí, y esto es justo el botón de antes de prestar el móvil.
        //
        // Bloquea unos milisegundos. En una acción puntual del usuario es lo correcto: en
        // los demás caminos —guardar y borrar una sola credencial— `apply` sigue bien,
        // porque allí el proceso sigue vivo y el sistema espera esas escrituras al pausar.
        prefs(context)?.edit()?.clear()?.commit()
    }

    /** Borra todo lo guardado de un destino de descarga que se elimina. */
    fun forgetTarget(context: Context, targetId: String) {
        delete(context, targetKey(targetId))
    }

    /** Borra las credenciales de un servicio que se elimina. */
    fun forgetService(context: Context, serviceId: String) {
        delete(context, serviceUserKey(serviceId))
        delete(context, servicePasswordKey(serviceId))
    }
}
