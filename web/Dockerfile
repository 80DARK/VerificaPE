# ─────────────────────────────────────────────
#  VerificaPE Web · Dockerfile
#  Construye y sirve el panel + webhook + SSE
# ─────────────────────────────────────────────
FROM node:20-bookworm-slim

WORKDIR /app
ENV NODE_ENV=production

# Dependencias primero (capa cacheable)
COPY package.json package-lock.json* ./
RUN npm install --omit=dev

# El resto de la app (web pública + sonido + servidor)
COPY server.js ./
COPY public ./public

EXPOSE 3000

# Comprobación de vida: el servidor responde al ping
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://localhost:3000/api/ping').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "server.js"]
