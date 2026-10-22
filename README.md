# ✅ VerificaPE

**Verifica pagos (Yape, Plin, banca…) al instante y sin dejarte engañar.**

VerificaPE son dos piezas que trabajan juntas:

- una **app Android** que se instala en el celular del **dueño de la billetera**,
  lee las notificaciones de pago (solo de las apps que tú actives) y las envía
  **cifradas de extremo a extremo** a tu servidor, y
- un **panel web** (en Docker) donde quien atiende el cajón ve cada pago
  **al instante** (push en vivo, menos de 1 segundo) y **con sonido de caja**
  🔔 — verificar un Yape queda tan rápido como contar un billete.

```
┌──────────────┐   QR (clave AES + tokens)   ┌──────────────┐
│  App Android │ ◄──────────────────────────►│   Página web │
│  (dueño de   │                             │  (caja)      │
│   la Yape)   │      AES-256-GCM           │              │
│ Room (DB)    │────────────────────────────►│  descifra    │
│  historial   │   POST /api/webhook/:id    │  en navegador │
│  propio      │   (solo ciphertext)        │  ┌─────────┐ │
└──────────────┘        ┌───────────┐       │  │ SQLite  │ │
                        │  Docker 🐳 │──────►│  │ (DB)    │ │
                        │ cloudflared│  push │  └─────────┘ │
                        │  túnel 🌐  │  SSE   └──────────────┘
                        └───────────┘
```

## ✅ Funciones

| Requisito | Cómo se cumple |
|---|---|
| App Android que lee notificaciones | `NotificationListenerService` (Kotlin nativo) |
| Envío a la web vía **webhook** | `POST /api/webhook/:canal` |
| App y web con **su propia DB** | Room/SQLite local · SQLite en el contenedor (volumen persistente) |
| **Ignorar OTP por SMS** | Filtro SIEMPRE activo: bloquea la app de SMS y notificaciones con apariencia de código OTP. Sin guardar ni enviar el contenido |
| Ver **apps instaladas** y activar/desactivar cada una | Sección «Apps monitoreadas» con interruptor por app |
| **Todo desactivado por defecto** | Cada app nueva entra con `enabled = false` |
| **Emparejar escaneando un QR** | La web genera el QR; la app lo escanea (o pegas el código) |
| **Varios QR a la vez** (multi-canal) | Escanea todos los paneles que quieras: cada notificación se envía cifrada a TODOS |
| **Cifrado extremo a extremo** | AES-256-GCM; la clave solo viaja en el QR; el servidor solo ve ciphertext |
| **Menú lateral izquierdo, vertical y retráctil** | App: `DrawerLayout` + `NavigationView` · Web: sidebar colapsable |
| ⚡ **Latencia < 1 segundo** | Push en vivo por **SSE** (test real: 7–15 ms) + sondeo de respaldo |
| 🔔 **Sonido de notificación** | Campanilla «di-ding» al llegar un pago (activable, con botón de prueba) |
| 🐳 **Docker** | `Dockerfile` + `docker compose` (con túnel opcional incluido) |
| 🌐 **Túnel gratuito Cloudflare** | `start.ps1` / perfil `tunnel` del compose |
| 🔌 **API de acceso** | Sección «API»: token con pausa/revocación + `cliente-verificape.js` con filtros (palabra, nombre, monto, fecha, app) |

## 🚀 Puesta en marcha

### 1) Web con Docker (recomendado)

Requisitos: Docker (Desktop en Windows / Engine en Linux) y Node 18+ solo
para el test.

```bash
cd web
docker compose up -d --build        # construye y arranca el panel en :3000
docker compose logs -f verificape   # espera el banner "escuchando"
```

La base de datos queda en `web/data/` (volumen montado) y sobrevive a
reinicios y actualizaciones de la imagen.

**Con túnel público gratis** (el teléfono funciona hasta con datos móviles):

```bash
docker compose --profile tunnel up -d --build
docker compose logs tunnel          # copia la URL https://xxx.trycloudflare.com
```

Pega esa URL en el panel (Emparejamiento → «Dirección del servidor») o ponla
en `web/.env` como `TUNNEL_URL=…` y recrea el contenedor: el QR la tomará
solo. Las URLs de Quick Tunnel cambian al reiniciar; para una fija crea un
túnel con nombre con tu cuenta de Cloudflare.

En Windows también puedes usar `start.ps1` (o doble clic en `start.bat`),
que arranca servidor + túnel y muestra la URL sin Docker.

### 2) App Android

**APK ya compilada y firmada:** `dist/VerificaPE-1.1.0.apk`
(keystore `android/app/notibridge.keystore`, alias `notibridge`,
contraseña `notibridge2026` — clave personal inventada). Cópiala al teléfono,
instálala como «app desconocida» (no uses SAI: es para APKs divididas y falla
con content:// URIs) y:

1. Ábrela → **Conceder acceso** a notificaciones (ajustes del sistema).
2. Menú → **Emparejar** → escanea el **QR** del panel web.
3. Menú → **Apps monitoreadas** → activa **Yape** (y las que quieras).
   *Nada se lee hasta que tú lo actives.*
4. En la web: pulsa una vez **Probar sonido** 🔔 (el navegador exige un clic
   antes de reproducir audio; luego suena solo).

Para recompilar: abre `android/` en Android Studio o
`gradle assembleRelease` (JDK 17). Mantener `applicationId` te deja instalar
actualizaciones sin desinstalar.

### 3) Verificación

```bash
cd web
docker compose up -d
node test-e2e.js     # 12 pruebas: cifrado, webhook, push SSE (<1 s), borrados…
```

## 🧠 Flujo técnico

1. El **navegador** crea `canal (UUID) + clave AES + tokens` y registra en el
   servidor **solo los hashes SHA-256** de los tokens.
2. El **QR** lleva: URL del servidor, canal, clave AES y token de escritura.
3. La **app** cifra cada notificación permitida con AES-256-GCM y la POSTea
   al webhook (`X-Write-Token`).
4. El **servidor** (Docker) valida el token, guarda `{iv, ct}` en SQLite y
   hace **push SSE instantáneo** a todos los paneles conectados.
5. El **navegador** descifra con WebCrypto, pinta la tarjeta y **suena la
   campanilla**. El sondeo cada 5 s es solo respaldo.
6. OTP/SMS: bloqueado antes de cifrar — nunca salen del teléfono.

## ⚙️ Ajustes del panel

- **Tiempo real:** pill «⚡ En vivo» = push SSE activo; «● Conectado» = sondeo.
- **Sonido:** Ajustes → Sonido y alertas (Activado/Desactivado + Probar sonido).
- **Estadísticas:** por app, por dispositivo, últimas 24 h.
- **Tema:** oscuro/claro. Menú lateral: colapsable con «», deslizante en móvil.

## ⚠️ Notas y limitaciones

- La latencia total es la del viaje teléfono→servidor (LAN ≈ instantáneo;
  túnel ≈ 0.1–0.5 s) + unos milisegundos de SSE.
- Si tu teléfono demora en *emitir* la notificación (estilo Xiaomi/Huawei),
  libera la optimización de batería para VerificaPE.
- `QUERY_ALL_PACKAGES` es necesario para listar apps (normal en APK propia).
- `usesCleartextTraffic="true"` está activo para pruebas en LAN HTTP;
  elimínalo si el servidor va tras HTTPS.
- Mensajes: hasta 5 000 por canal en el servidor (recorte automático).

## 🔌 API de acceso (bots y servicios)

El panel trae una sección **API** en el menú lateral donde generas un token
con un clic, lo **pausas** temporalmente o lo **revocas**.

Desde la v1.2 la API entrega los mensajes **ya descifrados**: el consumidor
solo necesita el token (el transporte va protegido por HTTPS). Al crear el
token, el servidor guarda una copia de la clave del canal para poder
descifrar (el panel sigue siendo E2E; está explicado en la propia UI).

**Endpoints v1** (autenticación con cabecera `X-API-Token`):

```
GET /api/v1/ping                                 → estado del servicio
GET /api/v1/messages?since=&from=&to=&limit=    → mensajes DESCIFRADOS (fecha en ms)
GET /api/v1/stats                                → total, ids y fechas extremas
Token en pausa → 403 · Token inválido o revocado → 401
```

Filtros de **fecha/rango/límite** corren en el servidor. Los de texto
(**palabra clave, nombre, monto, app**) están listos en la librería
`web/cliente-verificape.js` (Node 18+, sin dependencias, solo token):

```js
const { VerificaPE } = require('./cliente-verificape')

const vpe = new VerificaPE({
  baseUrl:  'https://tu-servidor.com',   // copia del panel, sección API
  apiToken: 'xxxx'                        // token de API
})

// ¿Llegó el Yape de S/ 25 de María hoy?
const pagos = await vpe.mensajes({ monto: 25, nombre: 'Maria', fecha: '2026-10-21' })
const ultimo = await vpe.ultimo()          // la notificación más reciente
```

Filtros disponibles: `q` (palabra clave), `nombre`, `monto` (S/), `fecha`
(día), `app`, `desde`, `hasta`, `desdeId`, `limite` — combinables entre sí.
Prueba todo con `npm run test:api` (26 verificaciones).

## 📦 Publicar en GitHub

El proyecto ya trae `.gitignore` raíz — quedan fuera los secretos
(`notibridge.keystore`, `web/.env`) y los binarios (`dist/`, `node_modules/`,
`web/data/`):

```bash
git init
git add .
git commit -m "VerificaPE v1.2: panel E2E en tiempo real + API para bots"
# crea el repo en github.com y:
git remote add origin https://github.com/TU_USUARIO/verificape.git
git branch -M main
git push -u origin main
```

Para distribuir la APK usa **GitHub Releases** (no la subas al git). Los que
clonen el repo podrán compilar con su propia clave: el build firma con la
clave debug si no encuentran `notibridge.keystore`.

## ⚖️ Uso responsable

VerificaPE está pensado para el **dueño de la billetera** que quiere
verificar sus propios pagos en su propio negocio: notificaciones de su
propio teléfono hacia su propio panel, cifradas. Instálalo solo en
dispositivos propios o con consentimiento explícito del dueño. Reenviar
notificaciones de terceros sin permiso puede ser ilegal.
