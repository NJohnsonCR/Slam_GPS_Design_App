# Slam_GPS_Design_App

Aplicación Android que graba video, IMU y GPS con marcas de tiempo en un mismo reloj, y transmite el video y las mediciones en vivo a una PC por la red local. Es la fuente de datos del sistema de SLAM visual con GPS del repositorio `Slam_GPS_Design`.

Está basada en [MARS Logger](https://github.com/OSUPCVLab/marslogger_android), del PCV Lab de Ohio State University, cuya historia está integrada en este repositorio.

## Requisitos

- JDK 17. Con JDK 21, o con el que incluye Android Studio, el proyecto no compila: usa Gradle 7.5 y Android Gradle Plugin 7.4.2.
- Android SDK. El plugin descarga las plataformas que falten.
- Un teléfono con Android 6.0 o superior. Probado en un Galaxy S23 con Android 16.

## Compilar e instalar

```bash
JAVA_HOME=/ruta/al/jdk-17 ./gradlew assembleFdroidDebug
adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk
```

El teléfono tiene que estar desbloqueado durante la instalación. Si no, Play Protect la rechaza con `INSTALL_FAILED_VERIFICATION_FAILURE`.

En Android Studio, el JDK se elige en *Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK*.

## Grabaciones

Las grabaciones se inician desde la pantalla de video. Cada una se guarda en una carpeta con la fecha y la hora:

```
/sdcard/Android/data/edu.osu.pcv.marslogger/files/data/<aaaa_MM_dd_HH_mm_ss>/
```

```bash
adb pull /sdcard/Android/data/edu.osu.pcv.marslogger/files/data/<carpeta>
```

| Archivo | Contenido |
|---|---|
| `movie.mp4` | Video H.264 en horizontal, derecho con el teléfono en horizontal y la cámara a la izquierda. La resolución se elige en los ajustes de la app. |
| `frame_timestamps.txt` | Instante de exposición y hora Unix de cada frame. |
| `movie_metadata.csv` | Por frame: distancia focal en píxeles del video (`fx`, `fy`), exposición, ISO y enfoque. |
| `gyro_accel.csv` | Giroscopio y acelerómetro. |
| `location.csv` | Latitud, longitud, altitud y velocidad del GPS. |
| `edge_epochs.txt` | Qué reloj usa la cámara, y los dos relojes al inicio y al final de la grabación. |

### Sincronización

Cuando `edge_epochs.txt` indica `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` (es el caso del Galaxy S23), la primera columna de todos los archivos está en el mismo reloj, el de `SystemClock.elapsedRealtimeNanos()`. Las filas de cámara, IMU y GPS se alinean directamente por esa columna.

La columna `Unix time` sirve como referencia horaria pero no para alinear. En los archivos de cámara es la hora en que el frame llegó a la app, no la de captura: en el Galaxy S23 queda entre 60 y 75 ms desfasada respecto de la de la IMU y el GPS.

Los frames no siempre están separados exactamente por 1/30 s: la cámara corre el instante de captura cuando la exposición automática cambia de valor. Hay que usar la marca de tiempo de cada frame.

Los primeros frames de cada `movie.mp4`, unos 0.2 s, no muestran la imagen que corresponde a su marca de tiempo, porque el codificador tarda en ponerse al día al empezar a grabar. Desde ahí, cada frame coincide con su marca.

### Orientación del video

Los píxeles de `movie.mp4` están guardados en vertical (720x1280), con una marca de rotación de 270 grados. Los reproductores y OpenCV la aplican al leer el archivo y entregan la imagen en horizontal (1280x720), en la orientación del sensor. Es la misma orientación de la transmisión en vivo, y la que corresponde a `fx` y `fy` de `movie_metadata.csv`.

## Transmisión en vivo

El teléfono y la PC tienen que estar en la misma red, por ejemplo con el teléfono como punto de acceso. El teléfono espera las conexiones y la PC se conecta a él. La pantalla de video muestra la IP del teléfono y, para cada transmisión, la PC conectada, lo enviado y lo descartado.

El teléfono transmite siempre que haya una PC conectada, grabe o no. La grabación se controla aparte, desde la PC o con el botón del teléfono, y así hay dos modos de uso:

- **Grabar y transmitir:** la PC recibe en vivo y el teléfono guarda una copia completa en disco.
- **Solo transmitir:** la PC recibe en vivo y el teléfono no ocupa espacio.

### Video (TCP, puerto 5000)

Mientras la pantalla de video está abierta, grabando o no, la app envía cada frame como JPEG a la PC conectada. Al conectarse, la PC recibe primero una línea con los parámetros de la cámara, en píxeles de la imagen transmitida:

```
HELLO,<ancho>,<alto>,<fx>,<fy>,<cx>,<cy>,<fps>
```

Después, por cada frame, un encabezado de 20 bytes seguido del JPEG:

| Campo | Bytes | Tipo |
|---|---|---|
| Longitud del JPEG | 4 | entero sin signo, big-endian |
| Instante de captura, reloj de `elapsedRealtimeNanos()` (ns) | 8 | entero sin signo, big-endian |
| Instante de captura, hora Unix (ns) | 8 | entero sin signo, big-endian |

- Los frames no se encolan: si llega uno nuevo mientras el anterior se sigue enviando, el nuevo se descarta. Una red lenta baja la tasa de frames pero no aumenta el retraso.
- Se atiende a una PC a la vez; una conexión nueva reemplaza a la anterior.
- La imagen tiene el tamaño del video y la orientación del sensor. Queda derecha con el teléfono en horizontal y la cámara a la izquierda.
- Mientras se graba, cada frame transmitido es el mismo que se guarda en `movie.mp4` con la misma marca de tiempo.
- El teléfono no corrige la distorsión del lente, y el `HELLO` no la incluye. En el Galaxy S23, `LENS_DISTORTION` es `[0.0903, -0.1210, 0.0544, 0, 0]` en el orden de Android (k1, k2, k3, p1, p2).

Para ver el video desde la PC (requiere OpenCV):

```bash
python tools/video_receiver.py <IP_del_teléfono>
```

Por cable USB, sin WiFi:

```bash
adb forward tcp:5000 tcp:5000
python tools/video_receiver.py 127.0.0.1
```

### GPS (UDP, puerto 5001)

La app envía cada posición en el formato de las filas de `location.csv`:

```
GPS,<t_ns>,<lat>,<lon>,<alt>,<speed>,<unix_ns>
```

La PC inicia la conexión enviando el texto `SUBSCRIBE` al puerto 5001 del teléfono, y lo repite cada 2 segundos. El teléfono responde `SUBSCRIBED` y su estado (ver abajo), y envía las mediciones a la última dirección suscrita. Si pasan 5 segundos sin recibir `SUBSCRIBE`, la pantalla muestra a la PC como inactiva.

Para probar la recepción desde la PC:

```bash
python3 tools/udp_gps_receiver.py <IP_del_teléfono>
```

### Control de la grabación (UDP, puerto 5001)

La PC inicia y detiene la grabación enviando `START` o `STOP` al mismo puerto. Tienen el mismo efecto que el botón de la pantalla de video, que sigue funcionando. El teléfono responde a cada orden con su estado:

```
STATE,RECORDING,<carpeta>
STATE,IDLE
```

`<carpeta>` es el nombre de la carpeta de la grabación en el teléfono. `IDLE` significa que no está grabando; la transmisión sigue. El teléfono también envía su estado al suscriptor cuando la grabación se inicia o detiene con el botón, y junto con cada `SUBSCRIBED`.

- Una orden puede perderse en la red. La PC la repite hasta recibir el estado esperado; repetirla no tiene efecto si el teléfono ya está en ese estado.
- Si la app no está en primer plano en la pantalla de video, las órdenes se ignoran y el teléfono responde con su estado actual.
- Un programa que también recibe las mediciones debe enviar las órdenes desde el mismo socket con el que se suscribe.

```bash
python3 tools/phone_control.py <IP_del_teléfono> start
python3 tools/phone_control.py <IP_del_teléfono> stop
```

## Cambios respecto de MARS Logger

- `location.csv` reemplaza a `gps.csv` y `all_gps.csv`. Las posiciones usan el reloj de la cámara y la IMU en lugar de la hora del sistema en milisegundos, e incluyen la velocidad.
- Se solicitan actualizaciones de ubicación cada 100 ms en lugar de cada 1000 ms.
- `fx` y `fy` en `movie_metadata.csv` se escalan a la resolución del video cuando el teléfono expone su calibración de fábrica. Antes se escribían en píxeles del sensor.
- Transmisión del video por TCP y de las posiciones por UDP, con el estado de cada conexión en la pantalla de video.
- Inicio y detención de la grabación desde la PC.
- `gyro_accel.csv` ya no incluye el magnetómetro.
- `movie.mp4` lleva una marca de rotación para reproducirse en horizontal.
- Cálculo del punto principal (`cx`, `cy`) a partir de la calibración de fábrica, para el `HELLO`.
- Las actualizaciones de ubicación se detienen al salir de la pantalla de video.

## Estructura

```
app/src/main/java/edu/osu/pcv/marslogger/
    *.java        MARS Logger: captura, sensores e interfaz
    gles/         MARS Logger: renderizado OpenGL
    streaming/    transmisión a la PC
tools/            scripts para la PC
```

Los archivos de MARS Logger se mantienen en su ubicación original para poder seguir incorporando los cambios del proyecto original:

```bash
git remote add upstream https://github.com/OSUPCVLab/marslogger_android.git
git pull upstream main
```

## Licencia

GPL-3.0, heredada de MARS Logger. El repositorio de la app de MARS Logger no incluye archivo de licencia; el `LICENSE` de este repositorio es el de [mobile-ar-sensor-logger](https://github.com/OSUPCVLab/mobile-ar-sensor-logger), el proyecto que la contiene.
