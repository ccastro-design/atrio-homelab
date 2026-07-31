package com.homelab.panel

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Título de sección, el mismo en todos los ajustes y fichas.
 *
 * Del tamaño de las cabeceras de grupo del panel (20 sp): a 12 sp no se leían y cada
 * pantalla llevaba el suyo, así que las secciones no se distinguían del texto de ayuda.
 */
@Composable
fun TituloDeSeccion(texto: String, modifier: Modifier = Modifier) {
    Text(
        texto,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 22.dp, bottom = 8.dp)
    )
}

/**
 * Pastilla roja de borrar. Del ancho del texto y centrada, una debajo de otra.
 *
 * Aquí y no en una pantalla concreta porque la usan dos: los borrados de Seguridad y el
 * restablecimiento de Copias.
 */
@Composable
fun BotonDeBorrado(texto: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        colors = ButtonDefaults.buttonColors(
            containerColor = PanelColors.StatusDown,
            // Texto negro sobre el rojo: en el tema oscuro el blanco de fábrica sobre
            // este rojo se lee peor.
            contentColor = Color.Black
        )
    ) {
        Text(texto)
    }
}

/**
 * Recuadro que agrupa el contenido de una sección de ajustes.
 *
 * El mismo borde y el mismo redondeo que [ClickableRow], para que una pantalla con
 * secciones y otra con filas pulsables se vean de la misma familia. El título de la
 * sección va **fuera**, encima.
 */
@Composable
fun Burbuja(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), content = content)
    }
}

/**
 * Fila pulsable de los ajustes.
 *
 * Va dentro de un recuadro con borde y con la flecha a la derecha, para que se vea que
 * se puede pulsar. Sin eso no había forma de adivinarlo: parecía texto suelto.
 */
@Composable
fun ClickableRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    monospaceLine: String? = null,
    accentLine: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailingText: String? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.let {
                it()
                Spacer(Modifier.width(16.dp))
            }

            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                monospaceLine?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                accentLine?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            trailingText?.let {
                Text(
                    it,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.padding(start = 6.dp)
            )
        }
    }
}

/**
 * Campo de contraseña que deja claro cuándo ya hay una guardada.
 *
 * Mientras el usuario no escriba, muestra unos puntos para que se vea que hay algo
 * guardado, y un texto de aviso debajo. Al escribir, los puntos desaparecen y la nueva
 * contraseña sustituye a la anterior. No se usa el `placeholder` de Material porque solo
 * se ve al enfocar el campo, así que no servía para avisar de nada.
 */
@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    hasSaved: Boolean,
    showingSavedMarker: Boolean,
    onMarkerCleared: () -> Unit,
    modifier: Modifier = Modifier,
    /** Para secretos que no son contraseñas, como la clave API de SABnzbd. */
    label: String? = null
) {
    val mostrandoMarcador = hasSaved && showingSavedMarker
    var aLaVista by remember { mutableStateOf(false) }

    Column(modifier) {
        OutlinedTextField(
            value = if (mostrandoMarcador) MARCADOR else value,
            onValueChange = { nuevo ->
                if (mostrandoMarcador) {
                    onMarkerCleared()
                    // Lo que acabe de teclear es el principio de la nueva contraseña; lo
                    // que había era el marcador, no texto suyo.
                    onValueChange(nuevo.removePrefix(MARCADOR))
                } else {
                    onValueChange(nuevo)
                }
            },
            label = { Text(label ?: stringResource(R.string.field_password)) },
            singleLine = true,
            // El marcador nunca se enseña: son puntos de relleno, no la contraseña
            // guardada, y verlos en claro solo confundiría.
            visualTransformation = if (aLaVista) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            // El ojo solo aparece cuando hay algo escrito por el usuario. Con el marcador
            // a la vista no tendría nada que enseñar: son puntos de relleno, no la
            // contraseña guardada.
            trailingIcon = if (mostrandoMarcador) {
                null
            } else {
                {
                    IconButton(onClick = { aLaVista = !aLaVista }) {
                        Icon(
                            if (aLaVista) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            stringResource(
                                if (aLaVista) R.string.password_hide else R.string.password_show
                            ),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        if (hasSaved) {
            Text(
                stringResource(
                    if (mostrandoMarcador) R.string.password_saved else R.string.password_will_change
                ),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}

/** Ocho caracteres cualesquiera: la transformación de contraseña los pinta como puntos. */
private const val MARCADOR = "........"

/** Diálogo para pedir un texto corto: nombres de grupo y poco más. */
@Composable
fun TextPromptDialog(
    title: String,
    initialValue: String,
    onCancel: () -> Unit,
    onAccept: (String) -> Unit
) {
    var texto by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = texto,
                onValueChange = { texto = it },
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true,
                // El borde de serie casi no se ve sobre el fondo del diálogo: el campo
                // parecía no existir hasta que lo tocabas.
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(enabled = texto.isNotBlank(), onClick = { onAccept(texto.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        }
    )
}
