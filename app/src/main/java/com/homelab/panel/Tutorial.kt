package com.homelab.panel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Una pantalla del tutorial: su icono, su título y una explicación de dos líneas. */
private data class Paso(val icono: ImageVector, val titulo: Int, val texto: Int)

private val PASOS = listOf(
    Paso(Icons.Default.Widgets, R.string.tour_1_title, R.string.tour_1_body),
    Paso(Icons.Default.TravelExplore, R.string.tour_2_title, R.string.tour_2_body),
    Paso(Icons.Default.VpnKey, R.string.tour_3_title, R.string.tour_3_body),
    Paso(Icons.Default.Download, R.string.tour_4_title, R.string.tour_4_body),
    Paso(Icons.Default.Lock, R.string.tour_5_title, R.string.tour_5_body),
    // El permiso de ubicación asusta con razón en una aplicación de este tipo, así que se
    // explica antes de pedirlo y no cuando salte el diálogo del sistema.
    Paso(Icons.Default.Shield, R.string.tour_6_title, R.string.tour_6_body)
)

/**
 * Presentación de la aplicación, la primera vez que se abre.
 *
 * Existe porque casi nada de lo que hace se adivina mirando el panel: que los servicios
 * se abren dentro, que una máquina puede tener dos direcciones, o que un enlace de
 * descarga se puede mandar al servidor. Sin contarlo, se descubre por accidente o no se
 * descubre.
 *
 * Se puede saltar desde la primera pantalla y volver a ver desde Ajustes: nadie debería
 * quedarse atrapado en una presentación.
 */
@Composable
fun Tutorial(onFinish: () -> Unit) {
    val estado = rememberPagerState { PASOS.size }
    val alcance = rememberCoroutineScope()
    val ultima = estado.currentPage == PASOS.lastIndex

    // El botón atrás del sistema cierra el tutorial, no la aplicación.
    BackHandler { onFinish() }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // «Saltar» arriba y a la vista desde el primer momento: quien ya sabe de qué va
        // no tiene que deslizar cinco pantallas para entrar.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onFinish) {
                Text(stringResource(if (ultima) R.string.tour_close else R.string.tour_skip))
            }
        }

        HorizontalPager(state = estado, modifier = Modifier.weight(1f)) { indice ->
            PaginaDelTutorial(PASOS[indice])
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Puntos(actual = estado.currentPage, total = PASOS.size, modifier = Modifier.weight(1f))

            Button(
                onClick = {
                    if (ultima) {
                        onFinish()
                    } else {
                        alcance.launch { estado.animateScrollToPage(estado.currentPage + 1) }
                    }
                }
            ) {
                Text(stringResource(if (ultima) R.string.tour_start else R.string.tour_next))
            }
        }
    }
}

@Composable
private fun PaginaDelTutorial(paso: Paso) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                paso.icono,
                null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(52.dp)
            )
        }

        Text(
            stringResource(paso.titulo),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 28.dp)
        )
        Text(
            stringResource(paso.texto),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

/** Por dónde va: un punto por pantalla, relleno el de la actual. */
@Composable
private fun Puntos(actual: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        repeat(total) { indice ->
            Box(
                Modifier
                    .size(if (indice == actual) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(
                        MaterialTheme.colorScheme.primary
                            .copy(alpha = if (indice == actual) 1f else 0.25f)
                    )
            )
        }
    }
}
