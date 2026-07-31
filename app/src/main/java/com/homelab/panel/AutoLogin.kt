package com.homelab.panel

import android.content.Context
import android.util.Log
import android.webkit.WebView
import org.json.JSONObject

/**
 * Rellena usuario y contraseña en los servicios que cierran la sesión al salir.
 *
 * Va con salvaguardas, y cada una tiene su motivo:
 *  - Solo actúa si el origen de la página coincide EXACTAMENTE con el del servicio
 *    (esquema, equipo y puerto). Un redirect a cualquier otro sitio no recibe nada.
 *  - Un único intento por pestaña: con una contraseña equivocada, reintentar en cada
 *    recarga puede hacer que el servidor bloquee la dirección del móvil.
 *  - El usuario es opcional, porque hay paneles que solo piden contraseña.
 *  - Está desactivado de partida y se activa servicio a servicio.
 */
object AutoLogin {

    private const val TAG = "Panel"

    fun savedUser(context: Context, serviceId: String): String =
        SecureStore.read(context, SecureStore.serviceUserKey(serviceId)).orEmpty()

    /**
     * Guarda las credenciales de un servicio. El usuario puede quedar vacío.
     *
     * @param newPassword vacía significa conservar la que ya hubiera guardada.
     * @return false si no hay contraseña nueva ni antigua, o sea, nada que guardar.
     */
    fun save(context: Context, serviceId: String, user: String, newPassword: String): Boolean {
        val contrasena = newPassword.takeIf { it.isNotEmpty() }
            ?: SecureStore.read(context, SecureStore.servicePasswordKey(serviceId))
            ?: return false

        SecureStore.save(context, SecureStore.serviceUserKey(serviceId), user)
        SecureStore.save(context, SecureStore.servicePasswordKey(serviceId), contrasena)
        return true
    }

    fun forget(context: Context, serviceId: String) {
        SecureStore.forgetService(context, serviceId)
    }

    /**
     * Rellena el formulario si procede. Devuelve false si no había nada que hacer.
     *
     * @param allowedUrls direcciones legítimas del servicio: la de casa y la de fuera.
     */
    fun attempt(
        context: Context,
        view: WebView,
        service: Service,
        currentUrl: String?,
        allowedUrls: List<String>
    ): Boolean {
        if (!service.autoLogin) return false

        val contrasena = SecureStore.read(context, SecureStore.servicePasswordKey(service.id))
            ?.takeIf { it.isNotEmpty() } ?: return false
        val usuario = savedUser(context, service.id)

        if (allowedUrls.none { sameOrigin(currentUrl, it) }) {
            Log.i(TAG, "Autocompletado omitido en «${service.name}»: la página no es la del servicio.")
            return false
        }

        view.evaluateJavascript(script(usuario, contrasena)) { resultado ->
            Log.i(TAG, "Autocompletado en «${service.name}»: ${resultado?.trim('"')}")
        }
        return true
    }

    /** Compara esquema, equipo y puerto. Distinto origen, no se escribe nada. */
    private fun sameOrigin(actual: String?, configurada: String): Boolean {
        if (actual.isNullOrBlank() || configurada.isBlank()) return false

        return runCatching {
            val a = java.net.URI(actual)
            val b = java.net.URI(configurada)
            a.scheme.equals(b.scheme, true) &&
                a.host.equals(b.host, true) &&
                port(a) == port(b)
        }.getOrDefault(false)
    }

    private fun port(u: java.net.URI): Int =
        if (u.port != -1) u.port else if (u.scheme.equals("https", true)) 443 else 80

    private fun script(usuario: String, contrasena: String): String {
        val u = JSONObject.quote(usuario)
        val p = JSONObject.quote(contrasena)

        return """
(function () {
  const usuario = $u;
  const contrasena = $p;

  const visible = (el) => el && el.offsetParent !== null && !el.disabled && !el.readOnly;

  const campoPass = [...document.querySelectorAll('input[type="password"]')].find(visible);
  if (!campoPass) { return "sin-formulario"; }

  // Sin usuario configurado no se toca ningún campo de texto: hay paneles que solo
  // piden contraseña, y escribir en otra caja sería peor que no hacer nada.
  let campoUsuario = null;
  if (usuario) {
    campoUsuario = [...document.querySelectorAll(
      'input[autocomplete="username"], input[name*="user" i], input[id*="user" i],' +
      'input[name*="account" i], input[id*="account" i]')].find(visible);

    if (!campoUsuario) {
      const todos = [...document.querySelectorAll('input')];
      const pos = todos.indexOf(campoPass);
      campoUsuario = todos.slice(0, pos).reverse()
        .find(el => visible(el) && (el.type === 'text' || el.type === 'email'));
    }
  }

  // Asignar por el setter nativo y lanzar eventos: con .value directo, los
  // frameworks (ExtJS, React, Vue) no se enteran del cambio.
  const escribir = (el, valor) => {
    const setter = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value')?.set;
    if (setter) { setter.call(el, valor); } else { el.value = valor; }
    for (const evento of ['input', 'change', 'keyup', 'blur']) {
      el.dispatchEvent(new Event(evento, { bubbles: true }));
    }
  };

  if (campoUsuario) { escribir(campoUsuario, usuario); }
  escribir(campoPass, contrasena);

  const estado = !usuario ? "solo-contrasena"
    : (campoUsuario ? "usuario-y-contrasena" : "no-se-hallo-campo-usuario");

  const formulario = campoPass.form;

  // Pulsar el botón real incluye su par nombre/valor, que algunos servidores esperan.
  const boton = formulario
    ? formulario.querySelector('input[type="submit"], button[type="submit"], button:not([type])')
    : null;

  if (boton && visible(boton)) { boton.click(); return "enviado con su boton (" + estado + ")"; }

  if (formulario && typeof formulario.requestSubmit === 'function') {
    formulario.requestSubmit();
    return "enviado (" + estado + ")";
  }

  const suelto = [...document.querySelectorAll('button, input[type="submit"], a')]
    .find(el => visible(el) && /login|iniciar|acceder|entrar|sign in/i.test(el.innerText || el.value || ''));

  if (suelto) { suelto.click(); return "enviado por boton suelto (" + estado + ")"; }

  return "relleno sin enviar (" + estado + ")";
})();
        """.trimIndent()
    }
}
