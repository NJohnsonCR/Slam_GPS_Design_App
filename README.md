# Slam_GPS_Design_App

Aplicación Android que graba video, IMU y GPS con marcas de tiempo en un mismo reloj, y transmite las mediciones en vivo a una PC por la red local. Es la fuente de datos del sistema de SLAM visual con GPS del repositorio `Slam_GPS_Design`.

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
| `movie.mp4` | Video H.264. La resolución se elige en los ajustes de la app. |
| `frame_timestamps.txt` | Instante de exposición y hora Unix de cada frame. |
| `movie_metadata.csv` | Por frame: distancia focal en píxeles del video (`fx`, `fy`), exposición, ISO y enfoque. |
| `gyro_accel.csv` | Giroscopio, acelerómetro y magnetómetro. |
| `location.csv` | Latitud, longitud, altitud y velocidad del GPS. |
| `edge_epochs.txt` | Qué reloj usa la cámara, y los dos relojes al inicio y al final de la grabación. |

### Sincronización

Cuando `edge_epochs.txt` indica `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` (es el caso del Galaxy S23), la primera columna de todos los archivos está en el mismo reloj, el de `SystemClock.elapsedRealtimeNanos()`. Las filas de cámara, IMU y GPS se alinean directamente por esa columna.

La columna `Unix time` sirve como referencia horaria pero no para alinear: en el Galaxy S23, la de los frames queda entre 60 y 75 ms desfasada respecto de la de la IMU y el GPS.

## Transmisión en vivo

Mientras graba, la app envía cada fila de `location.csv` por UDP al puerto 5001:

```
GPS,<t_ns>,<lat>,<lon>,<alt>,<speed>,<unix_ns>
```

La PC inicia la conexión enviando el texto `SUBSCRIBE` al puerto 5001 del teléfono, y lo repite cada 2 segundos. El teléfono responde `SUBSCRIBED` y envía las mediciones a la última dirección suscrita. Si pasan 5 segundos sin recibir `SUBSCRIBE`, la pantalla muestra a la PC como inactiva.

El teléfono y la PC tienen que estar en la misma red, por ejemplo con el teléfono como punto de acceso. La pantalla de video muestra la IP del teléfono, el estado de la conexión y cuántas líneas se enviaron y descartaron.

Para probar la recepción desde la PC:

```bash
python3 tools/udp_gps_receiver.py <IP_del_teléfono>
```

## Cambios respecto de MARS Logger

- `location.csv` reemplaza a `gps.csv` y `all_gps.csv`. Las posiciones usan el reloj de la cámara y la IMU en lugar de la hora del sistema en milisegundos, e incluyen la velocidad.
- Se solicitan actualizaciones de ubicación cada 100 ms en lugar de cada 1000 ms.
- `fx` y `fy` en `movie_metadata.csv` se escalan a la resolución del video cuando el teléfono expone su calibración de fábrica. Antes se escribían en píxeles del sensor.
- Transmisión de las posiciones por UDP y estado de la conexión en la pantalla de video.
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
