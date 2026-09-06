# Unísono 0.2.3 · Versión de prueba personal

<img src="design/icon/Unisono-1024.png" width="96" alt="Icono de Unísono: una U y ondas de sonido en verde menta sobre fondo carbón">

Audio de tu Mac a Android por la red local, con reproducción simultánea y transmisión AAC o PCM cifrada. App nativa de barra de menú para Apple Silicon; receptor nativo Android 8 o posterior. Preparada para probar con Mac mini M4 y Galaxy S25 Ultra.

[Descargar la versión de prueba 0.2.3](https://github.com/AbrahamPanama/unisono/releases/tag/v0.2.3)

La versión 0.2.3 permite escribir una reserva compartida de 250 a 1000 ms en la Mac, con 500 ms como valor inicial. AAC equilibrado y PCM respetan una reserva de 250 ms; el perfil Más estable conserva un mínimo de 750 ms. El ajuste separado de sincronización de la Mac rechaza valores fuera de ±100 ms y Android identifica claramente su búfer de salida independiente.

La versión 0.2.2 incorporó el icono aprobado de U y ondas en verde menta: icono de app y plantilla nativa para la barra de menú de Mac, además de iconos adaptativo, temático y de notificación en Android. Aquella publicación mantuvo el comportamiento de audio de 0.2.1.

## Instalar y conectar

1. Abre `dist/Unisono.app` en la Mac. También puedes arrastrarla a Aplicaciones. Aparece como una U con ondas en la barra superior; no aparece en el Dock.
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
- **Reserva de audio** en la Mac: escribe un número entero de **250 a 1000 ms**; el valor inicial es **500 ms**. Pulsa **Aplicar y reconectar** y vuelve a conectar desde Android. Se usa el mayor valor entre la reserva de la Mac y la solicitud del teléfono: AAC equilibrado y PCM permiten 250 ms; Más estable exige al menos 750 ms. Android también puede pedir más reserva durante la recuperación.
- **Sincronización de la Mac**: ajuste separado de **−100 a +100 ms**. Los valores positivos retrasan la Mac respecto al celular; los negativos la adelantan. Solo cambia la reproducción local: no modifica la reserva ni el búfer del teléfono. Los valores inválidos se rechazan en pantalla, sin limitarlos silenciosamente al máximo. Aplicar ajustes válidos termina la sesión para reconectar con los nuevos valores.
- La app intenta reconectar hasta tres veces ante un fallo de red. Una detención explícita desde la Mac no activa esta reconexión.
- Android mantiene una notificación para seguir escuchando al abrir otras apps.
- **Mezclar con otras apps**, activado inicialmente, permite escuchar Unísono junto a Spotify u otro reproductor. Desconecta para cambiarlo. Al desactivarlo, Unísono solicita el foco de audio y se detiene cuando otra app lo reclama.
- Si Android indica modo de llamada, timbre o comunicación, Unísono detiene la sesión; vuelve a conectar después. Las apps de llamadas que no informen ese modo pueden no detectarse. La mezcla con Spotify en el S25 físico todavía requiere prueba.

## Corrección de desconexiones en 0.2.1

Se reprodujeron tres desconexiones en 20 segundos en el S25 Ultra físico con Android 16, sin cortes del búfer de salida. La versión anterior reiniciaba por un desfase de tiempo grande, aunque la reproducción estuviera funcionando. Ahora ese desfase se informa y se corrige gradualmente; por sí solo no provoca una reconexión. Con el cambio, el mismo teléfono permaneció conectado durante 60 de 60 segundos observados, sin desconexiones ni cortes de búfer reportados. La sincronización acústica y las sesiones largas todavía deben comprobarse por separado. En la publicación 0.2.1, esta corrección solo requirió actualizar Android; el binario Mac se mantuvo en 0.2.0.

## Calidad adaptable

En Android, desconecta para elegir el modo:

- **Equilibrado · AAC adaptable:** AAC-LC a 256 kbps inicialmente y reserva de 500 ms con los ajustes iniciales de la Mac; configurable desde 250 ms. Durante la recuperación puede bajar a 160 kbps.
- **Más estable · AAC 160 kbps:** exige una reserva mínima de 750 ms y usa menos tráfico; respeta una reserva mayor de la Mac.
- **Sin pérdida · PCM:** conserva las muestras transmitidas y nunca cambia automáticamente a AAC. Reserva de 500 ms con los ajustes iniciales de la Mac; configurable desde 250 ms.

**AAC tiene pérdida.** El modo elegido se guarda, y los indicadores muestran el formato y la reserva realmente usados. Actualiza ambas apps para usar una reserva de 250 ms; las solicitudes mayores de receptores antiguos se siguen respetando. Si AAC no puede inicializarse, se intenta PCM y se indica en pantalla.

Android comienza con unos 100 ms de audio preparados, agrupa los paquetes PCM pequeños, aumenta su búfer de salida después de cortes, suaviza el volumen y realiza correcciones de reloj más lentas. Tras un fallo o cortes repetidos, añade 250 ms a la reserva realmente negociada, hasta 1000 ms: una sesión de 250 ms puede recuperarse con 500 ms y después 750 ms. La recuperación produce una pausa breve; no se promete un cambio de códec sin interrupción. La Mac y el celular reinician juntos con esa reserva. Cada inicio manual reinicia la recuperación y negocia otra vez según el perfil y la reserva guardada en la Mac.

En versiones anteriores se verificó la recuperación tras una interrupción artificial de 850 ms, tanto con AAC como con PCM. En 0.2.3, el emulador verificó AAC con reserva inicial de 250 ms y su recuperación a 500 ms tras esa interrupción. Una observación breve del S25 físico también confirmó la reserva de 250 ms aplicada, con búfer de salida de 200 ms y sin cortes de salida reportados. Todavía debe comprobarse si estos cambios eliminan los chasquidos del S25 físico. Una señal Wi-Fi fuerte no descarta problemas de programación, búfer, salida de audio o saturación del audio original.

## Calidad y sincronización: alcance real

La **Reserva** de 250–1000 ms define el calendario de reproducción compartido; **no es una medición de latencia de extremo a extremo**. **Búfer de salida** muestra la capacidad efectiva de AudioTrack que informa Android, determinada por el dispositivo y ajustable tras cortes. Por eso **Reserva 250 ms · Búfer de salida 200 ms** puede ser correcto: cambiar la reserva no obliga a cambiar ese búfer. Los 200 ms no miden cuánto audio hay en cola ni una demora acústica adicional que deba sumarse automáticamente a la reserva.

En modo PCM, la transmisión conserva exactamente las muestras PCM que entrega el tap de Core Audio, sin códec con pérdida. Formato: estéreo, flotante de 32 bits little-endian, a la frecuencia de la salida capturada (48 kHz en esta Mac durante la prueba). A 48 kHz son aproximadamente 3,07 Mbps de PCM, más el protocolo.

Esto **no promete salida bit-perfect** desde el archivo original hasta el DAC. El mezclador de macOS puede modificar el audio antes de capturarlo; Android puede mezclar, cambiar frecuencia o procesar la salida. Unísono usa `AudioTimestamp` y pequeños ajustes de velocidad en Android para compensar la diferencia entre relojes: la transmisión es exacta, pero esa corrección modifica la reproducción. El volumen también modifica las muestras reproducidas.

Ambos extremos comparten marcas de tiempo mediante un intercambio de reloj. La Mac programa su reproducción local y Android intenta seguir ese calendario. El desfase que aparece en Configuración es una estimación de Android respecto al calendario, **no una medición acústica entre ambos altavoces**. Se necesita calibrar con el S25 físico para evaluar eco y sincronía real. Bluetooth puede añadir compresión y más latencia; el modo actual no certifica una cadena lossless por Bluetooth.

## Verificación realizada

En 0.2.3 pasaron las pruebas unitarias de reserva personalizada, validación del ajuste local y protocolo. El emulador Android 15 verificó una reserva AAC inicial de 250 ms, codificación/decodificación AAC, segundo plano, mezcla en ambos órdenes de inicio y detención al perder el foco. Una interrupción artificial de 850 ms produjo recuperación a AAC de 160 kbps, reserva de 500 ms y búfer de salida de 100 ms. La vista de ajustes de Mac se revisó con reserva de 250 ms y texto claro legible.

Con la versión normal 0.2.3 instalada en ambos equipos, una sesión real desde la Mac al S25 Ultra con Android 16 usó AAC de 256 kbps. Las 30 muestras de estado recogidas durante 32,2 segundos indicaron conexión activa, reserva de 250 ms, búfer de salida de 200 ms y cero cortes de salida. El último desfase estimado por marcas de tiempo fue de unos +201 ms. La observación confirma la reserva aplicada durante ese intervalo; no demuestra una latencia acústica de 250 ms, sincronización exacta ni estabilidad prolongada.

Los puntos siguientes registran verificaciones de versiones anteriores:

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
- **Puse 250 ms y aparece otra reserva:** usa **Reserva de audio**, no **Sincronización de la Mac**, aplica el ajuste y reconecta. Elige AAC equilibrado o PCM y actualiza ambas apps. Más estable mantiene un mínimo de 750 ms y la recuperación puede aumentar la reserva. Un **Búfer de salida 200 ms** independiente no indica que el ajuste haya fallado.
- **Eco:** mantén ambos altavoces cerca para comparar y ajusta el retraso de la Mac. No se ha garantizado sincronía acústica exacta.
- **Cambio de salida o reposo:** Unísono detiene o reinicia la sesión para evitar seguir con un reloj o dispositivo obsoleto; reconecta si es necesario.

## Código y compilación

`mac/`: AppKit, Core Audio Taps, AVAudioEngine, Network y CryptoKit. `android/`: Activity, servicio de reproducción, AudioTrack y cifrado de Java. `tests/`: pruebas de protocolo y reproducción. `design/`: referencia seleccionada y capturas; las capturas de configuración usan una clave ficticia.

Para Mac: `bash scripts/build-mac.sh`. Requiere las Command Line Tools de Apple y SDK con Core Audio Taps.

Para Android: define `JAVA_HOME` con JDK 17 y `ANDROID_HOME` con un SDK que contenga `platforms;android-35` y `build-tools;35.0.0`, y ejecuta `bash scripts/build-android.sh`. No requiere Android Studio ni Gradle. Conserva `build/development.p12` para que las siguientes compilaciones puedan actualizar la instalación personal existente.

Los iconos PNG e ICNS exportados están incluidos en el repositorio. Para regenerarlos en macOS, solo con dependencias nativas de Mac:

```sh
swift scripts/export-icons.swift
iconutil -c icns build/icons/Unisono.iconset -o mac/Resources/Unisono.icns
```

Para las pruebas de protocolo y reserva: detén Unísono para liberar el puerto 45871, define `JAVA_HOME` y ejecuta `bash scripts/test.sh`. Incluye validación del campo, ajuste local, negociación compartida, mínimos de perfiles y recuperación desde 250 ms. El servidor de prueba utiliza el mismo `LatencySettings` que la app Mac.

Para las pruebas de reproducción, con un emulador Android 15 iniciado y `JAVA_HOME` y `ANDROID_HOME` configurados, ejecuta `bash scripts/test-android.sh`. Instala las apps de prueba únicamente en el emulador y usa una reserva de Mac de 500 ms inicialmente. Para comprobar una reserva personalizada de 250 ms y su recuperación tras una interrupción artificial de 850 ms:

```sh
UNISONO_TEST_RESERVE=250 bash scripts/test-android.sh
UNISONO_TEST_RESERVE=250 UNISONO_JITTER=1 bash scripts/test-android.sh
```

Añade `UNISONO_TEST_QUALITY=lossless` para comprobar PCM, o usa `UNISONO_TEST_QUALITY=stable` para comprobar que su mínimo de 750 ms prevalece sobre una reserva de Mac de 250 ms.

Configura las rutas de Java y del SDK según tu entorno. La app de Mac está firmada ad hoc y no está notarizada para distribución pública. Los instaladores se encuentran en la [publicación 0.2.3](https://github.com/AbrahamPanama/unisono/releases/tag/v0.2.3).
