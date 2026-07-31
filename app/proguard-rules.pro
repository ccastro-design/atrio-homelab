# Reglas para la compilación de publicación (R8).
#
# Sin esto la compilación ni siquiera termina: la librería de almacenamiento cifrado
# —androidx.security, que por debajo usa Google Tink— referencia anotaciones que solo
# existen al compilar, y R8 se detiene al no encontrarlas. No hacen falta en el móvil,
# así que basta con decirle que no avise.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**

# La configuración se guarda en JSON por el **nombre** de cada campo. Al minificar, esos
# nombres se acortan y el fichero guardado deja de poder leerse: el usuario abriría la
# aplicación y se encontraría su panel vacío. Estas reglas conservan lo que necesita
# kotlinx.serialization para seguir leyendo lo de siempre.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class com.homelab.panel.** {
    *** Companion;
}
-keepclasseswithmembers class com.homelab.panel.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.homelab.panel.**$$serializer { *; }
