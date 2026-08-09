import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Datos de la clave con la que se firma la versión que se publica. Viven en
// `keystore.properties`, que está en `.gitignore` y **nunca** se sube: quien tenga ese
// fichero y la clave puede publicar actualizaciones falsas de la app.
//
// Si el fichero no está —otro ordenador, o una copia del repositorio— la compilación no
// falla: simplemente no hay firma de publicación y `assembleDebug`/`assembleMinificada`
// siguen funcionando igual.
val datosDeFirma = Properties().apply {
    val fichero = rootProject.file("keystore.properties")
    if (fichero.exists()) fichero.inputStream().use { load(it) }
}
val hayClaveDeFirma = datosDeFirma.getProperty("storeFile")?.let { file(it).exists() } == true

android {
    // Paquete del código. Es interno, no lo ve el usuario y no hace falta cambiarlo
    // cuando se decida el nombre comercial.
    namespace = "com.homelab.panel"
    compileSdk = 36

    defaultConfig {
        // Identificador definitivo, fijado el 31/07/2026 antes de empezar a subir a Play.
        // Sin el guion de `ccastro-design`, que no vale en un nombre de paquete.
        //
        // **NO SE PUEDE VOLVER A TOCAR.** Publicado en Google Play, este identificador es
        // la aplicación: cambiarlo no actualiza nada, crea otra aplicación distinta desde
        // cero, y en Play ni siquiera se permite.
        //
        // El paquete del código sigue siendo `com.homelab.panel` (ver `namespace` arriba):
        // es interno, no lo ve nadie y renombrarlo solo serviría para ensuciar el
        // historial.
        applicationId = "io.github.ccastrodesign.atrio"

        minSdk = 26
        // Android 16. Play obliga a apuntar a la versión del año anterior como mucho: desde
        // el 31/08/2026 no se aceptan actualizaciones que apunten a la 35 o anteriores.
        targetSdk = 36
        // Sube en cada subida a Play, incluso a pruebas internas: Play rechaza repetir uno.
        versionCode = 3
        // El que ve la gente, y el que da nombre a la etiqueta de git y al release de
        // GitHub. Sube cuando cambia algo que se nota: dos versiones distintas con el mismo
        // nombre hacen imposible que un tester diga cuál está probando.
        //
        // Dos números y de uno en uno: 1.1, 1.2, 1.3… Nada de «1.15», que se lee como
        // posterior a «1.2». El 2.0 se guarda para un cambio de verdad grande.
        versionName = "1.2"

        // Solo los idiomas del producto: evita arrastrar las traducciones de las
        // librerías de AndroidX a todos los idiomas del mundo.
        resourceConfigurations += listOf("en", "es")
    }

    signingConfigs {
        if (hayClaveDeFirma) {
            create("publicacion") {
                storeFile = file(datosDeFirma.getProperty("storeFile"))
                storePassword = datosDeFirma.getProperty("storePassword")
                keyAlias = datosDeFirma.getProperty("keyAlias")
                keyPassword = datosDeFirma.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Solo si la clave está en esta máquina; si no, `release` sale sin firmar.
            signingConfig = signingConfigs.findByName("publicacion")

            // NO poner aquí `ndk { debugSymbolLevel = ... }` para callar el aviso de Play
            // sobre los símbolos de depuración: **no funciona**. La única librería nativa
            // del paquete, `libandroidx.graphics.path.so`, llega ya compilada y sin
            // símbolos dentro de Compose, y Gradle solo sabe extraerlos del código nativo
            // que compila el propio proyecto. Probado el 31/07/2026: el `.aab` sale igual.

            // Minificada, con sus reglas en `proguard-rules.pro`. Sin esas reglas la
            // compilación ni terminaba, y lo que se guarda en disco dejaba de poder leerse.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        // La misma de publicación, pero firmada con la clave de depuración y con otro
        // identificador para que se instale **al lado** de la de desarrollo.
        //
        // Existe porque la minificación no se puede comprobar compilando: lo que rompe se
        // ve al ejecutar, y la única forma de probarla sin desinstalar la de desarrollo
        // —lo que se llevaría por delante las contraseñas guardadas— es que sean dos
        // aplicaciones distintas para Android.
        //
        //     gradle assembleMinificada
        create("minificada") {
            initWith(getByName("release"))
            applicationIdSuffix = ".min"
            versionNameSuffix = "-min"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Necesario para BuildConfig.DEBUG.
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Los tests corren en el ordenador, sin Android de verdad, así que las
            // llamadas al sistema —`Log.w` y compañía— no existen. Sin esto, cada una
            // lanza «not mocked» y revienta un test que no va de eso.
            isReturnDefaultValues = true
        }
    }
}

// `io.opencensus` es una librería de telemetría que **no usa esta aplicación** y que **no
// entra en el APK**: llega arrastrada por la plataforma de pruebas instrumentadas del
// plugin de Android (`utp-common` → `com.google.testing.platform:launcher`), que se ejecuta
// en el ordenador. Aquí ni siquiera se usa, porque no hay pruebas instrumentadas.
//
// Se excluye porque el analizador de F-Droid recorre **todas** las configuraciones de
// Gradle, incluidas las internas de las herramientas, y la marca como rastreador. La
// alternativa era discutir un falso positivo en cada revisión.
configurations.configureEach {
    exclude(group = "io.opencensus")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    // Control del oscurecido automático del WebView, que algunos Android fuerzan por
    // su cuenta y deja páginas en negro.
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Guardado de la configuración
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Desbloqueo biométrico y almacenamiento cifrado de credenciales
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    // BiometricPrompt necesita una FragmentActivity
    implementation("androidx.fragment:fragment-ktx:1.8.4")

    // Pruebas en el ordenador. Solo se compilan para `gradle test`, no viajan en el APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // El lector de paneles usa `org.json`, que en Android viene con el sistema pero fuera
    // del móvil son clases vacías: sin esto, cualquier prueba del lector de JSON leería
    // siempre un fichero sin nada dentro y pasaría sin comprobar nada.
    testImplementation("org.json:json:20240303")
}
