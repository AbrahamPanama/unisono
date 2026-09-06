# Unísono 0.1 · Versión de prueba personal

Audio de tu Mac a Android por la red local, con reproducción simultánea y transmisión PCM cifrada. App nativa de barra de menú para Apple Silicon; receptor nativo Android 8 o posterior. Preparada para probar con Mac mini M4 y Galaxy S25 Ultra.

## Instalar y conectar

1. Abre `dist/Unisono.app` en la Mac. También puedes arrastrarla a Aplicaciones. Aparece como un icono de onda en la barra superior; no aparece en el Dock.
2. Copia `dist/Unisono-Android.apk` al S25 Ultra y ábrelo allí. Autoriza la instalación desde esa fuente cuando Android lo solicite. Es una compilación personal, firmada con una clave de desarrollo; no está publicada en Play Store.
3. Conecta ambos equipos a la misma red local. Evita una red de invitados que aísle los dispositivos.
4. En el icono de Unísono de la Mac, abre el engranaje o pulsa **Conectar celular**. Escanea el QR con la cámara del S25 y abre el enlace en Unísono. Si la cámara no abre enlaces personalizados, pega el enlace completo en el campo de Android.
5. Pulsa **Conectar** en Android. En la Mac, acepta el permiso de captura de audio si macOS lo solicita. Reproduce música u otro audio en la Mac.
6. Si no comienza tras conceder el permiso por primera vez, desconecta y vuelve a conectar en Android.

El QR y el enlace contienen una clave privada de escucha. **Revocar clave y crear otra** invalida el enlace anterior. La clave se guarda localmente; Android tiene la copia de seguridad de la app desactivada. No hay cuentas, nube, grabaciones de audio ni analítica.

## Controles

- **Detener transmisión** termina la sesión en ambos extremos. La Mac recupera su reproducción normal, sin la reserva de Unísono.
- Cerrar el panel mantiene la transmisión. **Salir de Unísono** cierra la app.
- El deslizador de la Mac cambia el volumen de la reproducción de Unísono en Android. El teléfono también tiene su propio deslizador. No modifica el volumen general del sistema Android.
- **Escuchar también en la Mac** permite elegir reproducción simultánea o solo en el celular.
- Perfiles de reserva: **120 ms**, **250 ms (inicial)** y **500 ms**. La reserva no equivale a la latencia total medida; se suma el comportamiento de la salida de audio y la red.
- **Ajuste de la Mac**: valores positivos retrasan la Mac respecto al celular; valores negativos la adelantan. Cambiar ajustes termina la sesión para que puedas reconectar con los nuevos valores.
- La app intenta reconectar hasta tres veces ante un fallo de red. Una detención explícita desde la Mac no activa esta reconexión.
- Android mantiene una notificación para escuchar con la app en segundo plano. Una llamada u otra app que reclame el audio puede detener la sesión; vuelve a conectar después.

## Calidad y sincronización: alcance real

La transmisión conserva exactamente las muestras PCM que entrega el tap de Core Audio, sin códec con pérdida. Formato: estéreo, flotante de 32 bits little-endian, a la frecuencia de la salida capturada (48 kHz en esta Mac durante la prueba). A 48 kHz son aproximadamente 3,07 Mbps de PCM, más el protocolo.

Esto **no promete salida bit-perfect** desde el archivo original hasta el DAC. El mezclador de macOS puede modificar el audio antes de capturarlo; Android puede mezclar, cambiar frecuencia o procesar la salida. Unísono usa `AudioTimestamp` y pequeños ajustes de velocidad en Android para compensar la diferencia entre relojes: la transmisión es exacta, pero esa corrección modifica la reproducción. El volumen también modifica las muestras reproducidas.

Ambos extremos comparten marcas de tiempo mediante un intercambio de reloj. La Mac programa su reproducción local y Android intenta seguir ese calendario. El desfase que aparece en Configuración es una estimación de Android respecto al calendario, **no una medición acústica entre ambos altavoces**. Se necesita calibrar con el S25 físico para evaluar eco y sincronía real. Bluetooth puede añadir compresión y más latencia; el modo actual no certifica una cadena lossless por Bluetooth.

## Verificación realizada

- Compilación ARM64 de la app Mac y firma local verificable.
- Compilación y firma del APK Android.
- Prueba de interoperabilidad Swift/Java: cifrado AES-GCM, 8192 cuadros estéreo comparados bit a bit, mensajes de reloj, volumen, reconexión y rechazo de clave incorrecta.
- Pruebas del búfer de captura: FIFO, PCM intercalado y planar, cola vacía y desbordamiento acotado.
- Captura real en esta Mac: 719360 cuadros en unos 15 segundos, con señal no nula. Solo se conservaron estadísticas; no se guardó el audio.
- Prueba instrumentada en emulador Android 15: importar enlace, conectar al servidor Mac, reproducir seis segundos con AudioTrack y servicio en primer plano, y desconectar.
- Revisión visual de vistas nativas Mac y pantallas renderizadas de Android. Texto claro sobre fondo oscuro; el texto oscuro está reservado al botón verde claro.

**Pendiente:** instalación y prueba en el S25 Ultra físico, medición acústica de sincronización/latencia, pruebas largas y comprobación con tus audífonos o altavoces. El emulador no representa el rendimiento del S25 ni de tu Wi-Fi. Esta es una primera versión funcional para pruebas, no una versión comercial certificada.

## Si algo falla

- **No conecta:** abre Configuración en la Mac y vuelve a copiar el enlace. La IP puede cambiar al cambiar de red. Si hay varias interfaces, prueba otra IP mostrada al copiar el enlace. Puerto TCP: 45871. Autoriza conexiones entrantes si el firewall de macOS pregunta.
- **Sin sonido:** revisa el permiso de grabación de audio del sistema para Unísono en Privacidad y seguridad de macOS. Confirma que otra app de la Mac está reproduciendo. Algunas fuentes protegidas pueden no permitir captura.
- **Cortes o reconexiones:** prueba 500 ms, acerca el teléfono al router y evita redes de invitados. Si Android suspende la app, revisa sus ajustes de batería.
- **Eco:** mantén ambos altavoces cerca para comparar y ajusta el retraso de la Mac. No se ha garantizado sincronía acústica exacta.
- **Cambio de salida o reposo:** Unísono detiene o reinicia la sesión para evitar seguir con un reloj o dispositivo obsoleto; reconecta si es necesario.

## Código y compilación

`mac/`: AppKit, Core Audio Taps, AVAudioEngine, Network y CryptoKit. `android/`: Activity, servicio de reproducción, AudioTrack y cifrado de Java. `tests/`: pruebas de protocolo y reproducción. `design/`: referencia seleccionada y capturas; las capturas de configuración usan una clave ficticia.

Para Mac: `bash scripts/build-mac.sh`. Requiere las Command Line Tools de Apple y SDK con Core Audio Taps.

Para Android: define `JAVA_HOME` con JDK 17 y `ANDROID_HOME` con un SDK que contenga `platforms;android-35` y `build-tools;35.0.0`, y ejecuta `bash scripts/build-android.sh`. No requiere Android Studio ni Gradle. Conserva `build/development.p12` para que las siguientes compilaciones puedan actualizar la instalación personal existente.

Para las pruebas de protocolo: detén Unísono para liberar el puerto 45871, define `JAVA_HOME` y ejecuta `bash scripts/test.sh`.

Configura las rutas de Java y del SDK según tu entorno. La app de Mac está firmada ad hoc y no está notarizada para distribución pública. Los instaladores se encuentran en la sección Releases del repositorio.
