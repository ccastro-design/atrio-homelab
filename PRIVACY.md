# Privacy Policy — Atrio Homelab

**Last updated: 31 July 2026**

This policy applies to the Android application **Atrio Homelab** ("the app"), published by
**C. Castro** ("the developer").

*Español más abajo — [ver versión en español](#política-de-privacidad--atrio-homelab).*

## In short

**The app collects no data whatsoever.** There are no accounts, no analytics, no
advertising, no tracking, no crash reporting and no servers belonging to the developer.
Nothing you enter into the app is ever sent anywhere except to the servers on your own
network whose addresses you typed in yourself.

## What the app stores, and where

Everything the app saves stays **on your device**, in the app's private storage. It is
never uploaded anywhere.

| What | Where |
| --- | --- |
| Your panel: services, groups, servers, addresses, appearance settings | A file in the app's private storage |
| Passwords and credentials for your services | Encrypted storage (AES-256, key held in the device's hardware-backed keystore) |
| Icons and background images you choose or that the app fetches from your own services | The app's private storage |
| Web sessions and cookies from services you open inside the app | The app's own WebView storage |
| The names of the Wi-Fi networks you register as "home" | Together with the rest of your settings |

Uninstalling the app removes all of it. Android backups are switched off for this app on
purpose (`allowBackup="false"`), so none of this is copied to Google's servers.

## Network connections

The app connects **only to the addresses you enter yourself**: your own services, your NAS,
your download clients. It does this to:

- check whether a service is online,
- fetch a service's own icon (favicon), from that service,
- send links to your download clients (aMule, qBittorrent, Transmission, SABnzbd),
- display a service inside the app's browser tabs.

**The app contacts no third-party service of any kind** — no icon service, no directory, no
update check, no telemetry endpoint.

Two exceptions, both requiring you to tap them first: the "Source code" and "Support the
project" entries open GitHub and Ko-fi **in your own browser**. Once you leave the app,
those sites' privacy policies apply, not this one.

## Content from your own services

When you open one of your services inside the app, the app renders that service's web
interface. Whatever that page loads is under the control of that service, not of this app.
If your own service pulls in fonts, scripts or images from the internet, those requests
happen — exactly as they would in any browser.

## Permissions, and why each one is needed

- **Internet / network state** — to reach your services and to know whether you are
  connected.
- **Wi-Fi state and location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)** — used
  **only to read the name (SSID) of the Wi-Fi network you are connected to**. Since Android
  8.1 that name is only readable by apps holding a location permission; there is no other
  way to obtain it.

  **Your geographic position is never requested, never read and never stored.** The app has
  no use for it and does not ask the system for it.

  The network name matters for your safety. The app keeps two sets of addresses — one for
  home, one for your VPN — and choosing the wrong one is dangerous: deciding "I am at home"
  merely because some device answers at `192.168.1.254` would also be true at a friend's
  house with a machine on that same address, and the app would then send **your saved
  passwords** to their machine. The network name is the reliable signal.

  The permission is requested only when you choose to register your network, and **the app
  works fully without granting it**. The app never learns a network on its own — you add
  every one of them yourself.
- **Biometrics** — optional, and off by default. If you turn it on, the app asks Android to
  confirm it is you. Your fingerprint or face never reaches the app; Android answers only
  yes or no.
- **Installed applications (`queries`)** — so the app can offer you the list of apps in your
  launcher when you choose which one opens a given service. The list is read on your device
  and never leaves it. The broad `QUERY_ALL_PACKAGES` permission is deliberately not used.

## Backups you export yourself

The app can export your configuration to a file you choose. **Exported files never contain
your passwords.** Once a file is on your device or in your cloud, it is under your control
and this policy no longer governs it.

## Children

The app is not directed at children and collects no data from anyone, of any age.

## Changes

If this policy changes, the new version will be published at this same address with a new
date at the top.

## Contact

Questions about this policy: **atrio.homelab.app@proton.me**

---

# Política de privacidad — Atrio Homelab

**Última actualización: 31 de julio de 2026**

Esta política se aplica a la aplicación Android **Atrio Homelab** («la aplicación»),
publicada por **C. Castro** («el desarrollador»).

## En resumen

**La aplicación no recoge ningún dato.** No hay cuentas, ni analítica, ni publicidad, ni
seguimiento, ni informes de errores, ni servidores del desarrollador. Nada de lo que
escribas en la aplicación sale a ninguna parte, salvo a los servidores de tu propia red
cuyas direcciones has escrito tú.

## Qué guarda la aplicación, y dónde

Todo lo que la aplicación guarda se queda **en tu dispositivo**, en su almacenamiento
privado. Nunca se sube a ningún sitio.

| Qué | Dónde |
| --- | --- |
| Tu panel: servicios, grupos, servidores, direcciones y ajustes de apariencia | Un fichero en el almacenamiento privado de la aplicación |
| Las contraseñas y credenciales de tus servicios | Almacenamiento cifrado (AES-256, con la clave guardada en el almacén de claves del propio dispositivo) |
| Los iconos e imágenes de fondo que elijas, y los que la aplicación pide a tus propios servicios | El almacenamiento privado de la aplicación |
| Las sesiones y cookies de los servicios que abras dentro de la aplicación | El almacenamiento del navegador interno |
| Los nombres de las redes WiFi que registres como «casa» | Junto al resto de tus ajustes |

Al desinstalar la aplicación se borra todo. Las copias de seguridad de Android están
desactivadas a propósito (`allowBackup="false"`), así que nada de esto se copia a los
servidores de Google.

## Conexiones de red

La aplicación se conecta **solo a las direcciones que escribes tú**: tus servicios, tu NAS,
tus clientes de descarga. Lo hace para:

- comprobar si un servicio está en línea,
- pedir el icono (favicon) de un servicio, a ese mismo servicio,
- enviar enlaces a tus clientes de descarga (aMule, qBittorrent, Transmission, SABnzbd),
- mostrar un servicio dentro de las pestañas de la aplicación.

**La aplicación no contacta con ningún servicio de terceros**: ni servicios de iconos, ni
directorios, ni comprobaciones de actualización, ni telemetría.

Dos excepciones, y las dos hay que pulsarlas: las entradas «Código fuente» y «Apoyar el
proyecto» abren GitHub y Ko-fi **en tu propio navegador**. En cuanto sales de la
aplicación, se aplican las políticas de privacidad de esos sitios, no esta.

## Contenido de tus propios servicios

Cuando abres uno de tus servicios dentro de la aplicación, lo que se dibuja es la interfaz
web de ese servicio. Lo que esa página cargue depende de ese servicio, no de esta
aplicación. Si tu propio servicio trae tipografías, scripts o imágenes de internet, esas
peticiones se producen, exactamente igual que en cualquier navegador.

## Permisos, y para qué hace falta cada uno

- **Internet y estado de la red**: para llegar a tus servicios y saber si hay conexión.
- **Estado de la WiFi y ubicación (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`)**: se
  usan **únicamente para leer el nombre (SSID) de la red WiFi a la que estás conectado**.
  Desde Android 8.1 ese nombre solo lo puede leer una aplicación que tenga permiso de
  ubicación, y no hay otra vía.

  **Tu posición geográfica no se pide, no se lee y no se guarda nunca.** La aplicación no
  la necesita para nada y no se la pregunta al sistema.

  El nombre de la red importa por tu seguridad. La aplicación guarda dos juegos de
  direcciones —las de casa y las de la VPN— y elegir mal es peligroso: dar por hecho que
  «estoy en casa» solo porque algo responde en `192.168.1.254` también sería cierto en casa
  de un amigo con un equipo en esa misma dirección, y entonces la aplicación le mandaría a
  su máquina **tus contraseñas guardadas**. El nombre de la red es la señal fiable.

  El permiso se pide solo cuando decides registrar tu red, y **la aplicación funciona
  entera sin concederlo**. La aplicación nunca aprende una red por su cuenta: las añades tú.
- **Biometría**: opcional y desactivada de fábrica. Si la activas, la aplicación le pide a
  Android que confirme que eres tú. Tu huella o tu cara no llegan nunca a la aplicación;
  Android responde solo sí o no.
- **Aplicaciones instaladas (`queries`)**: para poder ofrecerte la lista de aplicaciones de
  tu escritorio cuando eliges con cuál se abre un servicio. Esa lista se consulta en tu
  dispositivo y no sale de él. El permiso amplio `QUERY_ALL_PACKAGES` no se usa a propósito.

## Las copias que exportas tú

La aplicación puede exportar tu configuración a un fichero que eliges tú. **Los ficheros
exportados no contienen tus contraseñas.** Una vez que el fichero está en tu dispositivo o
en tu nube, queda bajo tu control y esta política ya no lo cubre.

## Menores

La aplicación no está dirigida a menores y no recoge datos de nadie, tenga la edad que
tenga.

## Cambios

Si esta política cambia, la nueva versión se publicará en esta misma dirección con una
fecha nueva arriba.

## Contacto

Dudas sobre esta política: **atrio.homelab.app@proton.me**
