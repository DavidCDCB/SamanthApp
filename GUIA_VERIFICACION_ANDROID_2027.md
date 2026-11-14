# Guía: registrar una app Android para uso personal antes de 2027

> Para **Samantha** (`com.cdcb.samanthapp`) y para cualquier otra app que desarrolles.
> Última actualización: **12/11/2026**.
> Esto no es asesoría legal. Google cambia las reglas; al final están los enlaces oficiales para verificar el estado actual.

---

## 0. Resumen en 30 segundos

- La verificación de desarrolladores de Android empieza el **30/09/2026** en Brasil, Indonesia, Singapur y Tailandia, y se amplía **globalmente en 2027**.
- Aplica a **dispositivos certificados con Android 7 o superior**, mediante una actualización de Google Play Services.
- **Las instalaciones por ADB están exentas**: puedes seguir instalando y actualizando tu app por USB sin registrar nada.
- Para instalar el APK directamente desde el teléfono o compartirlo, existe la **cuenta de distribución limitada**: gratis, sin documento de identidad, hasta **50 dispositivos** (la guía vigente; algunas FAQ aún dicen 20).
- Si no registras nada, el **advanced flow** permite instalar apps no verificadas tras una configuración única con espera de 24 h y avisos.
- Lo ya instalado **no se desinstala retroactivamente** según el plan actual, pero **no hay una exención permanente**: las actualizaciones pueden fallar y, si desinstalas o reseteas, puedes no poder reinstalar sin una vía permitida.

---

## 1. Qué cambia exactamente

Google está pasando de “instala cualquier APK” a “instala apps de desarrolladores registrados” en dispositivos certificados.

| Fecha | Qué pasa |
|---|---|
| 30/09/2026 | Verificación en Brasil, Indonesia, Singapur y Tailandia, solo tiendas participantes. |
| 2027 | Expansión global para todas las apps en dispositivos certificados. |
| Colombia | No está en la fase inicial; prepárate antes de 2027. |

Puntos importantes:

- Se aplica a **instalar y actualizar**, no a una promesa de que lo ya instalado sea intocable.
- **ADB sigue funcionando** sin verificación y sin espera de 24 h.
- Una app ya instalada puede seguir abierta; lo que puede bloquearse son las **actualizaciones** desde el teléfono.
- Play Protect puede desactivar malware, pero eso es independiente de la verificación.

---

## 2. Elige tu ruta

| Ruta | Costo / requisitos | Para qué sirve |
|---|---|---|
| **ADB** | Nada; PC con USB | Tus propios teléfonos; desarrollo y uso personal |
| **Cuenta de distribución limitada** | Gratis; sin ID; Google Account, 2FA, perfil de pagos, correo | Instalar/compartir en hasta 50 dispositivos; familia y amigos; sin intención comercial |
| **Advanced flow** | Sin registro; modo desarrollador, reinicio y 24 h una vez | Instalar APK directo de desarrolladores no verificados; con avisos |
| **Cuenta completa / Play** | $25, ID; D-U-N-S si es organización | Distribución pública; Play Store |
| **F-Droid** | Código abierto, licencia FLOSS, build desde fuente | Público FOSS; no es la ruta personal y su futuro está en disputa |

Para uso personal, las rutas recomendadas son **ADB** y **cuenta limitada**.

---

## 3. Requisitos antes de empezar

- [ ] Cuenta Google (mejor una dedicada al desarrollo).
- [ ] Verificación en dos pasos activada.
- [ ] Perfil de pagos de Google con nombre y dirección legal (la cuenta limitada no pide documento de identidad).
- [ ] Correo de contacto.
- [ ] `applicationId` definitivo.
- [ ] Keystore y alias de firma.
- [ ] Contraseñas del keystore guardadas en un gestor de contraseñas.
- [ ] Huella SHA-256 del certificado.
- [ ] Un APK firmado para la prueba de propiedad.
- [ ] Copia de seguridad del keystore y de la configuración de la app.

---

## 4. Paso a paso

### 4.1 Identifica el paquete y la firma

El paquete está en `app/build.gradle.kts`:

```kotlin
defaultConfig {
    applicationId = "com.cdcb.samanthapp"
    versionCode = 45
    versionName = "2.6.0-SamanthApp"
}
```

Para cualquier otra app, cambia el `applicationId` por el suyo. **No lo cambies después de registrar**: el paquete es la identidad de la app.

Huella SHA-256 desde el APK:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
& "$sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs "dist\Samantha-2.6.0-android16.apk"
```

Busca `Signer #1 certificate SHA-256 digest`.

También puedes obtenerla del keystore:

```powershell
keytool -list -v `
  -keystore "$env:USERPROFILE\.android\debug.keystore" `
  -alias androiddebugkey `
  -storepass android -keypass android
```

La huella es **pública**; la clave privada nunca se sube.

### 4.2 Decide qué clave vas a registrar

Tienes dos opciones:

**A. Mantener la clave de debug actual (lo más cómodo para uso personal)**

- El APK actual está firmado con `CN=Android Debug`.
- Registrar esa clave permite seguir actualizando sin desinstalar y conservar datos.
- No es una clave de producción: la `debug.keystore` tiene contraseñas por defecto y no debe usarse para publicar.
- Guarda una copia de `C:\Users\crist\.android\debug.keystore`; si la pierdes, no podrás firmar actualizaciones ni completar la prueba de propiedad.

**B. Crear una clave de release (recomendado si algún día publicas)**

```powershell
keytool -genkeypair -v `
  -keystore release.jks `
  -alias samantha `
  -keyalg RSA -keysize 4096 -validity 10000
```

- Guarda `release.jks` fuera del repositorio y las contraseñas en un gestor.
- Cambiar de la firma debug a release obliga a **desinstalar y reinstalar** una vez; Android no permite actualizar entre firmas distintas.
- La app tiene `allowBackup=false`, así que se pierden configuración, key e historial. Antes de cambiar, anota la API key, la key de Firecrawl, tu nombre y los recuerdos importantes.

Configuración de firma en Gradle (ejemplo; no pongas contraseñas en el repo):

```kotlin
// keystore.properties va en .gitignore
val keystoreProps = java.util.Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}
```

### 4.3 Crea la cuenta de distribución limitada

1. Entra a **https://android.google.com/developerconsole/developers** con tu cuenta Google.
2. Activa **2-Step Verification**.
3. Elige **Limited distribution / Distribución limitada** (no la cuenta completa de $25).
4. Completa el **perfil de pagos**: nombre y dirección legal; no pide documento de identidad.
5. Añade un correo de contacto.
6. Acepta los términos.

La cuenta es gratis y no requiere ID. Si no ves la opción “Limited distribution”, revisa que estés en la cuenta correcta, con 2FA y perfil de pagos; el centro de ayuda es **https://support.google.com/android-developer-console**.

### 4.4 Registra el paquete

1. Abre **https://android.google.com/developerconsole/**.
2. Ve a **Packages / Nombres de paquete**.
3. Pulsa **Add package / Registrar paquete**.
4. Escribe `com.cdcb.samanthapp` (o el de tu otra app).
5. Añade la **huella SHA-256** del certificado.
6. El estado pasa a **In review**.

Si el paquete ya está en uso:

- La consola aplica reglas de mayoría de instalaciones, clúster de 50+ instalaciones o primero en llegar.
- Para una app personal con un paquete único, lo normal es que esté libre.
- Si no lo está, cambia el `applicationId` antes de distribuir (por ejemplo, `com.cdcb.samantha.desk`) y reconstruye; los teléfonos con la app vieja no se actualizan a la nueva firma/paquete sin desinstalar.

### 4.5 Prueba de propiedad

La consola te dará un fragmento o archivo de desafío. Debes demostrar que tienes la clave privada:

1. Copia el fragmento exactamente donde indique la consola (normalmente `app/src/main/assets/`).
2. Construye un APK firmado con la **misma clave** que registraste:
   ```powershell
   .\gradlew.bat :app:assembleRelease
   ```
3. Verifica la firma:
   ```powershell
   & "$sdk\build-tools\36.0.0\apksigner.bat" verify --print-certs `
     "app\build\outputs\apk\release\app-release.apk"
   ```
4. Sube ese APK a la consola.
5. Espera el correo y el estado **Registered**.

Consejos:

- El APK de prueba debe tener el mismo `applicationId` y estar firmado con la clave registrada.
- Si la consola pide un nombre de archivo concreto, respétalo.
- Si falla, revisa: paquete, huella, firma, ruta del desafío y que el APK no esté firmado por Play App Signing.
- No subas un APK con keys reales (Firecrawl, OpenCode) dentro. Usa una build sin secretos para la prueba.

### 4.6 Autoriza tus dispositivos

1. En la consola, busca la opción de **compartir** la app (QR o enlace).
2. En cada teléfono, abre el enlace/QR y acepta el consentimiento.
3. Instala la app.
4. Repite para cada dispositivo; el límite es de hasta **50 dispositivos** en la cuenta limitada.

No hay ficha pública ni listado en una tienda: es una distribución privada.

### 4.7 Actualiza la app

- Mantén el mismo `applicationId` y la misma clave.
- Sube el `versionCode` en cada build.
- Firma con la misma clave y actualiza por el enlace/QR o por ADB.
- Si cambias de clave, los dispositivos con la clave vieja necesitan desinstalar y reinstalar.
- Si algún día pasas a cuenta completa/Play, podrás migrar la cuenta limitada; al revés no.

---

## 5. Caso Samantha

Datos actuales de esta app:

| Dato | Valor |
|---|---|
| Paquete | `com.cdcb.samanthapp` |
| Versión actual | 2.8.4 (`versionCode 56`) |
| APK | `dist\Samantha-2.8.4-android16.apk` |
| Firma actual | Debug (`CN=Android Debug`) |
| SHA-256 actual | `96:4D:31:DE:31:E0:81:4A:F0:41:AA:9A:2E:4B:10:E8:1E:4D:CA:FB:E8:51:B2:58:C7:98:7E:20:89:3E:D7:01` |
| `build.gradle.kts` | `signingConfig = signingConfigs.getByName("debug")` |
| Backup | `allowBackup=false` |

Recomendación para uso personal:

- Registrar la **clave debug actual** si quieres conservar datos y actualizar sin desinstalar.
- Guardar una copia de `C:\Users\crist\.android\debug.keystore`; sin ella no hay actualizaciones ni prueba de propiedad.
- Si algún día publicas en Play, crea una clave release y asume una reinstalación (pérdida de historial/configuración; anota las keys antes).
- La app tiene una key real de Firecrawl en `assets/config.yaml`. **Quítala antes de compartir el APK fuera de tus dispositivos** o de subir cualquier APK a la consola; una key dentro del APK es pública. Rótala si el APK ya circuló.

Instalación por ADB (sin registro, para tus teléfonos):

```powershell
adb install -r "dist\Samantha-2.8.4-android16.apk"
```

---

## 6. Plantilla para cualquier otra app

Copia esta tabla y complétala por cada app:

| App | Package (`applicationId`) | Alias de firma | SHA-256 | Cuenta | Estado | Dispositivos | Notas |
|---|---|---|---|---|---|---|---|
| Samantha | `com.cdcb.samanthapp` | `androiddebugkey` | `96:4D:...:01` | Limitada | Pendiente | 0 | Backup debug.keystore |
| Otra app | `com.tudominio.app` | `release` | ... | Limitada | Pendiente | 0 | ... |

Pasos para cada app:

1. Obtén la huella del APK/keystore.
2. Registra el package en la misma cuenta limitada.
3. Añade la huella.
4. Sube el APK de prueba con el desafío.
5. Espera `Registered`.
6. Autoriza los dispositivos.
7. Documenta package, alias, huella y estado en tu gestor de contraseñas.

Si la cuenta limitada no alcanza (más de 50 dispositivos, uso comercial o publicación), crea una cuenta completa/Play y verifica los requisitos de Play Store.

---

## 7. ADB y advanced flow

### ADB

- Exento de verificación, sin espera de 24 h.
- Sirve para instalar y actualizar en tus propios teléfonos.
- Requiere depuración USB y PC.
- Es la opción más simple para desarrollo y uso personal.

```powershell
adb devices
adb install -r "ruta\app.apk"
```

### Advanced flow

Si no registras la app y quieres instalar el APK desde el teléfono:

1. Activa Opciones de desarrollador (toca 7 veces “Número de compilación”).
2. Confirma que no te está guiando nadie.
3. Reinicia y vuelve a autenticarte.
4. Espera **24 h**.
5. Confirma con huella/PIN.
6. Elige permitir **7 días** o **indefinidamente**.
7. Instala con el aviso de “desarrollador no verificado”.

Las actualizaciones de apps no registradas solo funcionan con advanced flow activo o por ADB. Si lo desactivas, las actualizaciones fallan. No es la ruta más cómoda para el día a día.

---

## 8. Problemas frecuentes

**No aparece “Limited distribution”.**
Revisa cuenta correcta, 2FA, perfil de pagos y región. Usa el centro de ayuda de Android Developer Console.

**El paquete ya está registrado.**
Cambia el `applicationId` antes de distribuir o solicita revisión. Para una app personal, usa un paquete único con tu dominio.

**La huella no coincide.**
Asegúrate de usar el APK y la clave correctos. `apksigner verify --print-certs` muestra la huella real del APK; `keytool` muestra la del keystore. Debug y release son claves distintas.

**La prueba de propiedad falla.**
Verifica que el desafío esté en la ruta exacta, que el APK tenga el mismo package y esté firmado con la clave registrada. Construye un APK limpio y vuelve a subirlo.

**La app sigue instalada pero no se actualiza.**
Es esperado si no está registrada: usa ADB, la cuenta limitada o advanced flow. Lo ya instalado no se borra por la verificación, pero las actualizaciones pueden bloquearse.

**Desinstalé/reseteé y no puedo reinstalar.**
Usa ADB, la cuenta limitada o advanced flow. La app tiene `allowBackup=false`; configuración, key e historial se pierden.

**Perdí el keystore.**
No podrás firmar actualizaciones ni completar la prueba. Restaura una copia; si no existe, tendrás que crear un paquete nuevo y perder los datos.

**Play Protect muestra aviso de desarrollador no verificado.**
Es distinto de malware. Registra la app o usa advanced flow. Play Protect puede seguir escaneando cualquier app.

**Una app bancaria no funciona con Opciones de desarrollador.**
El advanced flow se puede activar y luego desactivar las Opciones de desarrollador; según Google, no hace falta mantenerlas. La cuenta limitada no requiere modo desarrollador.

**Cambié de teléfono.**
Autoriza el nuevo dispositivo en la cuenta limitada o instala por ADB. No hay restauración automática.

---

## 9. Seguridad y copias de seguridad

- No subas el keystore ni las contraseñas al repositorio.
- Guarda `debug.keystore` o `release.jks` en un gestor de contraseñas y una copia offline.
- La huella SHA-256 es pública; la clave privada no se comparte.
- Usa una cuenta Google dedicada, con 2FA y códigos de recuperación.
- No incluyas API keys reales (Firecrawl, OpenCode) en APKs que compartas o subas a la consola.
- La cuenta limitada usa un perfil de pagos con nombre/dirección legal; no es anónima al 100 %, pero no pide documento de identidad.
- Antes de desinstalar para cambiar de firma, anota la configuración, la key de OpenCode, la de Firecrawl, tu nombre y los recuerdos importantes.
- Si la app crece, añade exportar/importar memoria y configuración para no depender de `allowBackup`.

---

## 10. Checklist para 2027

- [ ] `applicationId` definitivo.
- [ ] Clave elegida: debug personal o release.
- [ ] Keystore y contraseñas respaldados.
- [ ] Huella SHA-256 anotada.
- [ ] Cuenta limitada creada (Google, 2FA, perfil de pagos, correo).
- [ ] Paquete registrado.
- [ ] Prueba de propiedad subida.
- [ ] Estado **Registered**.
- [ ] Dispositivos autorizados.
- [ ] Instalación/actualización por ADB probada.
- [ ] Configuración y keys anotadas.
- [ ] Recordatorio en calendario antes de 2027.
- [ ] Si publicas: cuenta completa/Play/F-Droid y políticas revisadas.

---

## 11. Enlaces oficiales

- Verificación de desarrolladores: https://developer.android.com/developer-verification
- Guía de Android Developer Console: https://developer.android.com/developer-verification/guides/android-developer-console
- Cuenta de distribución limitada: https://developer.android.com/developer-verification/guides/limited-distribution
- Cuenta completa: https://developer.android.com/developer-verification/guides/full-distribution
- Preguntas frecuentes: https://developer.android.com/developer-verification/guides/faq
- Crear cuenta: https://android.google.com/developerconsole/developers
- Consola: https://android.google.com/developerconsole/
- Play Console: https://play.google.com/console
- F-Droid: https://f-droid.org/
- Campaña Keep Android Open: https://keepandroidopen.org/es/

---

## 12. Notas finales

- El plan oficial actual bloquea **instalaciones y actualizaciones** desde tiendas participantes y, globalmente, en 2027; **no hay una promesa de desinstalación retroactiva**, pero tampoco una inmunidad permanente por haber instalado la app en 2026.
- **ADB sigue exento**; es la vía más segura para uso personal.
- La **cuenta limitada** es gratis, sin ID y cubre hasta 50 dispositivos; es la mejor opción para instalar el APK directamente y compartir con familia.
- El **advanced flow** existe, pero con espera de 24 h, avisos y actualizaciones condicionadas.
- La campaña **Keep Android Open** tiene razón en el fondo: la distribución pública, F-Droid y el software libre son los más afectados. Para uso personal hay vías, pero con más fricción que antes.
- Las políticas pueden cambiar. Antes de 2027, revisa la consola y las preguntas frecuentes oficiales.
