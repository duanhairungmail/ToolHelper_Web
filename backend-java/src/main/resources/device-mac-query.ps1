$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
$ip = $env:TOOLHELPER_MAC_TARGET
function Reply($code, $message, $data = @{}) {
    $data.code = $code; $data.message = $message
    ConvertTo-Json -InputObject $data -Compress -Depth 4
}
function Route {
    $found = @(Find-NetRoute -RemoteIPAddress $ip -ErrorAction Stop)
    $route = @($found | Where-Object { $_.CimClass.CimClassName -eq 'MSFT_NetRoute' })
    $address = @($found | Where-Object { $_.CimClass.CimClassName -eq 'MSFT_NetIPAddress' })
    if ($route.Count -ne 1 -or $address.Count -ne 1) { return $null }
    return @{ index = [int]$route[0].InterfaceIndex; next = [string]$route[0].NextHop; prefix = [string]$route[0].DestinationPrefix; source = [string]$address[0].IPAddress; subnet = ([string]$address[0].IPAddress + '/' + [string]$address[0].PrefixLength) }
}
try {
    if ($ip -notmatch '^(\d{1,3}\.){3}\d{1,3}$') { Reply 'INVALID_IPV4' 'IPv4 invalid'; exit }
    if (!(Get-Command Find-NetRoute -ErrorAction SilentlyContinue) -or !(Get-Command Get-NetNeighbor -ErrorAction SilentlyContinue)) {
        Reply 'COMMAND_UNAVAILABLE' 'NetTCPIP commands unavailable'; exit
    }
    $route = Route
    if (!$route) { Reply 'NO_ROUTE' 'No unique route'; exit }
    if ($route.next -ne '0.0.0.0') { Reply 'NOT_ON_LINK' 'Target requires a gateway'; exit }
    $adapter = @(Get-NetAdapter -IncludeHidden -InterfaceIndex $route.index -ErrorAction Stop)
    if ($adapter.Count -ne 1 -or [int]$adapter[0].InterfaceType -notin @(6,71) -or [string]$adapter[0].Status -ne 'Up') {
        Reply 'UNSUPPORTED_INTERFACE' 'Interface is not an active Ethernet/WiFi link'; exit
    }
    $prefix = $route.subnet.Split('/')
    $bits = [int]$prefix[1]
    if ($bits -lt 31) {
        $network = [uint64]0; $target = [uint64]0
        foreach ($part in $prefix[0].Split('.')) { $network = ($network -shl 8) -bor [uint64]$part }
        foreach ($part in $ip.Split('.')) { $target = ($target -shl 8) -bor [uint64]$part }
        $hostMask = ([uint64]1 -shl (32 - $bits)) - 1
        $mask = [uint64]4294967295 -bxor $hostMask
        if ((($network -band $mask) -bor $hostMask) -eq $target) { Reply 'INVALID_IPV4' 'Subnet broadcast is not a device'; exit }
    }
    # 只探测用户指定的一个地址。Ping 不响应仍然读取邻居表。
    $ping = [System.Net.NetworkInformation.Ping]::new()
    try { $null = $ping.Send($ip, 1000) } catch [System.Net.NetworkInformation.PingException] { } finally { $ping.Dispose() }
    $rows = @(Get-NetNeighbor -AddressFamily IPv4 -InterfaceIndex $route.index -ErrorAction Stop | Where-Object { $_.IPAddress -eq $ip })
    $after = Route
    if (!$after -or $after.index -ne $route.index -or $after.next -ne $route.next -or $after.prefix -ne $route.prefix -or $after.source -ne $route.source -or $after.subnet -ne $route.subnet) {
        Reply 'NETWORK_CHANGED' 'Route changed during query'; exit
    }
    $data = @{ ipAddress = $ip; interfaceIndex = $route.index; prefix = $route.subnet; sourceAddress = $route.source }
    if ($rows.Count -ne 1) { Reply 'NO_NEIGHBOR' 'No unique neighbor mapping' $data; exit }
    $data.macAddress = [string]$rows[0].LinkLayerAddress; $data.neighborState = [string]$rows[0].State
    Reply 'OK' 'Neighbor read' $data
} catch {
    if ($_.Exception -is [System.UnauthorizedAccessException] -or $_.CategoryInfo.Category -eq 'PermissionDenied') { Reply 'ACCESS_DENIED' 'Network query permission denied' }
    elseif ($_.FullyQualifiedErrorId -match 'NotFound|NoMatching') { Reply 'NO_ROUTE' 'No usable route or adapter' }
    else { Reply 'COMMAND_FAILED' 'Windows network query failed' }
}
