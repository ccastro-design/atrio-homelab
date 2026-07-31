package com.homelab.panel

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun PaginaAcercaDe() {
    val context = LocalContext.current

    Column(Modifier.padding(top = 22.dp, bottom = 28.dp)) {

        // Cabecera: el icono y el nombre, centrados. Es la carta de presentación de la
        // aplicación y lo primero que mira quien viene a comprobar de quién se fía.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // El icono se pide al sistema en vez de pintar el recurso: `painterResource`
            // no sabe con un icono adaptativo, que no es ni un vector ni una imagen sino
            // dos capas que compone el lanzador.
            ExternalApps.icon(context, context.packageName)?.let { icono ->
                Image(
                    bitmap = icono,
                    contentDescription = null,
                    modifier = Modifier
                        .size(72.dp)
                        // Viene cuadrado: sin recortar no se parece al que el usuario
                        // tiene en el escritorio.
                        .clip(RoundedCornerShape(50))
                )
            }
            Text(
                stringResource(R.string.app_full_name),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
            )
            Text(
                stringResource(R.string.about_what),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
                modifier = Modifier.padding(top = 14.dp)
            )
        }

        TextoDeAcercaDe(
            titulo = stringResource(R.string.about_section_privacy),
            cuerpo = stringResource(R.string.about_privacy)
        )
        if (Proyecto.PRIVACIDAD.isNotBlank()) {
            ClickableRow(title = stringResource(R.string.about_privacy_policy)) {
                Proyecto.abrir(context, Proyecto.PRIVACIDAD)
            }
        }

        TextoDeAcercaDe(
            titulo = stringResource(R.string.about_section_code),
            cuerpo = stringResource(R.string.about_licence)
        )
        if (Proyecto.CODIGO.isNotBlank()) {
            ClickableRow(
                title = stringResource(R.string.about_source),
                subtitle = stringResource(R.string.about_source_sub)
            ) {
                Proyecto.abrir(context, Proyecto.CODIGO)
            }
        }

        // El apoyo tiene su propia entrada en el menú de Ajustes: repetirlo aquí sería
        // pedirlo dos veces en la misma pantalla de dos toques.

        TextoDeAcercaDe(
            titulo = stringResource(R.string.about_section_third_party),
            cuerpo = stringResource(R.string.about_third_party)
        )
    }
}

/**
 * Apoyar el proyecto: donación suelta o patrocinio recurrente.
 *
 * **No se promete nada a cambio**, ni aquí ni en las páginas de destino. Google solo
 * permite enlazar donaciones desde una aplicación si quien dona no recibe nada por ello;
 * en cuanto se ofrece una función extra, una insignia o quitar anuncios, hay que pasar por
 * su pasarela y su 15 %.
 */
@Composable
internal fun PaginaDeApoyo() {
    val context = LocalContext.current

    Column(Modifier.padding(top = 12.dp, bottom = 24.dp)) {
        Text(
            stringResource(R.string.support_intro),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            modifier = Modifier.padding(horizontal = 18.dp)
        )

        if (Proyecto.DONACION.isNotBlank()) {
            TituloDeSeccion(
                stringResource(R.string.support_kofi_title),
                Modifier.padding(horizontal = 18.dp)
            )
            Text(
                stringResource(R.string.support_kofi_intro),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp)
            )
            Spacer(Modifier.height(8.dp))
            ClickableRow(
                title = stringResource(R.string.support_kofi),
                subtitle = stringResource(R.string.support_kofi_help)
            ) {
                Proyecto.abrir(context, Proyecto.DONACION)
            }
        }

        if (Proyecto.PATROCINIO.isNotBlank()) {
            TituloDeSeccion(
                stringResource(R.string.support_github_title),
                Modifier.padding(horizontal = 18.dp)
            )
            Spacer(Modifier.height(4.dp))
            ClickableRow(
                title = stringResource(R.string.support_sponsors),
                subtitle = stringResource(R.string.support_sponsors_help)
            ) {
                Proyecto.abrir(context, Proyecto.PATROCINIO)
            }
        }

        Text(
            stringResource(R.string.support_note),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 18.dp)
        )
    }
}

/** Sección de «Acerca de»: su título y un párrafo corto. */
@Composable
private fun TextoDeAcercaDe(titulo: String, cuerpo: String) {
    TituloDeSeccion(titulo, Modifier.padding(horizontal = 18.dp))
    Text(
        cuerpo,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp)
    )
}
