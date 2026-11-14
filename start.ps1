# FileGrid PPC - one-command start for Windows PowerShell (no bash required).
#   init-env -> gen-certs -> docker compose up -d --build -> wait healthy -> URLs
#
#   .\start.ps1          full mode: one worker per category (bulkhead demo)
#   .\start.ps1 --lite   lite mode: a single worker-all (machines with ~6 GB for Docker)
$ErrorActionPreference = 'Stop'
Set-Location -Path $PSScriptRoot

$mode = 'full'
foreach ($arg in $args) {
    if ($arg -eq '--lite' -or $arg -eq '-lite') {
        $mode = 'lite'
        $env:COMPOSE_PROFILES = 'lite'
    } else {
        Write-Host "Unknown option: $arg (supported: --lite)" -ForegroundColor Red
        exit 1
    }
}

function Fail([string]$message) {
    Write-Host $message -ForegroundColor Red
    exit 1
}

function New-RandomBytes([int]$count) {
    $bytes = New-Object byte[] $count
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $rng.Dispose()
    return $bytes
}

# Replaces a __GENERATE_*__ placeholder in a template line with a random value.
function Format-EnvLine([string]$line) {
    if ($line.Contains('__GENERATE_HEX_24__')) {
        $hex = -join (New-RandomBytes 24 | ForEach-Object { $_.ToString('x2') })
        return $line.Replace('__GENERATE_HEX_24__', $hex)
    }
    if ($line.Contains('__GENERATE_BASE64_32__')) {
        return $line.Replace('__GENERATE_BASE64_32__', [Convert]::ToBase64String((New-RandomBytes 32)))
    }
    return $line
}

function Initialize-Env {
    $template = Join-Path $PSScriptRoot '.env.example'
    $target = Join-Path $PSScriptRoot '.env'
    $utf8 = New-Object System.Text.UTF8Encoding($false)
    $templateLines = [System.IO.File]::ReadAllLines($template)

    if (-not (Test-Path $target)) {
        $rendered = $templateLines | ForEach-Object { Format-EnvLine $_ }
        [System.IO.File]::WriteAllText($target, (($rendered -join "`n") + "`n"), $utf8)
        Write-Host 'init-env: created .env with freshly generated secrets'
        return
    }

    # Existing file: never overwrite, only append variables that are missing.
    $existingKeys = [System.IO.File]::ReadAllLines($target) |
        Where-Object { $_ -match '^[A-Za-z_][A-Za-z0-9_]*=' } |
        ForEach-Object { $_.Split('=')[0] }
    $added = 0
    foreach ($line in $templateLines) {
        if ($line -notmatch '^[A-Za-z_][A-Za-z0-9_]*=') { continue }
        $key = $line.Split('=')[0]
        if ($existingKeys -notcontains $key) {
            [System.IO.File]::AppendAllText($target, ((Format-EnvLine $line) + "`n"), $utf8)
            Write-Host "init-env: added missing variable $key"
            $added++
        }
    }
    if ($added -eq 0) { Write-Host 'init-env: .env already exists and is complete (left untouched)' }
}

function New-Certificate {
    $certDir = Join-Path $PSScriptRoot 'infra\certs'
    $cert = Join-Path $certDir 'localhost.pem'
    $key = Join-Path $certDir 'localhost-key.pem'
    New-Item -ItemType Directory -Force -Path $certDir | Out-Null

    if ((Test-Path $cert) -and (Test-Path $key)) {
        Write-Host 'gen-certs: certificate already exists (left untouched)'
        return
    }

    if (Get-Command mkcert -ErrorAction SilentlyContinue) {
        & mkcert -cert-file $cert -key-file $key localhost 127.0.0.1 ::1
        if ($LASTEXITCODE -ne 0) { Fail 'gen-certs: mkcert failed.' }
        Write-Host 'gen-certs: created a locally trusted certificate with mkcert'
        return
    }

    # No mkcert: self-signed certificate. Use a local openssl if there is one
    # (Git for Windows ships it), otherwise run openssl in a throwaway container.
    $opensslArgs = @('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '825',
        '-keyout', 'localhost-key.pem', '-out', 'localhost.pem',
        '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost,IP:127.0.0.1')
    $openssl = Get-Command openssl -ErrorAction SilentlyContinue
    $gitOpenssl = Join-Path $env:ProgramFiles 'Git\usr\bin\openssl.exe'

    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # openssl prints progress on stderr
    Push-Location $certDir
    try {
        if ($openssl) {
            & $openssl.Source @opensslArgs 2>$null
        } elseif (Test-Path $gitOpenssl) {
            & $gitOpenssl @opensslArgs 2>$null
        } else {
            & docker run --rm -v "${certDir}:/certs" -w /certs alpine/openssl @opensslArgs 2>$null
        }
    } finally {
        Pop-Location
        $ErrorActionPreference = $previous
    }
    if (-not ((Test-Path $cert) -and (Test-Path $key))) { Fail 'gen-certs: could not create the certificate.' }
    Write-Host 'gen-certs: created a SELF-SIGNED certificate (the browser will warn once; install mkcert for a trusted one)'
}

function Wait-Healthy([int]$timeoutSeconds) {
    $deadline = (Get-Date).AddSeconds($timeoutSeconds)
    while ($true) {
        $pending = @()
        $failed = @()
        $rows = & docker compose ps -a --format '{{.Service}}|{{.State}}|{{.Health}}|{{.ExitCode}}'
        foreach ($row in $rows) {
            if (-not $row) { continue }
            $service, $state, $health, $exitCode = $row.Split('|')
            if ($state -eq 'exited') {
                if ($exitCode -ne '0') { $failed += "$service(exit=$exitCode)" }
            } elseif ($state -eq 'running' -and $health -eq 'healthy') {
                continue
            } elseif ($health -eq 'unhealthy') {
                $failed += "$service(unhealthy)"
            } else {
                $pending += $service
            }
        }
        if ($failed.Count -gt 0) { Fail ("FAILED: " + ($failed -join ' ') + "`nInspect with: docker compose logs <service>") }
        if ($pending.Count -eq 0) { Write-Host 'All services are healthy.'; return }
        if ((Get-Date) -ge $deadline) { Fail ("TIMEOUT after ${timeoutSeconds}s, still waiting for: " + ($pending -join ' ')) }
        Write-Host ("waiting for: " + ($pending -join ' '))
        Start-Sleep -Seconds 5
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { Fail 'Docker is not installed or not on PATH.' }
$ErrorActionPreference = 'Continue'
& docker info *> $null
$dockerOk = ($LASTEXITCODE -eq 0)
$ErrorActionPreference = 'Stop'
if (-not $dockerOk) { Fail 'Docker is not running. Start Docker Desktop first.' }

Write-Host '==> 1/4 Environment'
Initialize-Env

Write-Host '==> 2/4 TLS certificate'
New-Certificate

Write-Host "==> 3/4 Starting services ($mode mode)"
# Switching mode: remove the workers of the other mode, otherwise both sets run.
if ($mode -eq 'lite') {
    $otherWorkers = @('worker-meter', 'worker-fault', 'worker-document', 'worker-video')
} else {
    $otherWorkers = @('worker-all')
}
$env:COMPOSE_PROFILES = 'full,lite'
$ErrorActionPreference = 'Continue'
& docker compose rm --stop --force @otherWorkers *> $null
$env:COMPOSE_PROFILES = $mode
& docker compose up -d --build
$upExit = $LASTEXITCODE
$ErrorActionPreference = 'Stop'
if ($upExit -ne 0) { Fail 'docker compose up failed.' }

Write-Host '==> 4/4 Waiting for services to become healthy'
Wait-Healthy 300

$httpsPort = ((& docker compose port gateway 443) -split ':')[-1]
$suffix = ''
if ($httpsPort -ne '443') { $suffix = ":$httpsPort" }

Write-Host ''
Write-Host "FileGrid is up ($mode mode)."
Write-Host ''
Write-Host "  Grafana      https://localhost$suffix/grafana   (user and password: GRAFANA_ADMIN_* in the env file)"
Write-Host '  Status       docker compose ps'
Write-Host '  Memory       bash scripts/mem-report.sh'
Write-Host '  Logs         docker compose logs -f <service>'
Write-Host '  Stop         docker compose down'
Write-Host ''
Write-Host 'Storages (s3, ftp, store-db) are reachable only inside the internal Docker network.'
