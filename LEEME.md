# Unísono · Versión de prueba personal

<img src="design/icon/Unisono-1024.png" width="96" alt="Icono de Unísono: una U y ondas de sonido en verde menta sobre fondo carbón">

Audio de tu Mac a Android por la red local, con reproducción simultánea y transmisión AAC, FLAC de 24 bits o PCM Float32 cifrada. App nativa de barra de menú para Apple Silicon; receptor nativo Android 8 o posterior. Preparada para probar con Mac mini M4 y Galaxy S25 Ultra.

[Descargar una versión de prueba](https://github.com/AbrahamPanama/unisono/releases)

La versión 0.3.0 incorpora **Depuración de latencia** en Mac y Android: modos Automático y Manual, controles explícitos, valores numéricos, gráficas, eventos y exportación local JSON. Manual mantiene los ajustes solicitados y el códec para que la recuperación no cambie las condiciones de la prueba. La sección de verificación distingue las pruebas de controles y telemetría de la latencia acústica y la estabilidad prolongada.

La versión 0.2.4, compilación 7, añadió **Sin pérdida · FLAC 24 bits** mediante los códecs nativos de Mac y Android. Primero convierte las muestras Float32 capturadas a enteros de 24 bits; FLAC conserva exactamente esos enteros. **Sin compresión · PCM Float32** sigue disponible para conservar las muestras capturadas originales en la transmisión. En Automático, si FLAC no está disponible, usa PCM; nunca cambia a AAC durante la recuperación. En Manual, un códec no disponible detiene la sesión con un error. La comparación exacta pasó en el emulador y en el S25 físico. La reproducción real en el S25 necesitó aumentar la reserva de 250 a 500 y después a 750 ms, conservando FLAC.

La versión 0.2.3 permitió escribir una reserva compartida de 250 a 1000 ms en la Mac, con 500 ms como valor inicial. AAC equilibrado y PCM pasaron a respetar una reserva de 250 ms; el perfil Más estable mantuvo un mínimo de 750 ms. También separó el ajuste de sincronización de la Mac, rechazó valores fuera de ±100 ms e identificó claramente el búfer de salida de Android. FLAC utiliza la misma política de reserva.

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
- **Reserva de audio** en los ajustes normales de la Mac: escribe un número entero de **250 a 1000 ms**; el valor inicial es **500 ms**. Pulsa **Aplicar y reconectar** y vuelve a conectar desde Android. En Automático se usa el mayor valor entre la reserva de la Mac y la solicitud del teléfono: AAC equilibrado, FLAC y PCM permiten 250 ms; Más estable exige al menos 750 ms. Android también puede pedir más reserva durante la recuperación. Manual utiliza su reserva explícita de 100–2000 ms.
- **Sincronización de la Mac**: ajuste separado de **−100 a +100 ms**. Los valores positivos retrasan la Mac respecto al celular; los negativos la adelantan. Solo cambia la reproducción local: no modifica la reserva ni el búfer del teléfono. Los valores inválidos se rechazan en pantalla, sin limitarlos silenciosamente al máximo. Aplicar ajustes válidos termina la sesión para reconectar con los nuevos valores.
- Hay hasta tres intentos de conexión por inicio. Manual permite desactivar los reintentos o repetir los mismos ajustes. Una detención explícita desde la Mac no activa esta reconexión.
- Android mantiene una notificación para seguir escuchando al abrir otras apps.
- **Mezclar con otras apps**, activado inicialmente, permite escuchar Unísono junto a Spotify u otro reproductor. Desconecta para cambiarlo. Al desactivarlo, Unísono solicita el foco de audio y se detiene cuando otra app lo reclama.
- Si Android indica modo de llamada, timbre o comunicación, Unísono detiene la sesión; vuelve a conectar después. Las apps de llamadas que no informen ese modo pueden no detectarse. La mezcla con Spotify en el S25 físico todavía requiere prueba.

## Depuración de latencia

<img src="design/mac-debug-preview.png" width="900" alt="Ventana nativa de depuración en Mac con texto claro, controles manuales, valores numéricos y gráficas">

*Ejemplo de interfaz con datos simulados e identificados como demostración; no son mediciones de reproducción.*

Abre **Depuración de latencia** desde la Mac o desde el botón junto a los controles de calidad de Android. Actualiza ambas apps para usar Manual. La Mac puede enviar ajustes al receptor Android conectado y compatible; Android también puede guardarlos sin conexión para la próxima sesión. Escribir solo modifica un borrador. **Aplicar y reiniciar audio** valida los valores y reinicia la sesión para que ambos equipos compartan el nuevo calendario.

| Control | Comportamiento en Manual |
|---|---|
| **Reserva de audio** | Entero de **100–2000 ms**, usado exactamente. Los mínimos de perfil y los incrementos de recuperación no lo cambian. |
| **Búfer de salida solicitado** | Entero de **20–500 ms**, solicitado a AudioTrack y mantenido fijo. Android puede imponer un búfer efectivo mayor; se muestran ambos valores. |
| **Precarga solicitada** | Entero de **10–200 ms**, sin superar el búfer solicitado. Es el audio que se prepara antes de iniciar; se muestra la precarga efectiva. |
| **Corregir deriva del reloj** | Activa la corrección gradual de velocidad. Desactívala para mantener 1,0× y observar la deriva. |
| **Reconectar después de un fallo** | Repite reserva, búfer solicitado, precarga, códec y bitrate AAC. Si se desactiva, el fallo detiene la sesión para inspeccionarla. |
| Ajuste de sincronización de la Mac | La ventana de depuración admite **−500 a +500 ms**, solo para la salida local. Positivo retrasa la Mac; negativo la adelanta. Se rechazan combinaciones que no permitan programar la salida a tiempo. |

Manual no aumenta el búfer solicitado tras cortes, no reduce el bitrate AAC y no cambia de códec. Si el códec falla o la Mac no confirma la configuración completa, aparece un error. Volver a **Automático** recupera los ajustes adaptables, las alternativas de códec y la corrección de reloj. Los ajustes normales de la Mac conservan su rango local de ±100 ms; aplicarlos elimina el ajuste ampliado guardado desde depuración.

Para comparar, parte de una sesión que funcione, pulsa **Usar valores actuales**, aplica el borrador Manual y modifica una sola variable por prueba. Mantén el mismo códec y la misma salida. **Pausar vista** / **Pausar gráficos** congela lo mostrado; el audio y el registro de estadísticas continúan. Reanudar muestra los datos recientes. Borrar el historial elimina muestras y eventos sin detener el audio.

Se guardan hasta diez minutos, aproximadamente una muestra por segundo. Los datos no disponibles aparecen como **—** y huecos en las gráficas. Las gráficas de intervalos, decodificación y escritura usan **máximos del último segundo**, para conservar una interrupción breve aunque la última observación sea normal. Los valores numéricos también muestran la observación más reciente. La variación de llegada es una estimación suavizada entre intervalos de mensajes de audio TCP y tiempos de origen; no mide pérdida de paquetes. Captura → llegada incluye codificación y transporte. Decodificación incluye esperar para encolar el resultado. El audio pendiente en salida se estima con muestras escritas y posición de reproducción.

| Medición | Interpretación |
|---|---|
| `reserveMs` | Reserva de reproducción compartida realmente aplicada. |
| `syncMs` | Desfase Android respecto al **objetivo compartido**, sin el ajuste local de la Mac. Positivo indica reproducción posterior a ese objetivo. |
| `syncAgeMs` | Antigüedad de la última marca de reproducción Android válida. Una lectura fallida o más de tres segundos sin una válida hacen que desfase y latencias derivadas aparezcan como no disponibles. |
| `estimatedLatencyMs` | `reserveMs + syncMs`: estimación de captura a marcas de reproducción Android por software, **no latencia acústica medida**. No se suma otra vez la capacidad del búfer. |
| `macRelativeMs` | `syncMs − macTrimMs`: desfase estimado respecto a la salida local programada de la Mac. Solo aparece con reproducción local activa y telemetría reciente; no mide la diferencia acústica entre altavoces. |
| Búfer solicitado/efectivo | Capacidad solicitada a AudioTrack frente a la que informa el dispositivo. Ninguna equivale al audio actualmente en cola ni a una demora extra medida. |
| Cortes actuales/acumulados | Cortes de AudioTrack de la salida actual y total entre reintentos. El contador actual puede reiniciarse al reconectar; los eventos conservan la secuencia de fallos. |

También se muestran RTT y offset de relojes, tráfico y bitrate AAC activo, señal/velocidad de enlace Wi-Fi cuando estén disponibles, colas, reintentos, velocidad de corrección, CPU, memoria Java y métricas de captura, codificación, envío y salida local de la Mac. La CPU puede superar 100 % al utilizar varios núcleos. La velocidad del enlace Wi-Fi no es el tráfico real de audio, y una señal fuerte no identifica la causa de un corte.

Los contadores de bytes y caudal recibido incluyen todos los mensajes descifrados, también los de control. El caudal enviado por la Mac incluye el tamaño adicional del cifrado y de las tramas. Son recuentos diferentes y no tienen por qué coincidir exactamente.

**Exportar JSON** usa el selector de archivos del sistema para guardar estadísticas, ajustes y eventos acotados. Excluye o elimina audio, claves de conexión, direcciones de red y nombres de dispositivos. No sube informes ni graba la transmisión. Las exportaciones de Mac y Android tienen estructuras JSON diferentes; ambas distinguen datos ausentes de valores cero.

## Corrección de desconexiones en 0.2.1

Se reprodujeron tres desconexiones en 20 segundos en el S25 Ultra físico con Android 16, sin cortes del búfer de salida. La versión anterior reiniciaba por un desfase de tiempo grande, aunque la reproducción estuviera funcionando. Ahora ese desfase se informa y se corrige gradualmente; por sí solo no provoca una reconexión. Con el cambio, el mismo teléfono permaneció conectado durante 60 de 60 segundos observados, sin desconexiones ni cortes de búfer reportados. La sincronización acústica y las sesiones largas todavía deben comprobarse por separado. En la publicación 0.2.1, esta corrección solo requirió actualizar Android; el binario Mac se mantuvo en 0.2.0.

## Calidad adaptable

La política inicial y la recuperación de esta sección corresponden a **Automático**. En **Manual** se usa la reserva explícita, los reintentos conservan códec/bitrate y un códec no disponible detiene la sesión.

En Android, desconecta para elegir el modo:

- **Equilibrado · AAC adaptable:** AAC-LC a 256 kbps inicialmente y reserva de 500 ms con los ajustes iniciales de la Mac; configurable desde 250 ms. Durante la recuperación puede bajar a 160 kbps.
- **Más estable · AAC 160 kbps:** exige una reserva mínima de 750 ms y usa menos tráfico; respeta una reserva mayor de la Mac.
- **Sin pérdida · FLAC 24 bits:** comprime sin pérdida el PCM después de convertirlo a enteros de 24 bits. Reserva de 500 ms con los ajustes iniciales de la Mac; configurable desde 250 ms. Puede usar PCM como alternativa; nunca AAC.
- **Sin compresión · PCM Float32:** conserva exactamente las muestras capturadas en la transmisión, sin conversión a 24 bits ni códec con pérdida. Reserva de 500 ms con los ajustes iniciales de la Mac; configurable desde 250 ms.

**AAC tiene pérdida.** El modo elegido se guarda, y los indicadores muestran el formato y la reserva realmente usados. Actualiza ambas apps para usar una reserva de 250 ms; las solicitudes mayores de receptores antiguos se siguen respetando. Si AAC no puede inicializarse, se intenta PCM y se indica en pantalla.

FLAC conserva la frecuencia de muestreo capturada y envía bloques de 1024 cuadros, sin muestras iniciales de preparación del codificador: unos 21,3 ms por bloque a 48 kHz. La tasa de bits depende del contenido, sin objetivo fijo ni proporción de compresión garantizada. No reduce por sí mismo el búfer de salida del teléfono. Si el códec nativo no puede inicializarse, decodificar o entregar la precisión necesaria, se usa PCM Float32 y la pantalla lo identifica como alternativa a FLAC. Una Mac antigua que no reconoce FLAC también responde con PCM.

Android comienza con unos 100 ms de audio preparados, agrupa los paquetes PCM pequeños, aumenta su búfer de salida después de cortes, suaviza el volumen y realiza correcciones de reloj más lentas. Tras un fallo o cortes repetidos, añade 250 ms a la reserva realmente negociada, hasta 1000 ms: una sesión de 250 ms puede recuperarse con 500 ms y después 750 ms. FLAC se mantiene durante la recuperación si el códec funciona, o utiliza PCM si la decodificación no está disponible; FLAC y PCM nunca pasan a AAC. La recuperación produce una pausa breve; no se promete un cambio de códec sin interrupción. La Mac y el celular reinician juntos con esa reserva. Cada nueva conexión iniciada por el usuario reinicia la recuperación y negocia otra vez según el perfil y la reserva guardada en la Mac.

En versiones anteriores se verificó la recuperación tras una interrupción artificial de 850 ms, tanto con AAC como con PCM. En 0.2.3, el emulador verificó AAC con reserva inicial de 250 ms y su recuperación a 500 ms tras esa interrupción. Una observación breve del S25 físico también confirmó la reserva de 250 ms aplicada, con búfer de salida de 200 ms y sin cortes de salida reportados. Todavía debe comprobarse si estos cambios eliminan los chasquidos del S25 físico. Una señal Wi-Fi fuerte no descarta problemas de programación, búfer, salida de audio o saturación del audio original.

## Calidad y sincronización: alcance real

La **Reserva** define el calendario de reproducción compartido: 250–1000 ms con la política automática, o 100–2000 ms en Manual. **No es una medición de latencia de extremo a extremo**. **Búfer de salida** muestra la capacidad efectiva de AudioTrack que informa Android, determinada por el dispositivo; solo Automático aumenta lo solicitado tras cortes. Por eso **Reserva 250 ms · Búfer de salida 200 ms** puede ser correcto: cambiar la reserva no obliga a cambiar ese búfer. Los 200 ms no miden cuánto audio hay en cola ni una demora acústica adicional que deba sumarse automáticamente a la reserva.

En modo PCM, la transmisión conserva exactamente las muestras PCM que entrega el tap de Core Audio, sin códec con pérdida. Formato: estéreo, flotante de 32 bits little-endian, a la frecuencia de la salida capturada (48 kHz en esta Mac durante la prueba). A 48 kHz son aproximadamente 3,07 Mbps de PCM, más el protocolo.

FLAC recibe PCM entero. Unísono convierte cada muestra Float32 finita de forma determinista: limita al intervalo normalizado, multiplica por 8.388.608, redondea al entero más cercano con los empates alejándose de cero y limita al rango de 24 bits con signo. Rechaza muestras no finitas. Esa conversión puede cambiar las muestras Float32 originales; FLAC conserva exactamente los enteros resultantes, sin cambiar la frecuencia de muestreo. Android acepta salida flotante, de 24 bits empaquetados o de 32 bits alineados a la izquierda con la precisión necesaria; rechaza salida de 16 bits en vez de reducir la precisión silenciosamente. PCM Float32 evita la conversión a 24 bits.

Esto **no promete salida bit-perfect** desde el archivo original hasta el DAC. El mezclador de macOS puede modificar el audio antes de capturarlo; Android puede mezclar, cambiar frecuencia o procesar la salida. Unísono usa `AudioTimestamp` y pequeños ajustes de velocidad en Android para compensar la diferencia entre relojes: la transmisión es exacta, pero esa corrección modifica la reproducción. El volumen también modifica las muestras reproducidas.

Ambos extremos comparten marcas de tiempo mediante un intercambio de reloj. La Mac programa su reproducción local y Android intenta seguir ese calendario. El desfase que aparece en Configuración es una estimación de Android respecto al calendario, **no una medición acústica entre ambos altavoces**. Se necesita calibrar con el S25 físico para evaluar eco y sincronía real. Bluetooth puede añadir compresión y más latencia; el modo actual no certifica una cadena lossless por Bluetooth.

## Verificación realizada

En **0.3.0** pasaron las compilaciones nativas y las pruebas de captura, códecs, cifrado, reserva y diagnóstico. En el emulador se verificaron la reserva manual exacta frente a un mínimo mayor de la Mac, rechazo de entradas inválidas, cambios locales/remotos, aplicaciones rápidas consecutivas, valores manuales fijos ante una pausa de 850 ms, rechazo de un servidor antiguo sin confirmación manual, reproducción mientras la vista está pausada, límites del historial y exportaciones sin direcciones ni claves. La prueba detectó y permitió corregir una carrera al detener el audio durante cambios rápidos.

También pasaron FLAC exact24, reproducción en segundo plano, mezcla con otra app en ambos órdenes, pérdida de foco normal y desconexión explícita. La recuperación automática AAC mantuvo el cambio de 256 a 160 kbps y de 250 a 500 ms ante una pausa de 850 ms.

Se instaló el APK normal, sin instrumentación de pruebas y con el certificado existente, en el S25 Ultra con Android 16. Ambas páginas mostraron datos reales, los cambios enviados desde la Mac llegaron al teléfono y la telemetría circuló en ambas direcciones. Una sesión manual con reserva de 250 ms pidió un búfer de 50 ms; la salida Bluetooth reportó 360 ms efectivos. Las gráficas conservaron el historial durante cambios posteriores. Esto verifica los controles y la telemetría, no la latencia acústica ni la ausencia de chasquidos.

En 0.2.4 pasaron la compilación Mac y `scripts/test.sh`. El codificador FLAC nativo conservó exactamente PCM de 24 bits a 8, 44,1, 48, 96 y 192 kHz, incluidos bloques finales cortos, límites, redondeo y entrada ruidosa. También pasaron las pruebas del búfer de captura, PCM cifrado, reserva y política de reproducción.

El emulador Android 15 comparó bit a bit los 49.152 cuadros estéreo sintéticos, incluidos los bits menos significativos, a través de la transmisión FLAC cifrada y salida flotante del decodificador nativo Android. La reproducción FLAC negoció una reserva de 250 ms y pasó segundo plano, mezcla en ambos órdenes de inicio, detención al perder el foco con mezcla desactivada y detención explícita. Una interrupción artificial de 850 ms produjo recuperación manteniendo FLAC y elevando la reserva a 500 ms. Un servidor sin FLAC respondió correctamente con PCM; la alternativa PCM y AAC pasaron las mismas comprobaciones de reproducción, y AAC pasó sus pruebas nativas en ambas tasas de bits.

La app normal de Mac capturó y transmitió 382.976 cuadros FLAC durante unos ocho segundos a 48 kHz con reserva de 250 ms, sin errores de secuencia ni desbordamiento de captura. No se guardó el audio capturado. Ambas compilaciones normales pasaron y la vista de ajustes de Mac mantuvo texto claro legible.

Después de la publicación inicial de 0.2.4 se comprobó también el S25 Ultra físico con Android 16. Su decodificador FLAC nativo pasó la comparación exacta de 24 bits de 49.152 cuadros estéreo sintéticos mediante `adb reverse` hacia el servidor de prueba. Esto verifica la decodificación del teléfono de forma independiente al Wi-Fi y la salida acústica.

Con las versiones normales 0.2.4 instaladas en ambos equipos, una primera observación recogió 30 muestras de estado durante 31,67 segundos. La primera mostró recuperación con reserva de 250 ms después de que la app indicara audio atrasado; las 29 restantes indicaron conexión activa con FLAC y reserva de 500 ms. El búfer de salida fue de 360 ms, los cortes de salida reportados fueron cero dentro de ese intervalo y el último desfase estimado fue de unos +325 ms.

Unos 88 segundos después de iniciar la sesión de 500 ms, la app indicó audio atrasado y un corte de salida, y se recuperó otra vez a 750 ms conservando FLAC. Seis lecturas posteriores mostraron reserva de 750 ms, búfer de salida de 360 ms y desfase de +185 a +212 ms. Sus contadores en cero pertenecían a la sesión nueva, no a toda la prueba. La suma de reserva y desfase dio una estimación por software de unos 935–962 ms entre captura y AudioTimestamp en ese momento. Estas observaciones no identifican la causa del retraso ni demuestran latencia acústica, sincronización exacta, ausencia de chasquidos o estabilidad prolongada.

Los resultados de 0.2.3 siguientes corresponden a AAC/PCM.

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
- **Cortes o reconexiones:** abre **Depuración de latencia**, copia una sesión estable a Manual y cambia una variable cada vez. Revisa máximos del último segundo, colas, cortes acumulados y eventos. Exporta el JSON para comparar. Automático sigue disponible si prefieres recuperación adaptable.
- **Puse 250 ms y aparece otra reserva:** en Automático, Más estable y la recuperación pueden elevarla. Para fijarla, actualiza ambas apps, elige Manual en **Depuración de latencia** y aplica 250 ms. **Sincronización de la Mac** es otro ajuste. Un **Búfer de salida 200 ms** independiente no indica que la reserva haya fallado.
- **Elegí FLAC y aparece PCM:** en Automático, la Mac o el decodificador Android no pudieron ofrecer FLAC de 24 bits. PCM Float32 es la alternativa prevista y no utiliza AAC. Manual muestra el error y conserva la selección de códec, sin alternativa automática.
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

Para las pruebas de protocolo y reserva: detén Unísono para liberar el puerto 45871, define `JAVA_HOME` y ejecuta `bash scripts/test.sh`. Incluye el codificador FLAC nativo, validación del campo, ajuste local, negociación compartida, mínimos de perfiles y recuperación desde 250 ms. El servidor de prueba utiliza el mismo `LatencySettings` que la app Mac.

Para las pruebas de reproducción, con un emulador Android 15 iniciado y `JAVA_HOME` y `ANDROID_HOME` configurados, ejecuta `bash scripts/test-android.sh`. Instala las apps de prueba únicamente en el emulador y usa una reserva de Mac de 500 ms inicialmente. Para comprobar una reserva personalizada de 250 ms y su recuperación tras una interrupción artificial de 850 ms:

```sh
UNISONO_TEST_RESERVE=250 bash scripts/test-android.sh
UNISONO_TEST_RESERVE=250 UNISONO_JITTER=1 bash scripts/test-android.sh
```

Añade `UNISONO_TEST_QUALITY=lossless` para comprobar PCM, o usa `UNISONO_TEST_QUALITY=stable` para comprobar que su mínimo de 750 ms prevalece sobre una reserva de Mac de 250 ms.

Para FLAC, con el mismo emulador y entorno:

```sh
UNISONO_TEST_QUALITY=flac UNISONO_TEST_RESERVE=250 bash scripts/test-android.sh
UNISONO_TEST_QUALITY=flac UNISONO_TEST_RESERVE=250 UNISONO_JITTER=1 bash scripts/test-android.sh
UNISONO_TEST_QUALITY=flac UNISONO_TEST_RESERVE=250 UNISONO_TEST_NO_FLAC=1 bash scripts/test-android.sh
```

El primer comando compara bit a bit 49.152 cuadros estéreo sintéticos a través del codificador Swift, transmisión cifrada y decodificador nativo Android. Incluye silencio, límites de enteros de 24 bits, diferencias entre canales, muestras seudoaleatorias y bits menos significativos; comprueba metadatos, continuidad de paquetes y tiempos, y rechazo de entradas malformadas antes de las pruebas de segundo plano y mezcla. El segundo comprueba recuperación con una reserva mayor manteniendo FLAC. El tercero hace que el servidor rechace FLAC y comprueba la alternativa PCM durante la reproducción. Los comandos solo instalan en el emulador; sus clases de prueba no se incluyen en los APK de publicación.

Las pruebas de integración de depuración usan el mismo ejecutor, exclusivamente en el emulador:

```sh
UNISONO_DEBUG_CASE=manual bash scripts/test-android.sh
UNISONO_DEBUG_CASE=jitter bash scripts/test-android.sh
UNISONO_DEBUG_CASE=legacy bash scripts/test-android.sh
```

Ejercitan valores manuales fijos, validación y estadísticas, ajustes locales/remotos explícitos, interrupciones de conexión y rechazo de un servidor que no confirma Manual. Son comandos de prueba, no mediciones acústicas; los resultados realizados se registran por separado. Sus clases no forman parte del APK normal.

Configura las rutas de Java y del SDK según tu entorno. La app de Mac está firmada ad hoc y no está notarizada para distribución pública. Los instaladores se encuentran en las [publicaciones de prueba](https://github.com/AbrahamPanama/unisono/releases).
