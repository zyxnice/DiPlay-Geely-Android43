# Explicit high-privilege OEM command bridge for verified C3ALFUS/API18/8600 units.
# Read docs/GEELY-8600-HANDOFF.md before use. Does not auto-select a device.
param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [Parameter(Mandatory=$true)][string]$Command,
    [string]$AdbPath = 'adb'
)
$ErrorActionPreference = 'Stop'
foreach ($check in @(
    @('ro.product.board', 'C3ALFUS'),
    @('ro.build.version.sdk', '18'),
    @('ro.build.display.id', '06.03.08600.H53.00060')
)) {
    $value = (& $AdbPath -s $Serial shell getprop $check[0] | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $value -ne $check[1]) {
        throw "Device guard failed for $($check[0]); no OEM command sent."
    }
}
$payload = [Text.Encoding]::UTF8.GetBytes($Command + [char]0)
$aligned = New-Object byte[] ([int]([Math]::Ceiling($payload.Length / 4.0) * 4))
[Array]::Copy($payload, $aligned, $payload.Length)
$callArgs = @('-s', $Serial, 'shell', 'service', 'call', 'usbprotect', '22', 'i32', [string]$payload.Length)
for ($offset=0; $offset -lt $aligned.Length; $offset+=4) {
    $callArgs += @('i32', [string][BitConverter]::ToInt32($aligned,$offset))
}
& $AdbPath @callArgs
if ($LASTEXITCODE -ne 0) { throw 'OEM service call transport failed' }
