package com.homelab.panel

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Desbloqueo de la aplicación con la huella o el código del móvil.
 *
 * Criterio, y conviene leerlo con cuidado porque es fácil dejar aquí un agujero:
 *
 *  - Huella **no reconocida**: no pasa nada, el diálogo sigue en pantalla y se puede
 *    reintentar. No abre.
 *  - **Demasiados intentos fallidos** (el sistema bloquea el lector): se pide el **código
 *    del móvil**, y si ahí también se falla o se cancela, la aplicación se cierra. Antes
 *    este caso llegaba como «error» y se colaba por la puerta de los fallos técnicos:
 *    cinco huellas mal y adentro.
 *  - **Cancelar** a propósito: no abre.
 *  - **No hay con qué comprobar** —lector estropeado, sin huellas registradas, sin código
 *    de pantalla—: **abre igualmente**. Si no, un móvil al que se le rompe el sensor
 *    dejaría a su dueño fuera de su propio panel para siempre, y encima sin forma de
 *    desactivar el desbloqueo desde dentro.
 */
object BiometricGate {

    enum class State { AVAILABLE, NO_HARDWARE, NOT_ENROLLED, UNAVAILABLE }

    /**
     * Errores que significan «este móvil no puede comprobar nada», y solo esos. Cualquier
     * otro —cancelar, agotar los intentos, quedarse sin tiempo— deja la aplicación
     * cerrada.
     */
    private val SIN_CON_QUE_COMPROBAR = setOf(
        BiometricPrompt.ERROR_HW_NOT_PRESENT,
        BiometricPrompt.ERROR_HW_UNAVAILABLE,
        BiometricPrompt.ERROR_NO_BIOMETRICS,
        BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
        BiometricPrompt.ERROR_NO_SPACE
    )

    private const val PERMITIDOS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** Solo el código del móvil, para cuando la huella se bloquea por intentos fallidos. */
    private const val SOLO_CODIGO = BiometricManager.Authenticators.DEVICE_CREDENTIAL

    fun state(context: Context): State =
        when (BiometricManager.from(context).canAuthenticate(PERMITIDOS)) {
            BiometricManager.BIOMETRIC_SUCCESS -> State.AVAILABLE
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> State.NO_HARDWARE
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> State.NOT_ENROLLED
            else -> State.UNAVAILABLE
        }

    /**
     * @param onResult true si se identificó, false solo si canceló a propósito.
     */
    fun ask(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        if (state(activity) != State.AVAILABLE) {
            onResult(true)
            return
        }

        pedir(activity, PERMITIDOS, onResult)
    }

    private fun pedir(
        activity: FragmentActivity,
        autenticadores: Int,
        onResult: (Boolean) -> Unit
    ) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onResult(true)
                }

                override fun onAuthenticationError(code: Int, mensaje: CharSequence) {
                    val agotadosLosIntentos =
                        code == BiometricPrompt.ERROR_LOCKOUT ||
                            code == BiometricPrompt.ERROR_LOCKOUT_PERMANENT

                    // Con la huella bloqueada por intentos fallidos queda el código del
                    // móvil. Si ahí también falla o cancela, la aplicación se cierra: es
                    // el segundo intento, no un tercero.
                    if (agotadosLosIntentos && autenticadores != SOLO_CODIGO) {
                        pedir(activity, SOLO_CODIGO, onResult)
                        return
                    }

                    onResult(code in SIN_CON_QUE_COMPROBAR)
                }

                // Una huella no reconocida no cierra el diálogo: se puede reintentar.
                override fun onAuthenticationFailed() = Unit
            }
        )

        // El sistema ya muestra el nombre de la aplicación encima, así que repetirlo en
        // el título lo dejaba duplicado.
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(activity.getString(R.string.biometric_title))
            .setSubtitle(activity.getString(R.string.biometric_subtitle))
            .setAllowedAuthenticators(autenticadores)
            .build()

        prompt.authenticate(info)
    }
}
