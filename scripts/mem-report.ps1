[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [string]$Project = 'filegrid',

    [Parameter()]
    [string]$Service,

    [Parameter()]
    [double]$MaxPercent = 0,

    [Parameter()]
    [switch]$Json,

    [Parameter()]
    [switch]$PassThru
)

# Native PowerShell container memory monitor mirroring scripts/mem-report.sh
# Usage:
#   .\scripts\mem-report.ps1
#   .\scripts\mem-report.ps1 -Service worker-video -MaxPercent 85
#   .\scripts\mem-report.ps1 -Json

$ErrorActionPreference = 'Stop'

function ConvertTo-MiB([string]$val) {
    if ([string]::IsNullOrWhiteSpace($val)) { return 0 }
    $val = $val.Trim()
    if ($val -match '^([0-9.]+)\s*([A-Za-z]+)$') {
        $num = [double]$matches[1]
        $unit = $matches[2]
        switch -Regex ($unit) {
            '^(GiB|GB)$' { return [Math]::Round($num * 1024) }
            '^(MiB|MB)$' { return [Math]::Round($num) }
            '^(KiB|KB)$' { return [Math]::Round($num / 1024) }
            '^(B)$'      { return [Math]::Round($num / 1048576) }
            default      { return [Math]::Round($num) }
        }
    }
    return 0
}

# 1. Discover all containers for the compose project
$containerIdsRaw = & docker ps -a -q --filter "label=com.docker.compose.project=$Project"
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($containerIdsRaw)) {
    [Console]::Error.WriteLine("No FileGrid containers found. Start the stack first.")
    exit 1
}

$containerIds = $containerIdsRaw -split '\r?\n' | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
if ($containerIds.Count -eq 0) {
    [Console]::Error.WriteLine("No FileGrid containers found. Start the stack first.")
    exit 1
}

# 2. Inspect containers via native JSON parsing
$rawJson = & docker inspect $containerIds
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($rawJson)) {
    [Console]::Error.WriteLine("Failed to inspect containers.")
    exit 1
}

$inspected = $rawJson | ConvertFrom-Json

# 3. Batch stats for running containers
$runningIds = @()
$containerMap = [ordered]@{}

foreach ($c in $inspected) {
    $cId = $c.Id
    $cService = $c.Config.Labels.'com.docker.compose.service'
    if ([string]::IsNullOrWhiteSpace($cService)) { $cService = "unknown-$($cId.Substring(0, 12))" }
    $cState = $c.State.Status
    $cOom = [bool]($c.State.OOMKilled)
    $cLimitBytes = [long]($c.HostConfig.Memory)
    $limitMib = [Math]::Round($cLimitBytes / 1048576)

    $shortId = if ($cId.Length -ge 12) { $cId.Substring(0, 12) } else { $cId }

    $info = [PSCustomObject]@{
        Id = $cId
        ShortId = $shortId
        Service = $cService
        State = $cState
        OOMKilled = $cOom
        LimitMib = $limitMib
        UsedMib = 0
        UsedPct = $null
    }

    $containerMap[$cId] = $info
    if ($cState -eq 'running') {
        $runningIds += $cId
    }
}

if ($runningIds.Count -gt 0) {
    # Query docker stats in a single non-stream call
    $statsLines = & docker stats --no-stream --format '{{.ID}}|{{.MemUsage}}' $runningIds
    foreach ($sLine in $statsLines) {
        if ([string]::IsNullOrWhiteSpace($sLine)) { continue }
        $sParts = $sLine.Split('|')
        if ($sParts.Length -ge 2) {
            $sId = $sParts[0].Trim()
            $usageRaw = $sParts[1].Split('/')[0].Trim()
            $usedMib = ConvertTo-MiB $usageRaw

            # Find matching container in map
            foreach ($key in $containerMap.Keys) {
                $item = $containerMap[$key]
                if ($key.StartsWith($sId) -or $item.ShortId.StartsWith($sId) -or $sId.StartsWith($item.ShortId)) {
                    $item.UsedMib = $usedMib
                    if ($item.LimitMib -gt 0) {
                        $item.UsedPct = [Math]::Round(($usedMib * 100.0) / $item.LimitMib)
                    }
                    break
                }
            }
        }
    }
}

# 4. Sort services alphabetically
$sortedServices = $containerMap.Values | Sort-Object Service

# Filter by service if requested with non-existent service validation
$filteredServices = if (-not [string]::IsNullOrWhiteSpace($Service)) {
    $matched = @($sortedServices | Where-Object { $_.Service -eq $Service })
    if ($matched.Count -eq 0) {
        $available = ($sortedServices | ForEach-Object { $_.Service }) -join ', '
        [Console]::Error.WriteLine("Error: Requested service '$Service' not found in project '$Project'. Available services: $available")
        Write-Error "Service '$Service' not found in project '$Project'. Available services: $available" -ErrorAction Continue
        exit 1
    }
    $matched
} else {
    $sortedServices
}

# 5. Output handling
if ($Json) {
    $jsonOutput = $filteredServices | ForEach-Object {
        [ordered]@{
            service = $_.Service
            usedMib = $_.UsedMib
            limitMib = $_.LimitMib
            usedPercent = $_.UsedPct
            state = $_.State
            oomKilled = $_.OOMKilled
        }
    }
    $jsonOutput | ConvertTo-Json -Depth 3
    return
}

# Print table
"{0,-16} {1,10} {2,10} {3,7}  {4}" -f "SERVICE", "USED(MiB)", "LIMIT(MiB)", "USED%", "STATE"
$totalUsed = 0
$totalLimit = 0
$oomList = @()
$budgetViolations = @()

foreach ($item in $filteredServices) {
    if ($item.OOMKilled) {
        $oomList += $item.Service
    }

    $limitText = if ($item.LimitMib -gt 0) { "$($item.LimitMib)" } else { "none" }

    if ($item.State -ne 'running') {
        "{0,-16} {1,10} {2,10} {3,7}  {4}" -f $item.Service, "-", $limitText, "-", $item.State
        continue
    }

    $pctText = if ($null -ne $item.UsedPct) { "$($item.UsedPct)%" } else { "-" }
    "{0,-16} {1,10} {2,10} {3,7}  {4}" -f $item.Service, $item.UsedMib, $limitText, $pctText, $item.State

    $totalUsed += $item.UsedMib
    $totalLimit += $item.LimitMib

    if ($MaxPercent -gt 0 -and $null -ne $item.UsedPct -and $item.UsedPct -ge $MaxPercent) {
        $budgetViolations += "$($item.Service) ($($item.UsedPct)% >= $MaxPercent%)"
    }
}

"{0,-16} {1,10} {2,10}" -f "TOTAL (running)", $totalUsed, $totalLimit

# 6. Assertions & Verdict
if ($oomList.Count -gt 0) {
    [Console]::Error.WriteLine("OOMKilled: $($oomList -join ' ')")
    exit 1
}

Write-Host "No container was OOMKilled."

if ($budgetViolations.Count -gt 0) {
    [Console]::Error.WriteLine("Memory budget exceeded: $($budgetViolations -join ', ')")
    exit 1
}

if ($PassThru) {
    return $filteredServices
}
