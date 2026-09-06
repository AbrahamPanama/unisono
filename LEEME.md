# Unísono 0.2.1 · Versión de prueba personal

Audio de tu Mac a Android por la red local, con reproducción simultánea y transmisión AAC o PCM cifrada. App nativa de barra de menú para Apple Silicon; receptor nativo Android 8 o posterior. Preparada para probar con Mac mini M4 y Galaxy S25 Ultra.

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
- Reserva mínima en la Mac: **500 ms (inicial)**, **750 ms** y **1000 ms**. Android puede pedir una reserva mayor durante la recuperación; ambos equipos usan el mismo valor. La reserva no equivale a la latencia total medida; se suma el comportamiento de la salida de audio y la red.
- **Ajuste de la Mac**: valores positivos retrasan la Mac respecto al celular; valores negativos la adelantan. Cambiar ajustes termina la sesión para que puedas reconectar con los nuevos valores.
- La app intenta reconectar hasta tres veces ante un fallo de red. Una detención explícita desde la Mac no activa esta reconexión.
- Android mantiene una notificación para seguir escuchando al abrir otras apps.
- **Mezclar con otras apps**, activado inicialmente, permite escuchar Unísono junto a Spotify u otro reproductor. Desconecta para cambiarlo. Al desactivarlo, Unísono solicita el foco de audio y se detiene cuando otra app lo reclama.
- Si Android indica modo de llamada, timbre o comunicación, Unísono detiene la sesión; vuelve a conectar después. Las apps de llamadas que no informen ese modo pueden no detectarse. La mezcla con Spotify en el S25 físico todavía requiere prueba.

## Corrección de desconexiones en 0.2.1

Se reprodujeron tres desconexiones en 20 segundos en el S25 Ultra físico con Android 16, sin cortes del búfer de salida. La versión anterior reiniciaba por un desfase de tiempo grande, aunque la reproducción estuviera funcionando. Ahora ese desfase se informa y se corrige gradualmente; por sí solo no provoca una reconexión. Con el cambio, el mismo teléfono permaneció conectado durante 60 de 60 segundos observados, sin desconexiones ni cortes de búfer reportados. La sincronización acústica y las sesiones largas todavía deben comprobarse por separado. Esta corrección solo requiere actualizar Android; la app Mac 0.2.0 sigue siendo compatible.

## Calidad adaptable

En Android, desconecta para elegir el modo:

- **Equilibrado · AAC adaptable:** AAC-LC a 256 kbps inicialmente y reserva de 500 ms. Durante la recuperación puede bajar a 160 kbps.
- **Más estable · AAC 160 kbps:** comienza con una reserva de 750 ms y menor tráfico.
- **Sin pérdida · PCM:** conserva las muestras transmitidas y nunca cambia automáticamente a AAC.

**AAC tiene pérdida.** El modo elegido se guarda, y los indicadores muestran el formato y la reserva realmente usados. Actualiza ambas apps para disponer de AAC y la reserva compartida adaptable. Si AAC no puede inicializarse, se intenta PCM y se indica en pantalla.

Android comienza con unos 100 ms de audio preparados, agrupa los paquetes PCM pequeños, aumenta su búfer de salida después de cortes, suaviza el volumen y realiza correcciones de reloj más lentas. Tras un fallo o cortes repetidos, reconecta con 250 ms adicionales de reserva, hasta 1000 ms. La recuperación produce una pausa breve; no se promete un cambio de códec sin interrupción. La Mac y el celular reinician juntos con esa reserva. Cada inicio manual restablece el perfil seleccionado.

Se verificó la recuperación tras una interrupción artificial de 850 ms, tanto con AAC como con PCM. Todavía debe comprobarse si estos cambios eliminan los chasquidos del S25 físico. Una señal Wi-Fi fuerte no descarta problemas de programación, búfer, salida de audio o saturación del audio original.

## Calidad y sincronización: alcance real

En modo PCM, la transmisión conserva exactamente las muestras PCM que entrega el tap de Core Audio, sin códec con pérdida. Formato: estéreo, flotante de 32 bits little-endian, a la frecuencia de la salida capturada (48 kHz en esta Mac durante la prueba). A 48 kHz son aproximadamente 3,07 Mbps de PCM, más el protocolo.

Esto **no promete salida bit-perfect** desde el archivo original hasta el DAC. El mezclador de macOS puede modificar el audio antes de capturarlo; Android puede mezclar, cambiar frecuencia o procesar la salida. Unísono usa `AudioTimestamp` y pequeños ajustes de velocidad en Android para compensar la diferencia entre relojes: la transmisión es exacta, pero esa corrección modifica la reproducción. El volumen también modifica las muestras reproducidas.

Ambos extremos comparten marcas de tiempo mediante un intercambio de reloj. La Mac programa su reproducción local y Android intenta seguir ese calendario. El desfase que aparece en Configuración es una estimación de Android respecto al calendario, **no una medición acústica entre ambos altavoces**. Se necesita calibrar con el S25 físico para evaluar eco y sincronía real. Bluetooth puede añadir compresión y más latencia; el modo actual no certifica una cadena lossless por Bluetooth.

## Verificación realizada

- Compilación ARM64 de la app Mac y firma local verificable.
- Compilación y firma del APK Android.
- Prueba de interoperabilidad Swift/Java: cifrado AES-GCM, 8192 cuadros estéreo comparados bit a bit, mensajes de reloj, volumen, reconexión y rechazo de clave incorrecta.
- Pruebas del búfer de captura: FIFO, PCM intercalado y planar, cola vacía y desbordamiento acotado.
- Captura real en esta Mac: 719360 cuadros en unos 15 segundos, con señal no nula. Solo se conservaron estadísticas; no se guardó el audio.
- Prueba instrumentada en emulador Android 15: decodificación AAC nativa a 256 y 160 kbps con comprobación de alineación y señal sintética, reproducción cifrada en segundo plano, mezcla con otra app de música en ambos órdenes de inicio, dos pistas activas sin silenciamiento en el mezclador, detención al perder foco con la mezcla desactivada y desconexión explícita.
- Recuperación tras interrupciones artificiales de 850 ms: reserva aumentada a 750 ms, con AAC a 160 kbps o manteniendo PCM sin pérdida.
- Captura real de la Mac codificada a AAC durante unos ocho segundos, sin guardar audio.
- Revisión visual de vistas nativas Mac y pantallas renderizadas de Android. Capturas del popover real idénticas con apariencia anfitriona clara y oscura. Texto claro sobre fondo oscuro; el texto oscuro está reservado al botón verde claro.

**Pendiente:** pruebas más largas y acústicas en el S25 Ultra físico, medición acústica de sincronización/latencia, pruebas largas y comprobación con tus audífonos o altavoces. El emulador no representa el rendimiento del S25 ni de tu Wi-Fi. Esta es una primera versión funcional para pruebas, no una versión comercial certificada.

## Si algo falla

- **No conecta:** abre Configuración en la Mac y vuelve a copiar el enlace. La IP puede cambiar al cambiar de red. Si hay varias interfaces, prueba otra IP mostrada al copiar el enlace. Puerto TCP: 45871. Autoriza conexiones entrantes si el firewall de macOS pregunta.
- **Sin sonido:** revisa el permiso de grabación de audio del sistema para Unísono en Privacidad y seguridad de macOS. Confirma que otra app de la Mac está reproduciendo. Algunas fuentes protegidas pueden no permitir captura.
- **Cortes o reconexiones:** prueba **Más estable · AAC 160 kbps** o una reserva mínima de 1000 ms, acerca el teléfono al router y evita redes de invitados. Si Android suspende la app, revisa sus ajustes de batería.
- **Eco:** mantén ambos altavoces cerca para comparar y ajusta el retraso de la Mac. No se ha garantizado sincronía acústica exacta.
- **Cambio de salida o reposo:** Unísono detiene o reinicia la sesión para evitar seguir con un reloj o dispositivo obsoleto; reconecta si es necesario.

## Código y compilación

`mac/`: AppKit, Core Audio Taps, AVAudioEngine, Network y CryptoKit. `android/`: Activity, servicio de reproducción, AudioTrack y cifrado de Java. `tests/`: pruebas de protocolo y reproducción. `design/`: referencia seleccionada y capturas; las capturas de configuración usan una clave ficticia.

Para Mac: `bash scripts/build-mac.sh`. Requiere las Command Line Tools de Apple y SDK con Core Audio Taps.

Para Android: define `JAVA_HOME` con JDK 17 y `ANDROID_HOME` con un SDK que contenga `platforms;android-35` y `build-tools;35.0.0`, y ejecuta `bash scripts/build-android.sh`. No requiere Android Studio ni Gradle. Conserva `build/development.p12` para que las siguientes compilaciones puedan actualizar la instalación personal existente.

Para las pruebas de protocolo: detén Unísono para liberar el puerto 45871, define `JAVA_HOME` y ejecuta `bash scripts/test.sh`.

Para las pruebas de reproducción, con un emulador Android 15 iniciado, ejecuta `bash scripts/test-android.sh`. Instala las apps de prueba únicamente en el emulador. `UNISONO_JITTER=1 bash scripts/test-android.sh` comprueba la recuperación AAC; añade `UNISONO_TEST_QUALITY=lossless` para comprobar PCM.

Configura las rutas de Java y del SDK según tu entorno. La app de Mac está firmada ad hoc y no está notarizada para distribución pública. Los instaladores se encuentran en la sección Releases del repositorio.
