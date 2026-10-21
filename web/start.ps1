# ============================================================
#  NotiBridge - Arranque con tunel gratuito de Cloudflare
#  Uso:    .\start.ps1      (o doble clic en start.bat)
#  Detener: Ctrl+C (detiene servidor y tunel)
# ============================================================
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# 0) Limpiar tuneles de sesiones anteriores (mejor esfuerzo)
Get-CimInstance Win32_Process -Filter "Name='cloudflared.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -match 'localhost:3000' } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

# 1) Localizar cloudflared (instalado, en PATH o descargado)
$cloudflared = $null
if (Test-Path 'C:\Cloudflared\cloudflared.exe') {
    $cloudflared = 'C:\Cloudflared\cloudflared.exe'
} elseif (Get-Command cloudflared -ErrorAction SilentlyContinue) {
    $cloudflared = (Get-Command cloudflared).Source
} else {
    $local = Join-Path $root 'cloudflared\cloudflared.exe'
    if (-not (Test-Path $local)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $local) | Out-Null
        Write-Host 'Descargando cloudflared (solo la primera vez)...'
        curl.exe -sL --fail -o $local 'https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-windows-amd64.exe'
    }
    $cloudflared = $local
}

# 2) Arrancar el Quick Tunnel (gratis, sin cuenta de Cloudflare)
$out = Join-Path $env:TEMP 'notibridge-cf-out.log'
$err = Join-Path $env:TEMP 'notibridge-cf-err.log'
Remove-Item $out, $err -ErrorAction SilentlyContinue
Write-Host 'Iniciando tunel de Cloudflare...'
$proc = Start-Process -FilePath $cloudflared `
    -ArgumentList @('tunnel', '--url', 'http://localhost:3000', '--no-autoupdate') `
    -RedirectStandardOutput $out -RedirectStandardError $err `
    -PassThru -WindowStyle Hidden

$tunnelUrl = ''
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Milliseconds 500
    if ($proc.HasExited) { break }
    $txt = ''
    if (Test-Path $out) { $txt += (Get-Content $out -Raw -ErrorAction SilentlyContinue) }
    if (Test-Path $err) { $txt += (Get-Content $err -Raw -ErrorAction SilentlyContinue) }
    if ($txt -match 'https://[a-zA-Z0-9-]+\.trycloudflare\.com') {
        $tunnelUrl = $Matches[0]
        break
    }
}

if ($tunnelUrl) {
    Write-Host ''
    Write-Host '== TUNEL ACTIVO ====================================' -ForegroundColor Green
    Write-Host "  $tunnelUrl" -ForegroundColor Cyan
    Write-Host '====================================================' -ForegroundColor Green
    Write-Host '  Abre esa URL en tu navegador (tambien sirve para el QR).'
} else {
    Write-Host 'No se pudo obtener la URL del tunel; continuando solo en localhost.' -ForegroundColor Yellow
}

# 3) Servidor web (recibe la URL del tunel via TUNNEL_URL)
try {
    $env:TUNNEL_URL = $tunnelUrl
    Write-Host ''
    Write-Host 'Panel local : http://localhost:3000'
    Write-Host 'Detener con Ctrl+C'
    Write-Host ''
    node server.js
} finally {
    if ($proc -and -not $proc.HasExited) {
        Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
    }
}
