package com.homelab.panel

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp

/*
 * Los avisos de la pantalla principal: aquí solo se pintan, y qué pasa al contestarlos lo
 * decide quien los llama.
 */

/**
 * La configuración no se pudo leer al arrancar, o hubo que recuperarla de la copia. Hay que
 * decirlo: perder el panel en silencio es lo peor que puede hacer esta aplicación, y
 * enterarse a las dos semanas, peor todavía.
 */
@Composable
fun AvisoDeConfiguracion(recuperada: Boolean, onVisto: () -> Unit) {
    AlertDialog(
        onDismissRequest = onVisto,
        title = {
            Text(
                stringResource(
                    if (recuperada) R.string.config_recovered_title
                    else R.string.config_lost_title
                )
            )
        },
        text = {
            Text(
                stringResource(
                    if (recuperada) R.string.config_recovered_body
                    else R.string.config_lost_body
                ),
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onVisto) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

/** Al terminar la presentación por primera vez: ofrecer apuntar la red de casa. */
@Composable
fun PreguntaRedDeCasa(onIr: () -> Unit, onLuego: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLuego,
        title = { Text(stringResource(R.string.ask_network_title)) },
        text = { Text(stringResource(R.string.ask_network_body), fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onIr) { Text(stringResource(R.string.ask_network_go)) }
        },
        dismissButton = {
            TextButton(onClick = onLuego) {
                Text(stringResource(R.string.ask_network_later))
            }
        }
    )
}

/**
 * Se ha tocado un servicio que no tiene con qué abrirse. Se dice por qué y se ofrece ir a su
 * ficha, que es donde se arregla: allí se le elige otro servidor o se le pone una dirección
 * propia.
 */
@Composable
fun AvisoSinDireccion(
    servicio: Service,
    config: PanelConfig,
    fuera: Boolean,
    onEditar: () -> Unit,
    onCancelar: () -> Unit
) {
    val servidor = config.server(servicio.serverId)

    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(stringResource(R.string.no_address_title, servicio.name)) },
        text = {
            Text(
                when (config.motivoSinDireccion(servicio, fuera)) {
                    NoSePuedeAbrir.SERVIDOR_BORRADO ->
                        stringResource(R.string.no_address_server_gone)

                    NoSePuedeAbrir.SERVIDOR_SIN_DIRECCION ->
                        stringResource(
                            R.string.no_address_server_empty,
                            servidor?.name.orEmpty()
                        )

                    else -> stringResource(R.string.no_address_none)
                },
                fontSize = 14.sp
            )
        },
        confirmButton = {
            TextButton(onClick = onEditar) { Text(stringResource(R.string.edit)) }
        },
        dismissButton = {
            TextButton(onClick = onCancelar) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/**
 * Alguien responde en una dirección de casa, pero la red no es de las reconocidas. Mientras
 * no se conteste, la aplicación usa las direcciones de fuera: no se manda nada a una máquina
 * que podría no ser suya.
 */
@Composable
fun PreguntaRedDesconocida(wifi: String, onEsMia: () -> Unit, onNoEsMia: () -> Unit) {
    AlertDialog(
        onDismissRequest = onNoEsMia,
        title = { Text(stringResource(R.string.network_unknown_title)) },
        text = { Text(stringResource(R.string.network_unknown_body, wifi), fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onEsMia) { Text(stringResource(R.string.network_unknown_mine)) }
        },
        dismissButton = {
            TextButton(onClick = onNoEsMia) { Text(stringResource(R.string.network_unknown_not_mine)) }
        }
    )
}
