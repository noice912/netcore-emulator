<#
.SYNOPSIS
    Scrapes the engine's Prometheus exposition twice and reports memory, sessions, rate-limit
    violation deltas, tick-loop latency and drop/exhaustion indicators.

.DESCRIPTION
    Counters (…_total) are reported as the delta between the two scrapes so that a quiet engine
    shows 0 even if it dropped packets hours ago; gauges are reported from the second scrape.
    Flags raised: DROPS (any dropped/malformed/rejected/rate-limited/handler-error delta > 0),
    TICK OVERRUNS (overrun delta > 0), BUFFER PRESSURE (Netty direct memory above 75% of the JVM
    heap maximum, which is also the default direct-memory ceiling), HEAP PRESSURE (used heap above
    85% of committed heap).

    Exit code 0, or 1 when -FailOnFlags is set and any flag was raised (pipeline gate).

.PARAMETER Url              exposition URL (default http://127.0.0.1:22180/metrics/prometheus)
.PARAMETER IntervalSeconds  seconds between the two scrapes (default 5; 0 = single scrape, no deltas)
.PARAMETER Json             emit the summary as JSON instead of a table
.PARAMETER FailOnFlags      exit 1 when any flag is raised

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\parse_metrics.ps1 -IntervalSeconds 10
.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\parse_metrics.ps1 -Json -FailOnFlags
#>
[CmdletBinding()]
param(
    [string]$Url = 'http://127.0.0.1:22180/metrics/prometheus',
    [int]$IntervalSeconds = 5,
    [switch]$Json,
    [switch]$FailOnFlags
)

$ErrorActionPreference = 'Stop'

function Get-Scrape([string]$target) {
    $response = Invoke-WebRequest -Uri $target -UseBasicParsing -TimeoutSec 5
    $map = @{}
    foreach ($raw in ($response.Content -split "`n")) {
        $line = $raw.Trim()
        if ($line -eq '' -or $line.StartsWith('#')) { continue }
        $parts = $line -split '\s+'
        if ($parts.Count -ge 2) { $map[$parts[0]] = [double]$parts[1] }
    }
    return $map
}

$firstAt = Get-Date
$first = Get-Scrape $Url
if ($IntervalSeconds -gt 0) {
    Start-Sleep -Seconds $IntervalSeconds
    $second = Get-Scrape $Url
} else {
    $second = $first
}
$secondAt = Get-Date
$elapsed = [math]::Max(0.001, ($secondAt - $firstAt).TotalSeconds)

function Gauge([string]$name) {
    if ($second.ContainsKey($name)) { return $second[$name] }
    return $null
}
function Delta([string]$name) {
    if ($first.ContainsKey($name) -and $second.ContainsKey($name)) { return $second[$name] - $first[$name] }
    return $null
}
function MiB($bytes) {
    if ($null -eq $bytes) { return 'n/a' }
    return ('{0:N2} MiB' -f ($bytes / 1MB))
}
function Rate($delta) {
    if ($null -eq $delta) { return 'n/a' }
    return ('{0:N2}/s' -f ($delta / $elapsed))
}

$heapUsed      = Gauge 'netcore_jvm_heap_used_bytes'
$heapCommitted = Gauge 'netcore_jvm_heap_committed_bytes'
$heapMax       = Gauge 'netcore_jvm_heap_max_bytes'
$nettyDirect   = Gauge 'netcore_netty_direct_memory_bytes'

$drops = [ordered]@{
    packetsDropped       = Delta 'netcore_packets_dropped_total'
    packetsRateLimited   = Delta 'netcore_packets_rate_limited_total'
    packetsRejected      = Delta 'netcore_packets_rejected_total'
    malformedPackets     = Delta 'netcore_malformed_packets_total'
    unknownOpcodes       = Delta 'netcore_unknown_opcodes_total'
    handlerErrors        = Delta 'netcore_handler_errors_total'
    connectionsRejected  = Delta 'netcore_connections_rejected_total'
}

$flags = New-Object System.Collections.Generic.List[string]
foreach ($key in $drops.Keys) {
    if ($null -ne $drops[$key] -and $drops[$key] -gt 0) { $flags.Add(("DROPS: {0} +{1} over {2:N1}s" -f $key, $drops[$key], $elapsed)) }
}
$overrunDelta = Delta 'netcore_tick_overruns_total'
if ($null -ne $overrunDelta -and $overrunDelta -gt 0) { $flags.Add(("TICK OVERRUNS: +{0} over {1:N1}s" -f $overrunDelta, $elapsed)) }
if ($null -ne $nettyDirect -and $null -ne $heapMax -and $heapMax -gt 0 -and $nettyDirect -gt 0.75 * $heapMax) {
    $flags.Add(("BUFFER PRESSURE: netty direct {0} is above 75% of the {1} direct-memory ceiling" -f (MiB $nettyDirect), (MiB $heapMax)))
}
if ($null -ne $heapUsed -and $null -ne $heapCommitted -and $heapCommitted -gt 0 -and $heapUsed -gt 0.85 * $heapCommitted) {
    $flags.Add(("HEAP PRESSURE: {0} used of {1} committed" -f (MiB $heapUsed), (MiB $heapCommitted)))
}

$summary = [ordered]@{
    scrapedAt       = $secondAt.ToString('o')
    intervalSeconds = [math]::Round($elapsed, 2)
    up              = Gauge 'netcore_up'
    uptimeSeconds   = Gauge 'netcore_uptime_seconds'
    memory          = [ordered]@{
        heapUsedBytes      = $heapUsed
        heapCommittedBytes = $heapCommitted
        heapMaxBytes       = $heapMax
        nonHeapUsedBytes   = Gauge 'netcore_jvm_nonheap_used_bytes'
        nettyDirectBytes   = $nettyDirect
        nettyHeapBytes     = Gauge 'netcore_netty_heap_memory_bytes'
        threads            = Gauge 'netcore_jvm_threads'
        eventLoopsBoss     = Gauge 'netcore_event_loops_boss'
        eventLoopsWorker   = Gauge 'netcore_event_loops_worker'
    }
    sessions        = [ordered]@{
        active   = Gauge 'netcore_sessions_active'
        tcp      = Gauge 'netcore_sessions_tcp'
        udp      = Gauge 'netcore_sessions_udp'
        loopback = Gauge 'netcore_sessions_loopback'
        acceptedDelta = Delta 'netcore_connections_accepted_total'
    }
    traffic         = [ordered]@{
        packetsInDelta  = Delta 'netcore_packets_in_total'
        bytesInDelta    = Delta 'netcore_bytes_in_total'
        packetsOutDelta = Delta 'netcore_packets_out_total'
        bytesOutDelta   = Delta 'netcore_bytes_out_total'
        handledDelta    = Delta 'netcore_packets_handled_total'
    }
    rateLimit       = [ordered]@{
        violationsTotal = Gauge 'netcore_packets_rate_limited_total'
        violationsDelta = Delta 'netcore_packets_rate_limited_total'
    }
    tick            = [ordered]@{
        rate           = Gauge 'netcore_tick_rate'
        averageMicros  = Gauge 'netcore_tick_average_micros'
        lastMicros     = Gauge 'netcore_tick_last_micros'
        ticksDelta     = Delta 'netcore_tick_count_total'
        overrunsDelta  = $overrunDelta
    }
    drops           = $drops
    flags           = @($flags)
}

if ($Json) {
    $summary | ConvertTo-Json -Depth 5
} else {
    Write-Host ("netcore-emulator metrics  {0}  (deltas over {1:N1} s)" -f $summary.scrapedAt, $elapsed)
    Write-Host ("  up / uptime     : {0} / {1} s" -f $summary.up, $summary.uptimeSeconds)
    Write-Host ("  heap            : {0} used, {1} committed, {2} max" -f (MiB $heapUsed), (MiB $heapCommitted), (MiB $heapMax))
    Write-Host ("  non-heap        : {0}" -f (MiB $summary.memory.nonHeapUsedBytes))
    Write-Host ("  netty buffers   : direct {0}, heap {1}" -f (MiB $nettyDirect), (MiB $summary.memory.nettyHeapBytes))
    Write-Host ("  threads / loops : {0} threads, boss {1}, workers {2}" -f $summary.memory.threads, $summary.memory.eventLoopsBoss, $summary.memory.eventLoopsWorker)
    Write-Host ("  sessions        : {0} active (tcp {1}, udp {2}, loopback {3}); accepted +{4}" -f $summary.sessions.active, $summary.sessions.tcp, $summary.sessions.udp, $summary.sessions.loopback, $summary.sessions.acceptedDelta)
    Write-Host ("  traffic         : in +{0} pkt ({1} B), out +{2} pkt ({3} B), handled +{4}  [{5} in]" -f $summary.traffic.packetsInDelta, $summary.traffic.bytesInDelta, $summary.traffic.packetsOutDelta, $summary.traffic.bytesOutDelta, $summary.traffic.handledDelta, (Rate $summary.traffic.packetsInDelta))
    Write-Host ("  rate limit      : {0} violations total, +{1} in window" -f $summary.rateLimit.violationsTotal, $summary.rateLimit.violationsDelta)
    Write-Host ("  tick loop       : {0} tps, avg {1} us, last {2} us, +{3} ticks, +{4} overruns" -f $summary.tick.rate, $summary.tick.averageMicros, $summary.tick.lastMicros, $summary.tick.ticksDelta, $summary.tick.overrunsDelta)
    Write-Host ("  drops (delta)   : dropped {0}, rate-limited {1}, rejected {2}, malformed {3}, unknown {4}, handler errors {5}, conn rejected {6}" -f $drops.packetsDropped, $drops.packetsRateLimited, $drops.packetsRejected, $drops.malformedPackets, $drops.unknownOpcodes, $drops.handlerErrors, $drops.connectionsRejected)
    if ($flags.Count -eq 0) {
        Write-Host "  flags           : none"
    } else {
        foreach ($flag in $flags) { Write-Host ("  FLAG            : {0}" -f $flag) }
    }
}

if ($FailOnFlags -and $flags.Count -gt 0) { exit 1 }
exit 0
