package com.homelab.panel

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Los tres borrados de Ajustes › Seguridad.
 *
 * @property exigeCerrar si hay que cerrar la aplicación para que el borrado sea de verdad.
 *   El motor del navegador guarda en memoria las credenciales de los servicios que las
 *   piden por su propia ventana —autenticación HTTP, como Transmission— y esa memoria
 *   **no se puede vaciar desde la aplicación**. Al morir el proceso, desaparece.
 */
private enum class Borrado(val exigeCerrar: Boolean) {
    SESIONES(exigeCerrar = true),
    CONTRASENAS(exigeCerrar = true),
    CERTIFICADOS(exigeCerrar = false)
}

/**
 * Interruptor de una opción, con su explicación debajo.
 *
 * Del mismo tamaño en todas las pantallas de ajustes.
 */
@Composable
private fun InterruptorDeAjuste(
    titulo: String,
    ayuda: String?,
    valor: Boolean,
    onChange: (Boolean) -> Unit,
    activado: Boolean = true
) {
    Row(
        Modifier.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                titulo,
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onBackground
                    .copy(alpha = if (activado) 1f else 0.5f)
            )
            ayuda?.let {
                Text(
                    it,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        Switch(checked = valor, enabled = activado, onCheckedChange = onChange)
    }
}

private fun avisar(context: android.content.Context, texto: Int) {
    Toast.makeText(context, context.getString(texto), Toast.LENGTH_SHORT).show()
}

/** Ejecuta el borrado ya confirmado. */
private fun aplicarBorrado(
    que: Borrado,
    context: android.content.Context,
    config: PanelConfig,
    onConfigChange: (PanelConfig) -> Unit,
    onSessionsCleared: () -> Unit
) {
    when (que) {
        Borrado.SESIONES -> {
            WebSessions.clear(context)
            // Y se cierran las pestañas: una página ya cargada sigue dentro del servicio
            // aunque se le quiten las cookies, así que sin esto el borrado parecía no
            // hacer nada.
            onSessionsCleared()
        }

        Borrado.CONTRASENAS -> {
            SecureStore.forgetAll(context)
            // Se apaga también el interruptor de cada servicio: si no, las fichas
            // seguirían diciendo que hay credenciales puestas.
            onConfigChange(
                config.copy(
                    groups = config.groups.map { grupo ->
                        grupo.copy(services = grupo.services.map { it.copy(autoLogin = false) })
                    }
                )
            )
        }

        Borrado.CERTIFICADOS -> onConfigChange(config.copy(trustedCerts = emptyMap()))
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
internal fun PaginaDeSeguridad(
    config: PanelConfig,
    onConfigChange: (PanelConfig) -> Unit,
    onSessionsCleared: () -> Unit,
    onCerrarApp: () -> Unit
) {
    val context = LocalContext.current
    val biometriaDisponible = remember { BiometricGate.state(context) == BiometricGate.State.AVAILABLE }
    var borrando by remember { mutableStateOf<Borrado?>(null) }

    // Borrar no se pregunta con un «¿seguro?» genérico: cada uno dice qué se lleva por
    // delante, porque no hay forma de deshacerlo.
    borrando?.let { que ->
        AlertDialog(
            onDismissRequest = { borrando = null },
            title = {
                Text(
                    stringResource(
                        when (que) {
                            Borrado.SESIONES -> R.string.security_erase_sessions
                            Borrado.CONTRASENAS -> R.string.security_erase_passwords
                            Borrado.CERTIFICADOS -> R.string.security_erase_certs
                        }
                    )
                )
            },
            text = {
                Text(
                    stringResource(
                        when (que) {
                            Borrado.SESIONES -> R.string.security_erase_sessions_ask
                            Borrado.CONTRASENAS -> R.string.security_erase_passwords_ask
                            Borrado.CERTIFICADOS -> R.string.security_erase_certs_ask
                        }
                    ),
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Segunda confirmación con la huella o el código del móvil: borrar
                    // todas las contraseñas es de lo más gordo que hace la aplicación, y
                    // un solo toque de más no debería bastar.
                    val actividad = context as? androidx.fragment.app.FragmentActivity
                    if (actividad != null) {
                        BiometricGate.ask(actividad) { autorizado ->
                            // Se dice siempre en qué quedó: alguien podía quedarse
                            // creyendo que había borrado sus contraseñas cuando seguían ahí.
                            if (autorizado) {
                                aplicarBorrado(que, context, config, onConfigChange, onSessionsCleared)
                                avisar(context, R.string.erase_done)
                                if (que.exigeCerrar) onCerrarApp()
                            } else {
                                avisar(context, R.string.erase_cancelled)
                            }
                        }
                    } else {
                        aplicarBorrado(que, context, config, onConfigChange, onSessionsCleared)
                        avisar(context, R.string.erase_done)
                        if (que.exigeCerrar) onCerrarApp()
                    }
                    borrando = null
                }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { borrando = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Cada sección dentro de su recuadro y el título fuera, igual que en Apariencia.
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {

        // La primera de la pantalla: es de lo que depende que el panel no confunda tu
        // casa con otra red. Siempre a la vista aunque no haya ninguna todavía: es donde
        // hay que venir al cambiar de router.
        TituloDeSeccion(stringResource(R.string.security_networks), Modifier.padding(start = 4.dp))

        Burbuja {
            Text(
                stringResource(R.string.security_networks_help),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )

            config.homeSsids.forEach { nombre ->
                Row(
                    Modifier.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(nombre, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        onConfigChange(config.copy(homeSsids = config.homeSsids - nombre))
                        NetworkResolver.invalidate()
                    }) {
                        Text(stringResource(R.string.cert_forget))
                    }
                }
            }

            AvisoDeRedSinUsar(config)
            BotonesDeRed(config, onConfigChange)
        }

        TituloDeSeccion(
            stringResource(R.string.security_section_unlock),
            Modifier.padding(start = 4.dp)
        )

        Burbuja {
            InterruptorDeAjuste(
                titulo = stringResource(R.string.security_unlock),
                // Sin explicación: la de antes solo servía para sembrar dudas. Cuando el
                // móvil no puede comprobar nada sí se dice, porque explica por qué el
                // interruptor está apagado y no se deja tocar.
                ayuda = stringResource(R.string.security_unlock_unavailable)
                    .takeIf { !biometriaDisponible },
                valor = config.requireUnlock,
                activado = biometriaDisponible,
                onChange = { onConfigChange(config.copy(requireUnlock = it)) }
            )

            // Justo debajo del interruptor del que depende: apareciendo al final de la
            // pantalla, no se veía de quién colgaba.
            if (config.requireUnlock) {
                Text(
                    stringResource(R.string.security_relock),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 14.dp)
                )
                Text(
                    stringResource(R.string.security_relock_help),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // En fila corrida la cuarta opción no cabía y quedaba apretujada contra
                // el borde; así baja sola a la siguiente línea.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        0 to stringResource(R.string.security_relock_now),
                        1 to stringResource(R.string.security_relock_1),
                        5 to stringResource(R.string.security_relock_5),
                        -1 to stringResource(R.string.security_relock_never)
                    ).forEach { (minutos, etiqueta) ->
                        FilterChip(
                            selected = config.relockMinutes == minutos,
                            onClick = { onConfigChange(config.copy(relockMinutes = minutos)) },
                            label = { Text(etiqueta) }
                        )
                    }
                }
            }
        }

        TituloDeSeccion(
            stringResource(R.string.security_section_warnings),
            Modifier.padding(start = 4.dp)
        )

        Burbuja {
            InterruptorDeAjuste(
                titulo = stringResource(R.string.check_status),
                ayuda = stringResource(R.string.security_status_help),
                valor = config.checkStatus,
                onChange = { onConfigChange(config.copy(checkStatus = it)) }
            )
            InterruptorDeAjuste(
                titulo = stringResource(R.string.warn_cleartext),
                ayuda = stringResource(R.string.security_cleartext_help),
                valor = config.warnCleartext,
                onChange = { onConfigChange(config.copy(warnCleartext = it)) }
            )
        }

        TituloDeSeccion(
            stringResource(R.string.security_section_privacy),
            Modifier.padding(start = 4.dp)
        )

        Burbuja {
            InterruptorDeAjuste(
                titulo = stringResource(R.string.security_hide_recents),
                ayuda = stringResource(R.string.security_hide_recents_help),
                valor = config.secureScreen,
                onChange = { onConfigChange(config.copy(secureScreen = it)) }
            )
            InterruptorDeAjuste(
                titulo = stringResource(R.string.security_sessions),
                ayuda = stringResource(R.string.security_sessions_help),
                valor = config.clearSessionsOnExit,
                onChange = { onConfigChange(config.copy(clearSessionsOnExit = it)) }
            )
        }

        // Sin título de sección: son botones que se explican solos y que además no
        // conviene destacar más de lo justo. En rojo porque no se pueden deshacer.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BotonDeBorrado(stringResource(R.string.security_erase_sessions)) {
                borrando = Borrado.SESIONES
            }
            BotonDeBorrado(stringResource(R.string.security_erase_passwords)) {
                borrando = Borrado.CONTRASENAS
            }
            if (config.trustedCerts.isNotEmpty()) {
                BotonDeBorrado(stringResource(R.string.security_erase_certs)) {
                    borrando = Borrado.CERTIFICADOS
                }
            }
        }


        if (config.trustedCerts.isNotEmpty()) {
            TituloDeSeccion(
                stringResource(R.string.cert_trusted_list),
                Modifier.padding(start = 4.dp)
            )

            Burbuja {
                config.trustedCerts.keys.sorted().forEach { host ->
                    Row(
                        Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            host,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { onConfigChange(CertTrust.revoke(config, host)) }) {
                            Text(stringResource(R.string.cert_forget))
                        }
                    }
                }
            }
        }
    }
}

/** Por qué las redes apuntadas no se están usando. Ver [AvisoDeRedSinUsar]. */
internal enum class RedSinUsar { SIN_PERMISO, SIN_UBICACION }

/** El motivo, o null si se usan (o si no hay ninguna apuntada, que no hay nada que avisar). */
internal fun redSinUsar(hayRedes: Boolean, permiso: Boolean, ubicacion: Boolean): RedSinUsar? = when {
    !hayRedes -> null
    !permiso -> RedSinUsar.SIN_PERMISO
    !ubicacion -> RedSinUsar.SIN_UBICACION
    else -> null
}

/**
 * Las redes apuntadas no se están usando: sin el permiso de ubicación precisa, o con la
 * ubicación apagada, Android no dice en qué WiFi estás y el panel decide por si responde el
 * servidor. Pasó el 04/10/2026: la red estaba apuntada, el permiso no, y solo se había dicho
 * una vez, en un aviso breve al apuntarla.
 *
 * Se vuelve a mirar al volver a la pantalla: los dos se cambian en los ajustes de Android.
 */
@Composable
private fun AvisoDeRedSinUsar(config: PanelConfig) {
    val context = LocalContext.current
    var permiso by remember { mutableStateOf(WifiNetwork.hasPermission(context)) }
    var ubicacion by remember { mutableStateOf(WifiNetwork.locationEnabled(context)) }

    /** Denegado del todo: Android ya no enseña la pregunta y solo queda la ficha de la app. */
    var bloqueado by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val ciclo = (context as? androidx.activity.ComponentActivity)?.lifecycle
        val observador = androidx.lifecycle.LifecycleEventObserver { _, evento ->
            if (evento == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permiso = WifiNetwork.hasPermission(context)
                ubicacion = WifiNetwork.locationEnabled(context)
            }
        }
        ciclo?.addObserver(observador)
        onDispose { ciclo?.removeObserver(observador) }
    }

    val pedirPermiso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permiso = WifiNetwork.hasPermission(context)
        if (permiso) {
            NetworkResolver.invalidate()
        } else {
            val actividad = context as? android.app.Activity
            bloqueado = actividad != null && !androidx.core.app.ActivityCompat
                .shouldShowRequestPermissionRationale(actividad, android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    val motivo = redSinUsar(config.homeSsids.isNotEmpty(), permiso, ubicacion) ?: return

    Text(
        stringResource(
            if (motivo == RedSinUsar.SIN_PERMISO) R.string.network_unused_no_permission
            else R.string.network_unused_location_off
        ),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(top = 4.dp)
    )

    OutlinedButton(
        onClick = {
            when {
                motivo == RedSinUsar.SIN_UBICACION ->
                    abrirAjusteDeAndroid(context, android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                bloqueado ->
                    abrirAjusteDeAndroid(context, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, deEstaApp = true)
                else -> pedirPermiso.launch(WifiNetwork.PERMISSIONS)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
    ) {
        Text(
            stringResource(
                when {
                    motivo == RedSinUsar.SIN_UBICACION -> R.string.network_turn_on_location
                    bloqueado -> R.string.network_open_permissions
                    else -> R.string.network_grant
                }
            )
        )
    }
}

private fun abrirAjusteDeAndroid(context: android.content.Context, accion: String, deEstaApp: Boolean = false) {
    val intent = android.content.Intent(accion)
    if (deEstaApp) intent.data = android.net.Uri.fromParts("package", context.packageName, null)
    runCatching { context.startActivity(intent) }
}

/**
 * Añadir la WiFi de casa: leyéndola del móvil o escribiéndola a mano.
 *
 * Lo normal es lo primero, que no admite erratas. Escribirla a mano está para quien no
 * quiera dar el permiso de ubicación, tenga la ubicación apagada o quiera dejar apuntada
 * una red en la que ahora mismo no está.
 */
@Composable
private fun BotonesDeRed(config: PanelConfig, onConfigChange: (PanelConfig) -> Unit) {
    val context = LocalContext.current
    var escribiendo by remember { mutableStateOf(false) }

    fun anadir(nombre: String) {
        val limpio = nombre.trim()
        val aviso = when {
            limpio.isBlank() -> R.string.network_add_none
            limpio in config.homeSsids -> R.string.network_add_already
            else -> {
                onConfigChange(config.copy(homeSsids = config.homeSsids + limpio))
                NetworkResolver.invalidate()

                // El permiso no hace falta para apuntar el nombre, sino para preguntarle
                // a Android en qué red estamos cada vez. Sin él la red queda anotada y no
                // sirve para nada, y eso hay que decirlo: es justo el tipo de fallo
                // silencioso que deja al usuario creyendo que está protegido.
                if (WifiNetwork.hasPermission(context)) {
                    R.string.network_add_done
                } else {
                    R.string.network_add_no_permission
                }
            }
        }
        Toast.makeText(context, context.getString(aviso), Toast.LENGTH_LONG).show()
    }

    /** Lee el nombre de la red, ya con el permiso concedido. */
    fun leerYAnadir() {
        when {
            // El permiso no basta: con la ubicación apagada Android tampoco lo dice, y sin
            // explicarlo el usuario se queda sin saber por qué no funciona.
            !WifiNetwork.locationEnabled(context) ->
                Toast.makeText(context, R.string.network_needs_location, Toast.LENGTH_LONG).show()

            else -> {
                val nombre = WifiNetwork.currentSsid(context)
                if (nombre.isBlank()) {
                    Toast.makeText(context, R.string.network_add_none, Toast.LENGTH_LONG).show()
                } else {
                    anadir(nombre)
                }
            }
        }
    }

    val pedirPermiso = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { concedidos ->
        if (concedidos.values.any { it }) {
            leerYAnadir()
        } else {
            // Denegado: se ofrece la vía manual en vez de dejarlo en un callejón sin
            // salida. La aplicación entera tiene que funcionar sin este permiso.
            escribiendo = true
        }
    }

    OutlinedButton(
        onClick = {
            if (WifiNetwork.hasPermission(context)) {
                leerYAnadir()
            } else {
                pedirPermiso.launch(WifiNetwork.PERMISSIONS)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
    ) {
        Text(stringResource(R.string.network_add))
    }

    TextButton(
        onClick = { escribiendo = true },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(stringResource(R.string.network_add_manual))
    }

    if (escribiendo) {
        TextPromptDialog(
            title = stringResource(R.string.network_add_manual),
            initialValue = "",
            onCancel = { escribiendo = false },
            onAccept = { nombre ->
                anadir(nombre)
                escribiendo = false
            }
        )
    }
}
