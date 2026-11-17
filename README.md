# Samurai Flash ⚔️

Duelo de reflejos entre samuráis: respira, espera la señal y ataca antes que tu rival.
Todo el juego está en un solo archivo HTML, sin instalar nada.

**Jugar en el navegador:** https://geisoncontreras.github.io/samurai-flash-/

## Instalar en Android (APK)
Descarga el APK: https://github.com/geisoncontreras/samurai-flash-/releases/download/apk/samurai-flash.apk

Ábrelo en el teléfono y, si Android lo pide, permite "Instalar apps desconocidas" para tu navegador o el administrador de archivos. El APK se vuelve a compilar solo (GitHub Actions) cada vez que cambia `main`.

## Cómo jugar
1. **Respira:** antes de cada ronda mantén presionada tu mitad de la pantalla. La barra sube y baja; suéltala en el punto más alto para tener más poder.
2. **Espera la señal:** aparece en un momento aleatorio. Si tocas antes, pierdes la ronda.
3. **Ataca:** el primero en tocar su lado después de la señal gana. Si los dos tocan casi a la vez, gana quien respiró con más poder; con el mismo poder, las espadas chocan y se repite.
4. Gana el mejor de tres rondas.

Modos: 2 jugadores en el mismo teléfono (jugador 1 a la izquierda, jugador 2 a la derecha), contra la computadora (dificultad progresiva) y online hasta 4 jugadores.

## Estructura
| Ruta | Qué es |
|---|---|
| `index.html` | El juego completo (también es la página de GitHub Pages). |
| `politica-privacidad.html` | Política de privacidad para la ficha de Play Store. |
| `app-android/` | Proyecto Android (Capacitor) para generar la app. |
| `firebase/reglas-firebase.json` | Reglas de Realtime Database para el modo online. |
| `play-store/` | Icono 512×512 y gráfico destacado 1024×500 para la ficha. |

## Modo online
Necesita un proyecto de Firebase con Realtime Database. Pega tu `firebaseConfig` en la línea
`const FIREBASE_CONFIG=null;` al inicio de `index.html` y publica las reglas de `firebase/reglas-firebase.json`.
Para probar sin servidor, abre el juego con `?localnet` al final de la dirección (funciona entre pestañas del mismo navegador).

## Compilar la app Android (.aab para Play Store)
1. Instala Node.js 22 o más nuevo y Android Studio.
2. En la carpeta `app-android`: `npm install` y luego `npm run sync` (copia `index.html` a la app y sincroniza Android).
3. `npm run open` (o abre la carpeta `app-android/android` en Android Studio).
4. En Android Studio: Build → Generate Signed App Bundle or APK → Android App Bundle → crea una llave nueva (guárdala muy bien y **no la subas a GitHub**) → release.
5. El archivo queda en `app-android/android/app/release/app-release.aab`. Ese es el que se sube a Play Console.

Identificador de la app: `com.samuraiflash.juego`. Si quieres otro, cámbialo antes de la primera subida a Play Store; después ya no se puede.

## Actualizar el juego
Edita `index.html`, sube `versionCode` y `versionName` en `app-android/android/app/build.gradle`, ejecuta `npm run sync` y vuelve a generar el .aab.

## Servidor del modo en línea

El online (2 a 4 jugadores con código de sala) usa un servidor propio en la carpeta `servidor/` (Node + WebSocket), igual que Leyendas del Puño. Se publica gratis en Render con el archivo `render.yaml` (New → Blueprint → este repositorio). El juego se conecta a `wss://samurai-flash-servidor.onrender.com`.
