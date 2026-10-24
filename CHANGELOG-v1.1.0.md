# VerificaPE v1.1.0 - Changelog

## Resumen

Version mayor con rediseno financiero del panel web y la app Android, fiabilidad en segundo plano, exportacion a Excel, API para bots, y multiples correcciones de seguridad y rendimiento.

---

## App Android

### Nuevo diseno financiero (glassmorphism)
- Pantalla de inicio redisenada con tarjeta de ingresos del dia (total en soles y cantidad de pagos)
- Historial de pagos agrupado por dia con monto, nombre del pagador, hora e insignia de color por billetera
- Fondo degradado violeta-noche con tarjetas translucidas estilo glassmorphism
- Icono de cada aplicacion extraido del telefono (logo real de Yape, Plin, SIP, etc.)

### Filtro de pagos reales (PaymentFilter)
- Deteccion inteligente de pagos: solo cuenta notificaciones con verbo de recepcion ("te envio un pago", "recibiste un pago", "re pago") Y un monto "S/"
- Las promociones y ofertas de prestamos no se cuentan como ingresos
- Opcion "Enviar SOLO pagos al panel" activable en Ajustes

### Fiabilidad en segundo plano (Guardian)
- Servicio en primer plano persistente que impide que el sistema elimine el proceso al cerrar la app o bloquear el telefono
- Notificacion permanente con el total del dia en la barra de estado
- Cola de reintentos: las notificaciones que no llegaron a la web se reenvian automaticamente cada 60 segundos
- Reenganche forzado del lector de notificaciones al abrir la app (corrige el desenganche tras actualizaciones)
- Exencion de optimizacion de bateria y de restriccion de datos en segundo plano, con botones directos en Ajustes
- Wakelock durante el envio para evitar suspension de CPU con pantalla bloqueada

### Exportar a Excel
- Boton que abre un dialogo con selector de rango de fechas (desde / hasta con calendario)
- Genera un archivo .xlsx real con dos hojas:
  - Hoja 1 "Pagos": Fecha, Hora, Nombre, Monto, Billetera, App con encabezado verde, filas alternadas y formato monetario
  - Hoja 2 "Resumen": cantidad y total por billetera, ordenado por monto, con fila de TOTAL GENERAL
- Sin dependencias externas: el xlsx se genera a mano con XML comprimido

### Seccion Spam (diagnostico)
- Muestra las notificaciones capturadas que NO se clasificaron como pago
- Boton "Es pago" para corregir manualmente un descarte del filtro
- Boton "Probar lector" que publica una notificacion de prueba local

### Multiples emparejamientos (multi-QR)
- Conecta varios paneles web a la vez escaneando cada QR
- Cada notificacion se envia cifrada a todos los paneles conectados
- Lista de canales con botones Probar y Eliminar por canal

### Escaner QR en vertical
- La camara del escaner ya no fuerza el modo horizontal (se sobreescribe la orientacion de la libreria)

### Otros
- Filtro de notificaciones por fecha (Hoy / 7 dias / Mes / Todo)
- Boton circular para dormir el servicio sin desinstalar
- Iconos de menu lateral con SVG
- Deduplicacion quirurgica: solo descarta resumen-de-grupo vs hija, nunca dos pagos reales

---

## Panel Web

### Nuevo diseno financiero
- Fondo degradado violeta-noche con tarjetas de vidrio (glassmorphism con blur optimizado)
- Strip superior con ingresos del dia (total y cantidad de pagos, en vivo)
- Tarjetas de pago con insignia de color de billetera, monto en verde y nombre del pagador
- Notificaciones no-pago atenuadas con etiqueta "OTRO"
- Filtros: Pagos / Spam / Todo + multiseleccion de billeteras (chips acumulables)
- Indicador de conexion con icono SVG (rayo / circulo / triangulo)

### Fecha del pago en la tarjeta
- La fecha y hora del yapeo (cuando ocurrio en el telefono) se muestra de forma prominente en un badge verde
- La hora de llegada al servidor se muestra discreta abajo en cursiva

### Dominio propio configurable
- Nueva tarjeta "Tu dominio o tunel" en la seccion Emparejamiento
- Campo para introducir un dominio fijo que hace que el QR nunca cambie
- Boton para restaurar la URL automatica
- Soporte para TUNNEL_TOKEN de Cloudflare en docker-compose

### API v1 (para bots e integraciones)
- Tokens de API pausables y revocables desde el panel
- Endpoints:
  - GET /api/v1/ping
  - GET /api/v1/messages (mensajes descifrados, con filtros de fecha)
  - GET /api/v1/stats
- Cliente Node.js incluido (cliente-verificape.js) con filtros por palabra clave, nombre, monto, fecha y app
- La clave AES se guarda envuelta (cifrada) con el propio token: la base de datos nunca contiene claves en texto claro

### Seguridad
- Webhook idempotente: el mismo mensaje (canal + hora de notificacion) solo se guarda una vez
- Clave AES eliminada de la interfaz del panel
- Credenciales de firma movidas a keystore.properties (excluido de git)

### Sonido de notificacion
- Campanilla configurable al recibir un pago (activable/desactivable con boton de prueba)

---

## Infraestructura

- Dockerfile y docker-compose.yml incluidos (con perfil "tunnel" para acceso publico)
- Tuel gratuito de Cloudflare integrado
- Soporte para TUNNEL_TOKEN (tunel con nombre, URL fija)
- Tests automatizados: E2E del canal cifrado (12 verificaciones) y API v1 (27 verificaciones)
- Script de generacion de sonido incluido (generar-sonido.js)

---

## Correcciones

- Parpadeo del feed web cada 5 segundos (solo se repinta si algo cambio)
- Crash al renderizar el primer pago (ViewHolders separados por tipo de fila)
- Notificaciones duplicadas en el panel (deduplicacion servidor + idempotencia)
- Deduplicacion que descartaba pagos reales de la misma persona y monto
- Latencia de 10 segundos a menos de 1 segundo (push SSE)
- Escaner QR que forzaba modo horizontal
- Credenciales de firma expuestas en el repositorio
- Optimizacion de rendimiento web (blur solo en sidebar/topbar/strip)
