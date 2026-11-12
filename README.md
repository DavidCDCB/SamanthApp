# SamanthApp (Android)

Samantha para Android: **orbe interactivo**, con el ciclo
completo **voz → modelo → voz**.

1. El reconocedor de voz del sistema transcribe lo que dices (es-CO).
2. El texto va a OpenCode Go (API compatible con OpenAI) y responde el modelo.
3. La respuesta se limpia (sin markdown ni etiquetas de razonamiento) y se lee
   con el TTS del teléfono.

## Controles

- **Al abrir la app no hay saludo automático.** La conversación empieza
  cuando tocas el orbe y hablas. En ese primer turno el prompt ya incluye la
  **fecha y hora actuales**, y se recalculan en cada mensaje.
- Si **«Hola Samantha»** está activada, el micrófono se abre al iniciar la app
  (unos 0,3 s después de quedar visible) y Vosk se queda escuchando la palabra
  sin que tengas que tocar el orbe. Al detectarla empieza el turno de voz.
- **La app se muestra a pantalla completa**: sin barra de estado ni de
  navegación. Si deslizas desde el borde superior o inferior, aparecen
  temporalmente y se vuelven a ocultar.
- **Toca el orbe** para empezar a hablar y tócalo otra vez para detenerte.
  Mientras Samantha habla, un toque corta la voz y vuelve a escuchar. Ese
  intercambio interrumpido se borra del historial: el modelo no lo tiene en
  cuenta en el siguiente turno.
- La respuesta aparece en una tarjeta con **scroll vertical propio**: si es
  larga, se desplaza dentro de la tarjeta (hasta 240 dp de alto) sin cortar el
  texto, y el orbe no se mueve.
- **Fondo animado a pantalla completa**: aurora multicapa, cortinas que flotan,
  polvo con parpadeo, luciérnagas y anillos lentos que nacen del centro real
  del orbe; en reposo aparece de vez en cuando una estrella fugaz. El color
  cambia con la hora local: **mañana** (verde agua y dorado), **mediodía**
  (azul y cian), **tarde** (naranja y rosa) y **noche** (índigo y violeta).
  Los estados activos conservan su color funcional. En Ajustes puedes elegir
  **Normal**, **Bajo** (menos FPS, manchas y partículas) o **Apagado**; se
  pausa solo cuando la app deja de estar visible.
- **Mantener pantalla encendida**: activado por defecto; evita que la pantalla
  se apague sola mientras la app está en primer plano. Ideal para dejar el
  teléfono dedicado en el escritorio. Se puede desactivar en Ajustes.
- **Horizontal**: al girar el teléfono, el orbe queda **centrado** si no hay
  tarjetas; cuando aparece una transcripción o respuesta, pasa a la mitad
  izquierda y las tarjetas a la derecha. Mantiene un margen superior para que
  su expansión no quede debajo de la barra de estado. La actividad no se
  recrea, así que la escucha y la voz no se cortan durante el giro.
- **Ajustes** está en el menú de **tres puntos**, arriba a la derecha, y ahora
  ocupa **pantalla completa**.
- En Ajustes puedes elegir **Español o English**: cambia el idioma del
  micrófono (STT) y de la voz (TTS). El modelo, además, **responde en el mismo
  idioma en que le hablas** (inglés si le hablas en inglés, español si en
  español); el modo elegido es el fallback si el mensaje es ambiguo.
- En Ajustes puedes activar **Escuchar al terminar de hablar**: al acabar cada
  respuesta hay un margen de **200 ms**; si el motor TTS todavía está sonando
  (algunos móviles llaman `onDone` antes), se espera a que termine de verdad.
  Para voces de red se añaden **400 ms** extra. Después se abre **una sola
  ventana de escucha** (unos 5 s). Si no hablas, vuelve a esperar sin abrir
  otra ventana.

## «Hola Samantha» con Vosk

- En **Ajustes → Escuchar «Hola Samantha»** puedes activar la palabra de
  activación. La primera vez descarga un modelo español pequeño de Vosk
  (~39 MB, licencia Apache 2.0); después funciona sin internet.
- **App abierta**: Vosk escucha solo mientras ves el orbe. No hay notificación
  ni servicio en segundo plano.
- **Segundo plano**: Samantha mantiene un servicio en primer plano con una
  notificación permanente y consume más batería. Al detectar la frase muestra
  el aviso **«Te escucho»** e intenta abrir la app; si el sistema lo permite, se
  abre sola. Para aumentar las probabilidades, concede **«Mostrar encima de
  otras apps»** desde Ajustes → «Permitir apertura automática». Android 14+ y
  MIUI pueden bloquearlo igualmente: en ese caso queda el aviso para tocar.
- El audio de la palabra de activación se procesa en el teléfono; no se envía
  a ningún servidor.
- Si fuerzas la detención de la app, el servicio en segundo plano también se
  detiene hasta que vuelvas a abrirla.

## Requisitos

- Android 8.0+ (minSdk 26).
- Servicio de reconocimiento de voz (Google o el del fabricante).
- Voz en español instalada en el teléfono (Ajustes → Idiomas → Salida de voz).
- Conexión a internet y una **API key de OpenCode Go**.
- Para «Hola Samantha», conexión la primera vez para descargar el modelo Vosk
  (~39 MB); luego funciona sin internet.

## Configuración (primera vez)

1. Abre la app → **Ajustes** (si no hay API key, se abre solo al arrancar).
2. Pega la **API key** (en el PC está en `secret\OPENCODE_GO_API_KEY`).
3. Modelo por defecto: `claude-haiku-5-5` (puedes cambiarlo en Ajustes o en `config.yaml`).
4. Pulsa **Probar modelo** para validar key y red. Para oír la voz, abre
   **Voz del sistema**: al elegir una voz de la lista suena una frase de muestra.
5. **Guardar**.

La key se guarda en las preferencias privadas de la app (sin cifrar). No sale
del teléfono salvo en la petición HTTPS a OpenCode. El backup de Android está
desactivado para que la key y la memoria no se suban a la nube.

## Memoria

Samantha recuerda entre sesiones (SharedPreferences privadas, sin backup):

- **Tu nombre**: no hay campo en Ajustes. Si no lo sabe, el modelo lo pregunta
  una sola vez al inicio y la app guarda la respuesta ("Juan", "me llamo Juan",
  "mi nombre es Juan"…). También se actualiza si lo dices en otro momento.
- **Recuerdos**: di "recuerda que …" para guardar un dato y "olvida …" /
  "olvídate de …" para borrarlo. Se incluyen en el prompt de cada turno.
- **Historial**: se guardan los últimos mensajes (por defecto 8 = 4
  intercambios) en `samantha_memory`; **persisten al cerrar la app** y se
  recuperan al abrir. La cantidad se ajusta con `memory.recent_window` en
  `config.yaml` (2–200 mensajes; cada intercambio son 2). Los más viejos se
  descartan al superar el límite.
- **Ajustes** muestra cuántos recuerdos hay y permite **borrar memoria**
  (recuerdos, historial y nombre; la próxima conversación vuelve a preguntarlo).

Los comandos de memoria se resuelven en el teléfono, sin llamar al modelo:
responden "Lo recordaré." o "Lo olvidé." al instante.

## El orbe

`OrbView.kt` es un **port fiel de `openocto/ui/orb.py`** del proyecto de PC:

- Cuerpo plano de 44 capas concéntricas con viñeta y borde de vidrio (sin brillo interior), deformación orgánica y canto con sombra/luz.
- Halo de 22 capas, anillo tenue e infinito crema (lemniscata de Gerono).
- Estados y efectos: `waiting` gris con motas, `listening` cian con arco, `hearing` verde agua con corona de barras, `transcribing` violeta con arcos, `thinking` ámbar con cometas, `speaking` rosa con corona y ondas, `error` rojo con corona discontinua y pulsos.
- Todo se escala con la misma unidad de referencia (ventana de 220 px) para que trazo, tamaño y movimiento coincidan. La vista se adapta al ancho de la pantalla y el radio se limita para que el halo y la corona nunca se recorten en cuadrado.
- Toques Android extra: transición de color suave entre estados, resplandor ambiental, sombra bajo el orbe, polvo orbital y brillo giratorio en reposo, onda al tocar y respuesta de nivel con ataque rápido/caída lenta.

El icono de la app se generó desde `assets/samantha-orb.png` del PC (orbe rosa con el
infinito) y se incluye como adaptive icon + mipmaps por densidad.

## Persona (prompt)

El prompt vive en `app/src/main/assets/persona_samantha.md` y está basado en
`personas/samantha/system_prompt.md` del proyecto de PC: misma esencia, voz,
carácter, reglas de voz y cuidado. La búsqueda web se añadió con Firecrawl; el
bloque de búsqueda solo se agrega al prompt cuando hay una API key configurada.
Las secciones de `open_search` y `wikipedia` siguen fuera.

En cada petición `SamanthaApi.composeSystemPrompt(...)` añade al final:

- `## Fecha y hora actuales` (se calcula en el momento de cada mensaje),
- `## La persona` (nombre guardado o instrucción de preguntarlo una vez),
- `## Recuerdos guardados` (memoria persistente),
- `## Idioma de esta conversación` (responde en el idioma del usuario; el modo
  elegido en Ajustes es el fallback).

Para cambiar la personalidad basta editar ese `.md` y recompilar.

## Configuración avanzada (`config.yaml`)

En el primer arranque se copia `assets/config.yaml` a:

```
Android/data/com.cdcb.samanthapp/files/config.yaml
```

**Importante:** si ese archivo ya existe, la app **no lo sobrescribe** al
recompilar o actualizar el APK. El archivo del teléfono manda. Para cambiar un
valor en una instalación existente, edita el archivo del teléfono y reinicia;
cambiar solo el `assets` y recompilar no actualiza la copia que ya está en uso.

```yaml
ai:
  base_url: "https://opencode.ai/inference/go/openai/v1"
  model: "claude-haiku-5-5"
  reasoning_effort: "high"
  max_tokens: 800
  temperature: 0.6

memory:
  recent_window: 8   # mensajes (2 = 1 intercambio); 2–200

firecrawl:
  api_key: ""          # pega aquí la key fc-...
  max_results: 5
  timeout: 15
  lang: "es"
  country: "CO"
```

- `reasoning_effort`: `low | medium | high | xhigh | max` según el modelo.
  Por defecto **high** (más razonamiento, respuestas más lentas que con `low`).
- `max_tokens` y `temperature` también se leen de ahí.
- El `model` de **Ajustes** (si lo guardas) manda sobre el del archivo; el resto
  de parámetros sale siempre del YAML.
- **`memory.recent_window`**: cantidad de mensajes recientes que se conservan
  en `samantha_memory` y se envían al modelo. Por defecto **8** (4
  intercambios). Rango 2–200. Al bajarlo, el historial guardado se recorta.
- **`firecrawl.api_key`**: la API key de Firecrawl va **solo en este archivo**,
  no en la pantalla de Ajustes. Si está vacía, el modelo no recibe la
  herramienta `web_search`. La app nunca imprime la key; en logcat solo se ve
  `firecrawl=sí (N chars)` o `firecrawl=no`.
- Se lee al arrancar: edita el archivo y **reinicia la app**. En Android 11+ los
  gestores de archivos no ven `Android/data`; usa USB/adb:

```powershell
adb pull /sdcard/Android/data/com.cdcb.samanthapp/files/config.yaml
# editar reasoning_effort y firecrawl.api_key
adb push config.yaml /sdcard/Android/data/com.cdcb.samanthapp/files/config.yaml
```

El valor activo queda en logcat con la etiqueta `SamanthaConfig`.

## Búsqueda web (Firecrawl)

- El modelo recibe una herramienta `web_search` cuando `firecrawl.api_key` no
  está vacía. La usa para noticias, precios, eventos o datos recientes; no para
  definiciones que ya sabe.
- Firecrawl devuelve título, resumen y URL; la app no descarga la página
  completa, así que no gasta créditos de scraping.
- Límite de voz: como máximo **2 búsquedas por turno**; después el modelo
  responde sin herramientas.
- Si Firecrawl rechaza la key (401), se queda sin créditos (402) o falla la
  red, el modelo recibe el error y debe decirlo; no inventa resultados.
- No hay respaldo a DuckDuckGo en Android todavía; la búsqueda web requiere una
  key válida de Firecrawl en `config.yaml`.

## APK release

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleRelease
```

Salida: `dist\Samantha-2.6.0-android16.apk` (también en
`app\build\outputs\apk\release\app-release.apk`).

- `minSdk 26` → Android 8.0 en adelante, incluido **Android 16** (API 36).
- `targetSdk 37`, APK universal (sin librerías nativas: sirve en cualquier ABI).
- Firmado con la **keystore de debug** para poder instalar/actualizar por
  sideload sin perder datos. Para publicar en Play hay que crear una keystore
  de release propia y firmar con ella.

## Compilar

Desde Android Studio: abrir la carpeta `SamanthApp` y pulsar **Run**.

Desde consola (Windows):

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :app:assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

## Instalar en un Xiaomi / HyperOS

HyperOS bloquea `adb install` por defecto aunque la depuración USB esté activa:

1. Ajustes → Sobre el teléfono → pulsar **Versión de HyperOS** 7 veces.
2. Opciones de desarrollador → activar **Depuración USB** e **Instalar vía USB**.
3. Conectar el teléfono y aceptar el aviso de depuración.
4. `adb install -r app-debug.apk` o **Run** desde Android Studio.

## Estados del orbe

| Estado | Color |
|---|---|
| Inactivo sin configurar | gris |
| Inactivo configurado | azul |
| Escuchando (esperando voz) | cian |
| Escuchando (te oye) | verde agua |
| Transcribiendo | violeta |
| Pensando | ámbar |
| Hablando | rosa |
| Error | rojo |

El orbe es la única señal de estado: no hay etiquetas de texto. **Azul** significa
que ya hay API key y modelo configurados; **gris**, que falta configurar. Los
mensajes de error aparecen en la tarjeta de respuesta, en rojo.

Si el reconocedor no oye voz (silencio o no coincide), el rojo dura unos
**3,5 s** y luego vuelve solo al azul; el aviso se retira.

El orbe también reacciona al nivel del micrófono mientras escuchas.

## Estructura

```
app/src/main/java/com/cdcb/samanthapp/
├─ MainActivity.kt   # pantalla, permiso, STT, TTS, memoria y ciclo completo
├─ SamanthaApi.kt    # POST a OpenCode Go, prompt y limpieza para voz
├─ MemoryStore.kt    # nombre, recuerdos e historial persistentes
├─ NameExtractor.kt  # nombre dictado + comandos "recuerda/olvida"
└─ OrbView.kt        # orbe con Canvas + animación
app/src/main/assets/persona_samantha.md   # prompt de la persona
app/src/main/res/
├─ layout/{activity_main,dialog_settings}.xml
├─ drawable/{bg_screen,card_bg,ic_launcher_foreground}.xml
├─ values/{colors,strings,themes}.xml
├─ xml/data_extraction_rules.xml
└─ mipmap-anydpi-v26/ic_launcher*.xml
docs/capturas/       # capturas reales del teléfono
```

## Limitaciones actuales

- La respuesta es **sin streaming**: espera la respuesta completa y luego habla.
- Historial **persistente** de 8 turnos, sin resumen automático de conversaciones.
- Sin herramientas (búsqueda web, notas, Wikipedia) todavía.
- Sin wake word: se activa tocando el orbe.
- La API key no está cifrada (SharedPreferences privadas, sin backup).

## Siguientes pasos

- Streaming SSE: hablar por frases mientras llega la respuesta.
- Wake word offline con Vosk Android (misma gramática/verificación que en PC).
- Herramientas: `web_search`, `wikipedia`, notas; memoria en Room.
- Historial persistente y ajustes de voz (velocidad, voz).
