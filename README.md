# VerificaPE 🇵🇪

> **Verificación en tiempo real y anti-estafa para pagos digitales en Perú.**
> Cero capturas falsas, cero intermediarios.

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)
[![Docker](https://img.shields.io/badge/Docker-Ready-2496ED?logo=docker&logoColor=white)](https://www.docker.com/)
[![Android](https://img.shields.io/badge/Android-Kotlin-3DDC84?logo=android&logoColor=white)](https://kotlinlang.org/)
[![Backend](https://img.shields.io/badge/Backend-Node.js%20%7C%20Express-339933?logo=node.js&logoColor=white)](https://nodejs.org/)

---

## 1. El problema que resuelve

En el comercio peruano diario (bodegas, minimarkets, restaurantes, ferreterías…),
una de las estafas más frecuentes es el uso de **capturas adulteradas** o apps
clonadas de billeteras como Yape o Plin: el estafador simula una transferencia
exitosa mientras el cajero, por prisa, entrega la mercadería.

**VerificaPE** resuelve esto de raíz validando el pago **desde el dispositivo
que lo recibe**: la app intercepta la notificación legítima en el celular del
negocio, la filtra, la cifra y la muestra al instante en la pantalla del cajero.
Ya no hace falta confiar en la pantalla del cliente.

## 2. Características

| Componente | Característica | Descripción |
| :--- | :--- | :--- |
| App Android | **Seguridad por defecto** | Lee notificaciones SOLO de las apps que actives; todo viene desactivado |
| App Android | **Filtro Anti-OTP permanente** | Bloquea siempre la app de SMS y cualquier código de verificación: no se guarda ni se envía |
| App Android | **Cifrado E2E** | AES-256-GCM con clave compartida solo por QR; el servidor solo ve ciphertext |
| App Android | **Botón de dormir** | Interruptor circular en Inicio para suspender la escucha sin desinstalar nada |
| App Android | **Multi-QR** | Conecta varios paneles a la vez (cada uno con su propia clave) |
| App Android | **Persistencia** | Sigue escuchando tras cerrar la app o reiniciar (servicio del sistema + exención de batería) |
| App Android | **DB propia** | Historial local con Room, independiente de la web |
| Panel Web | **Push en vivo** | Server-Sent Events: cada pago aparece en menos de 1 segundo |
| Panel Web | **Estilo financiero glassmorphism** | Strip de ingresos del día, tarjetas de pago con monto/pagador/billetera, spam atenuado |
| Panel Web | **Filtros múltiples** | Pagos/Spam/Todo + multiselección de billeteras (Yape y Plin a la vez, etc.) |
| Panel Web | **Dominio propio configurable** | Campo «Dirección del servidor»: pon tu dominio fijo y nunca más re-escanees el QR |
| Panel Web | **Sonido de caja** 🔔 | Campanilla configurable al llegar un pago, con botón de prueba |
| Panel Web | **Menú lateral retráctil** | Sidebar izquierda colapsable (deslizante en móvil), tema claro/oscuro |
| Panel Web | **Autohospedado** | Docker con Node.js, Express y SQLite (volumen persistente) |
| Panel Web | **Túnel gratuito** | Perfil `tunnel` con cloudflared: URL pública HTTPS sin cuenta |
| API | **API v1 con token** | Tokens pausables y revocables; mensajes ya descifrados; clave AES guardada envuelta con el propio token |
| API | **Cliente Node.js** | `cliente-verificape.js` sin dependencias, con filtros por palabra clave, nombre, monto, fecha y app |

## 3. Arquitectura

```text
  [ Celular del negocio (Android) ]
  ┌───────────────────────────────────────────────────────┐
  │  Notificación legítima (Yape / Plin / banco)          │
  │            │                                          │
  │            ▼                                          │
  │  [ Filtro Anti-OTP: SMS y códigos 2FA descartados ]   │
  │            │                                          │
  │            ▼                                          │
  │  [ Historial local (Room) ]                           │
  │            │                                          │
  │            ▼                                          │
  │  [ Cifrado AES-256-GCM · clave compartida por QR ]    │
  └────────────┬──────────────────────────────────────────┘
               │ HTTPS (payload cifrado)
               ▼
   [ Túnel Cloudflare (opcional, cloudflared) ]
               │
               ▼
  [ Servidor / Panel Web (docker compose) ]
  ┌───────────────────────────────────────────────────────┐
  │  Node.js + Express · SQLite (mensajes cifrados)       │
  │            │                                          │
  │      ┌─────┴──────┐                                   │
  │      ▼            ▼                                    │
  │ [ Push SSE ]   [ API v1 con token ]                    │
  │  (< 1 s)        (mensajes descifrados)                │
  │      │            │                                   │
  │      ▼            ▼                                    │
  │ [ Panel caja ]  [ Bot / integración ]                  │
  │   + sonido 🔔   + cliente-verificape.js                │
  └───────────────────────────────────────────────────────┘
```

## 4. Requisitos

- **Web**: Docker (Desktop en Windows / Engine en Linux). Node.js 18+ solo para
  los tests.
- **Android**: la APK precompilada (ver *Instalación de la app*) o Android
  Studio con JDK 17 para compilar.
- **Red**: el teléfono y el servidor deben poder comunicarse (misma red, IP
  local o el túnel público de Cloudflare).

## 5. Instalación de la web (Docker)

```bash
cd web
docker compose up -d --build          # panel en http://localhost:3000
```

La base de datos SQLite queda en `web/data/` (volumen) y sobrevive a
reinicios y actualizaciones de la imagen.

**Con túnel público HTTPS gratuito** (el teléfono funciona incluso con
datos móviles):

```bash
docker compose --profile tunnel up -d --build
docker compose logs tunnel            # copia la URL https://xxx.trycloudflare.com
```

Pega esa URL en el panel («Emparejamiento → Dirección del servidor») o
defínela en `web/.env` como `TUNNEL_URL=…` y recrea el contenedor. En
Windows también puedes usar `start.ps1` sin Docker.

> Las URLs de Quick Tunnel cambian al reiniciar; para una URL fija crea un
> túnel con nombre con tu cuenta de Cloudflare.

### Dominio propio (sin re-escanear QR nunca más)

1. En **Cloudflare Zero Trust → Networks → Tunnels** crea un túnel con
   nombre, apunta un DNS de tu dominio (ej. `caja.tudominio.com`) a él y
   copia el **TOKEN** del túnel.
2. En `web/.env` pon `TUNNEL_TOKEN=eyJ...` y en `docker-compose.yml`
   cambia el `command` del servicio `tunnel` por
   `command: tunnel run --token ${TUNNEL_TOKEN}`.
3. Abre el panel y en **Emparejamiento → Dirección del servidor** escribe
   `https://caja.tudominio.com` (el panel indica «⚙️ Dominio manual ACTIVO»).

Con la URL fija, el QR apunta siempre a tu dominio: reinicia Docker o la PC
cuando quieras, **el teléfono y el QR siguen válidos**. El botón
«↺ Usar URL automática» vuelve al comportamiento automático.

## 6. Instalación de la app Android

1. Descarga la APK desde **GitHub Releases** del repositorio.
2. Instálala en el teléfono del dueño de la billetera (permiso «instalar apps
   desconocidas»). *Ojo: SAI da problemas con APKs normales; usa el instalador
   del sistema.*
3. Ábrela → **Conceder acceso** a notificaciones (ajustes del sistema).
4. En **Ajustes → Liberar de la optimización de batería** (fiabilidad en
   Xiaomi/Huawei).

Los que compilan desde el código: `android/keystore.properties` guarda las
credenciales de firma **localmente** (está excluido de git). Sin ese archivo,
las builds release se firman con la clave debug; crea tu propio keystore con
`keytool` si quieres firmar con clave propia:

```bash
keytool -genkeypair -keystore android/app/mi-clave.keystore \
        -alias verificape -keyalg RSA -keysize 2048 -validity 10950
# y en android/keystore.properties:
#   storeFile=app/mi-clave.keystore
#   storePassword=...  keyAlias=...  keyPassword=...
```

## 7. Emparejamiento (QR)

1. Abre el panel web: el QR de emparejamiento se genera solo.
2. En la app: menú lateral → **Emparejar** → **Escanear código QR**
   (funciona en vertical) — o pega el código `notibridge://pair?…` a mano.
3. Menú → **Apps monitoreadas** → activa Yape (o las apps que quieras).
   *Nada se lee hasta que tú lo actives.*
4. Pulsa **Probar sonido** en la web una vez (el navegador exige un clic
   antes de reproducir audio) y usa **Probar** en la app para enviar un
   mensaje de prueba.
5. ¿Varias cajas? Escanea el QR de cada panel: cada notificación llegará a
   todas. El botón circular de Inicio duerme la escucha cuando cierres.

## 8. API v1 (bots e integraciones)

El panel tiene una sección **API** para crear tokens con un clic
(pausables y revocables). Los mensajes se entregan **ya descifrados**;
el consumidor solo necesita el token (transporte por HTTPS).

| Endpoint | Descripción |
|---|---|
| `GET /api/v1/ping` | Estado del servicio |
| `GET /api/v1/messages?since=&from=&to=&limit=` | Mensajes descifrados (fechas en ms epoch) |
| `GET /api/v1/stats` | Total, último id y fechas extremas |

Autenticación con la cabecera `X-API-Token`. Token en pausa → `403` ·
token inválido o revocado → `401`.

Ejemplo con el cliente de Node (`web/cliente-verificape.js`, sin dependencias):

```js
const { VerificaPE } = require('./cliente-verificape')

const vpe = new VerificaPE({
  baseUrl: 'https://tu-servidor.com',   // copia del panel, sección API
  apiToken: 'xxxx'                        // token de API
})

// ¿Llegó el Yape de S/ 25 de María hoy?
const pagos = await vpe.mensajes({ monto: 25, nombre: 'Maria', fecha: '2026-10-21' })
const ultimo = await vpe.ultimo()
```

Filtros combinables: `q` (palabra clave), `nombre`, `monto` (S/), `fecha`
(día), `app`, `desde`, `hasta`, `desdeId`, `limite`.

## 9. Seguridad y privacidad

- **E2E en el panel**: la clave AES-256 solo viaja dentro del QR; el servidor
  almacena los mensajes como ciphertext y el navegador descifra con WebCrypto.
- **OTP blindado**: la app de SMS y las notificaciones con apariencia de código
  de verificación se descartan SIEMPRE, sin guardar su contenido.
- **API sin secretos en la DB**: al crear un token, la clave del canal se guarda
  **envuelta** (AES-256-GCM) con una clave derivada del propio token; se
  desenvuelve solo en memoria durante cada petición. Una fuga de la base de
  datos no revela claves.
- **Advertencia honesta**: un servidor *activo y malicioso* podría registrar
  tokens o respuestas en el modo API descifrada. Si tu modelo de amenaza
  incluye al propio servidor, no actives la API (el panel sigue siendo E2E).

## 10. Pruebas

```bash
cd web
docker compose up -d
npm test          # E2E del canal cifrado (12 verificaciones)
npm run test:api  # API v1 + filtros + pausa + seguridad en disco (27)
```

## 11. Uso responsable

VerificaPE está pensado para que el **dueño de la billetera** verifique sus
propios pagos en su propio negocio: notificaciones de su propio teléfono
hacia su propio panel, cifradas. Instálalo solo en dispositivos propios o
con consentimiento explícito del dueño. Reenviar notificaciones de terceros
sin permiso puede ser ilegal en tu jurisdicción.

## 13. Licencia

MIT — ver [LICENSE](LICENSE).
