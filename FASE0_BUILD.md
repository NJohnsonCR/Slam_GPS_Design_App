# Fase 0 — MARS Logger compilando y corriendo

**Última actualización:** 19 de septiembre de 2026
**Puerta (§8 de `CONTEXTO_APP_ANDROID.md`):** BUILD SUCCESSFUL + los 4 archivos de salida.
**Estado:** ✅ **CERRADA, sin modificar una sola línea de código.**

---

## 1. Cómo compilar

```bash
JAVA_HOME=$HOME/.jdks/jdk-17.0.20.1+1 ./gradlew assembleFdroidDebug
```

APK: `app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk`

**El `JAVA_HOME` no es opcional.** No hay JDK en el sistema, y el JBR que trae
Android Studio 2026.1.4 es JDK 25, que el AGP 7.4.2 de este proyecto no soporta.
**Tiene que ser JDK 17**, no 21: con 21 el Gradle 7.5 del wrapper tampoco arranca.

Para abrirlo en Android Studio: *File → Project Structure → SDK Location → JDK*
apuntando a `~/.jdks/jdk-17.0.20.1+1`.

### Instalar en el teléfono

```bash
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/fdroid/debug/app-fdroid-debug.apk
```

⚠️ **El teléfono tiene que estar desbloqueado.** Con la pantalla bloqueada el
install falla con `INSTALL_FAILED_VERIFICATION_FAILURE`: Play Protect necesita
mostrar un diálogo y no puede. No hace falta desactivar ningún ajuste de
seguridad; basta con desbloquear y reintentar.

---

## 2. Entorno

| | |
|---|---|
| Android Studio | 2026.1.4 (`/opt/android-studio`), JBR = JDK 25 (no sirve para este build) |
| JDK para Gradle | Temurin **17.0.20.1** en `~/.jdks/` (instalado a mano, sin sudo) |
| SDK | `~/Android/Sdk` — AGP descarga solo las plataformas que falten |
| adb | `~/Android/Sdk/platform-tools/adb` (no está en el PATH) |

---

## 3. El repositorio

Este repo **es** la app: contiene la historia completa de
`OSUPCVLab/marslogger_android` (99 commits) integrada en la raíz, más los
cambios del TFG.

```bash
git remote -v
# origin    https://github.com/NJohnsonCR/Slam_GPS_Design_App.git
# upstream  https://github.com/OSUPCVLab/marslogger_android.git

git pull upstream main    # traer cambios futuros de upstream
```

Se optó por integrar la historia en vez de forkear porque un fork de GitHub **no
puede ser privado** ni reusar un nombre ya ocupado, y la trazabilidad real vive
en la historia de git, no en la etiqueta de GitHub. Si en algún momento se quiere
contribuir un arreglo a upstream, se forkea en ese momento y se hace cherry-pick.

### ⚠️ La licencia estaba solo en el superproyecto

`OSUPCVLab/marslogger_android` **no trae archivo `LICENSE` ni cabeceras de
licencia en el código Java**. El `LICENSE` de GPL-3.0 existe únicamente en el
superproyecto `mobile-ar-sensor-logger`, que es donde GitHub muestra la licencia.

Como el código se obtuvo formando parte de ese proyecto GPL-3.0, acá se **copió
el `LICENSE` a la raíz** para que no quede ambiguo. Es lo conservador: preserva
la licencia bajo la que se recibió el código en vez de dejar un repo sin
declaración.

No hay obligación de publicar nada mientras no se distribuya el binario. Si
alguna vez se distribuye, hay que publicar las modificaciones.

### ⚠️ El submódulo de `mobile-ar-sensor-logger` está congelado en 2021

El superproyecto `OSUPCVLab/mobile-ar-sensor-logger` apunta a un commit de
**diciembre de 2021** (`837abff`). El repo real de la app tiene **16 commits
más**, el último de septiembre de 2026. Clonar el superproyecto con
`--recursive` trae código obsoleto por cuatro años y medio.

Lo que cambió en esos 16 commits, y que el documento de contexto da por
inexistente:

| Commit | Fecha | Qué trae |
|---|---|---|
| `bd16335` | 2021-08-25 | **Gran angular** con la multi-camera API |
| `78795df` | 2022-01-29 | Geotagging de imágenes |
| `b628632` | 2022-03-12 | **Logging de GPS** con posiciones bufferizadas |
| `2f135bd` | 2022-08-07 | Migración fuera de Jetifier; GPS e IMU desacoplados |
| `52399cd` | 2023-06-09 | Magnetómetro |
| `0b00685` | 2024-03-23 | Carpeta `LocationProvider` |
| `68edefd` | 2024-08-15 | **Gradle 7.5 / AGP 7.4.2 / AndroidX** |
| `a36898c` | 2026-09-04 | Auto-exposición al tocar para enfocar |

---

## 4. Verificación en el teléfono

### Dispositivo definitivo del proyecto

**Samsung Galaxy S23 (SM-S911U1), Android 16 / One UI 8.5, API 36.**
Serie `R3CWB04L06Y`.

> Hubo dos equipos antes: el de `mobile_data/2025_03_11` (distancia focal
> 5.89 mm, más un segundo flujo de 3.49 mm) y un Galaxy A54 (5.54 mm). **Ninguno
> se usa.** Se dejan documentados porque sirvieron para descubrir cómo se calcula
> `fx` (§5).

### Grabación de prueba, 27 s

| | S23 (definitivo) | A54 (descartado) | `mobile_data/2025_03_11` |
|---|---|---|---|
| Video | 720×1280 vertical | igual | igual |
| Tasa de frames | **30.00 fps** | 30.01 fps | 29.97 fps |
| dt entre frames | **33.33 ms exacto** (min = max) | 33.28–33.37 | — |
| Saltos > 50 ms | **0** | 0 | — |
| IMU | **49.77 Hz** | 49.67 Hz | 49.9 Hz |
| Obturador rodante | **6.54 ms** ✅ | 31.03 ms | 20.79 ms |
| Distancia focal | 5.40 mm | 5.54 mm | 5.89 mm |

Salen los cuatro archivos de la puerta más `edge_epochs.txt`, que confirma
`SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME`: cámara e IMU comparten base de tiempo y
no hace falta sincronización offline.

**El obturador rodante es la mejora grande:** 6.54 ms contra 31 ms del A54. A
30 fps la lectura del sensor ocupa el **20 %** del intervalo entre frames en vez
del 93 %. Para odometría visual en un vehículo en movimiento eso se traduce
directamente en menos deformación geométrica por frame.

> Esta tabla se midió con el APK de la versión de 2021. Falta repetirla con
> `main`, que además genera `gps.csv` y `all_gps.csv`.

---

## 5. ⚠️ BUG: la columna `fx` del CSV está en el marco equivocado

En el S23, `movie_metadata.csv` reporta **`fx = 2766.13`** para un video de
720×1280: **3.18× más grande** de lo que corresponde. **Sigue presente en `main`.**

**Causa.** `FocalLengthHelper.getFocalLengthPixel()` tiene dos ramas:

```java
if (mIntrinsic != null && mIntrinsic[0] > 1.0) {
    return new SizeF(mIntrinsic[0], mIntrinsic[1]);   // <-- sale sin reescalar
}
if (mFocalLength != null) {
    ...                                               // esta rama SÍ aplica crop y escala
}
```

`LENS_INTRINSIC_CALIBRATION` está definido por Android en el **marco del array
activo del sensor** (acá 4080×3060). La rama de lente delgada aplica
correctamente el factor `imageSize / cropRegion`; la de calibración de fábrica
**sale antes de hacerlo**. En los teléfonos que no exponen la calibración (el A54
y el viejo) el bug no se ve, porque nunca se entra a esa rama.

**Cómo se detecta:** en el S23 `fx ≠ fy` (2766.13 vs 2768.51) y `fx` es constante
(desv 0.00). En los otros dos `fx == fy` exacto y la correlación con la distancia
de enfoque es **+1.000** — la firma de la fórmula.

### Intrínsecos correctos del S23

Calibración de fábrica, cámara 0 (`activeArraySize` 4080×3060,
`physicalSize` 8.16×6.12 mm):

```
intrinsicCalibration = [2766.12866, 2768.51489, 2010.50525, 1554.82983, 0.0]
distortion           = [0.09033814, -0.12104008, 0.05441276, 0.0, 0.0]
```

Reescalado al video de 720×1280 (recorte 16:9 → 4080×2295, escala 1280/4080):

| | Valor | Centro geométrico |
|---|---|---|
| `fx` | **868.6** | — |
| `fy` | **867.8** | — |
| `cx` | ~352–368 | 360 |
| `cy` | ~631–649 | 640 |

El rango de `cx`/`cy` es porque el sentido de la rotación a vertical no se deduce
de los archivos; en ambos casos quedan a menos de 10 px del centro.

**Plausibilidad:** con `fx = 868.6` el campo de visión horizontal da **72.8°**,
normal para una cámara principal. Con `fx = 2766` daría 14.8°, absurdo. Que el
valor correcto caiga cerca del A54 (869) y del teléfono viejo (899) confirma que
son tres cámaras principales de FOV parecido.

**Para el lado PC:** `run_replay.py --fx 869`. **No usar el valor del CSV.**
Hay además **coeficientes de distorsión de fábrica**, que el modelo de lente
delgada ignoraba por completo.

### Cámaras del S23

Distancias focales expuestas: **5.40 mm** (principal, la que usa la app por
defecto), **2.20 mm** (ultra gran angular), **7.00 mm** (teleobjetivo), 3.30 mm
(frontal). El §7 del contexto recomienda la gran angular; está disponible y
upstream ya trae el soporte de multi-camera (`bd16335`).

---

## 6. ⚠️ Correcciones a `CONTEXTO_APP_ANDROID.md`

### §2 — «Le falta el GPS (roadmap, no implementado)»

**Falso desde 2022.** Upstream tiene `LocationProvider.java` y `GPSManager.java`
con `FusedLocationProviderClient`, y escribe `gps.csv` y `all_gps.csv`.

**Pero no sirve tal cual**, por tres razones:

1. **Reloj equivocado.** En `all_gps.csv`:
   ```java
   mAllLocationsWriter.write(
       System.currentTimeMillis() + ","   // columna rotulada Timestamp[nanosec]
       + lat + "," + lon + "," + alt + ","
       + System.currentTimeMillis() + "\n"
   );
   ```
   Reloj de pared en **milisegundos** en una columna que dice nanosegundos, las
   dos columnas con el mismo valor, y tomado en el instante del callback, no del
   fix. El §5 exige `Location.getElapsedRealtimeNanos()`. **Así no se puede
   alinear con la cámara.**
2. **Falta la columna `speed`**, que sí está en el formato objetivo del §6.
3. **`gps.csv` muestrea la última posición conocida con el timestamp del frame**,
   no del fix — mete el error de antigüedad del fix que del lado PC se midió en
   508 ms de mediana.

### Confirmado con datos (grabación del 19/9/2026, 31 s, S23)

| | Medido |
|---|---|
| Fixes de GPS | **32 en 31.0 s → 1.00 Hz exacto** (dt mediana 1001 ms, rango 989–1029) |
| `gps.csv` | **vacío**: solo la cabecera, 65 bytes |
| `all_gps.csv` | 32 filas con datos válidos |
| Columnas 1 y 5 | **idénticas** (diferencia mediana 0.0 ms) |
| Frames | 942 en 31.37 s → 30.00 fps, dt 33.33 ms, 0 saltos |
| IMU | 52.90 Hz |
| `fx` / `fy` | 2766.13 / 2768.51 → **el bug §5 sigue** |

**`gps.csv` está vacío porque `recordGpsValue()` no tiene ningún llamador.** Es
código muerto: `startRecording()` abre el archivo y escribe la cabecera, pero
nadie le agrega filas. El único archivo con datos es `all_gps.csv`.

**El timestamp roto, decodificado:** `1789850267971` interpretado como
milisegundos Unix da `2026-09-19 20:37:47 UTC`, que es la hora real de la
grabación. Como nanosegundos daría 1970. Es `System.currentTimeMillis()`.

**La incompatibilidad de relojes, medida:**

```
camara (elapsedRealtimeNanos) : 3772698269000 ns = 3772.7 s desde el arranque
IMU    (SensorEvent.timestamp): 3772732448519 ns = 3772.7 s desde el arranque
GPS    (currentTimeMillis)    : 1789850267971 ms = reloj de pared
```

Cámara e IMU comparten base de tiempo. **El GPS está en otro reloj y no se puede
alinear con ellos.** Es exactamente lo que advierte el §5 del contexto.

**Fase 1 pasa de «implementar GPS» a un parche chico:**

1. Cambiar `System.currentTimeMillis()` por `location.getElapsedRealtimeNanos()`
2. Agregar la columna `speed` desde `location.getSpeed()`
3. Decidir qué hacer con `gps.csv` (cablear `recordGpsValue()` o eliminarlo) y
   producir `location.csv` con el formato del §6
4. Subir `DEFAULT_UPDATE_INTERVAL` / `FAST_UPDATE_INTERVAL` para pedir 10 Hz
   y medir qué entrega el hardware (Fase 0.5)

### §6 — «a `movie_metadata.csv` y `gyro_accel.csv` les falta la última columna»

**Falso.** Las cabeceras de `movie_metadata.csv`, `gyro_accel.csv` y
`frame_timestamps.txt` coinciden **carácter por carácter** con las de
`mobile_data/2025_03_11`, columna `Unix time[nanosec]` incluida.

### Fase 0.5 — la hipótesis era correcta

El documento sospechaba que el 1 Hz era el default de la app y no un límite del
hardware. **Confirmado en el código.** `LocationProvider.java`:

```java
private static final int DEFAULT_UPDATE_INTERVAL = 1;
private static final int FAST_UPDATE_INTERVAL = 1;
...
locationRequest.setInterval(1000 * DEFAULT_UPDATE_INTERVAL);      // 1000 ms
locationRequest.setFastestInterval(1000 * FAST_UPDATE_INTERVAL);  // 1000 ms
```

Medir qué entrega el S23 pidiendo 10 Hz es cambiar una constante y grabar.

El APK de `main` además declara `HIGH_SAMPLING_RATE_SENSORS`, lo que sugiere que
la IMU también puede ir bastante por encima de los ~50 Hz observados.

---

## 7. Pendientes

- [ ] Grabar con `main` en el S23 y verificar `gps.csv` / `all_gps.csv`
- [ ] Fase 0.5: subir la frecuencia pedida a 10 Hz y medir lo que entrega
- [ ] Fase 1: corregir el reloj del GPS, agregar `speed`, alinear el formato
- [ ] Arreglar el bug de `fx` (§5) y evaluar reportarlo a upstream
- [ ] Evaluar la cámara gran angular (2.20 mm)
- [ ] Liberar espacio en disco (al 93 %)
