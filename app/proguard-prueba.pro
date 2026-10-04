# Solo para la versión de prueba (`minificada`), que es la que escribe el registro.
#
# Sin esto el registro dice «b9 ← SocketException» en vez de «ServidorColgo ← …», y un cierre
# inesperado sale sin números de línea. El resto se sigue cifrando igual que en la publicada,
# que es lo que esta versión sirve para probar.
-keepnames class * extends java.lang.Throwable
-keepattributes SourceFile,LineNumberTable
