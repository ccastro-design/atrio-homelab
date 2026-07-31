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

/** Lo que se puede borrar de golpe desde Seguridad. */
private enum class Borrado { SESIONES, CONTRASENAS, CERTIFICADOS }

/**
 * Interruptor de una opción, con su explicación debajo.
 *
 * Del mismo tamaño en todas las pantallas de ajustes: cada una llevaba el suyo y en
 * Seguridad se leían más pequeños que en Apariencia.
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

/** Ejecuta el borrado ya confirmado. */
private fun aplicarBorrado(
    que: Borrado,
    context: android.content.Context,
    config: PanelConfig,
    onConfigChange: (PanelConfig) -> Unit
) {
    when (que) {
        Borrado.SESIONES -> WebSessions.clear()

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
internal fun PaginaDeSeguridad(config: PanelConfig, onConfigChange: (PanelConfig) -> Unit) {
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
                            if (autorizado) aplicarBorrado(que, context, config, onConfigChange)
                        }
                    } else {
                        aplicarBorrado(que, context, config, onConfigChange)
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
        // casa con otra red, así que tiene que verse al entrar y no al final del todo.
        // Siempre a la vista aunque no haya ninguna todavía: es donde hay que venir al
        // cambiar de router, y una sección que solo aparece cuando ya hay algo dentro no
        // se encuentra nunca.
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
