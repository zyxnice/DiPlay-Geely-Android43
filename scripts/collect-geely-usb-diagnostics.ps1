#Requires -Version 7.2
[CmdletBinding()]
param(
    [string] $AdbPath = 'adb',
    [string] $Serial,
    [string] $OutputDirectory = (Join-Path (Get-Location) 'usb-diagnostics')
)

$ErrorActionPreference = 'Stop'
$devices = @(& $AdbPath devices)
if ($LASTEXITCODE -ne 0) { throw 'adb devices failed' }
$ready = @($devices | ForEach-Object {
    if ($_ -match '^(\S+)\s+device\s*$') { $Matches[1] }
})
if (-not $Serial) {
    if ($ready.Count -ne 1) { throw 'Connect exactly one authorized device, or specify -Serial.' }
    $Serial = $ready[0]
}
if ($Serial -notin $ready) { throw 'Selected device is not connected and authorized.' }

$null = New-Item -ItemType Directory -Force -Path $OutputDirectory
$report = Join-Path $OutputDirectory ('geely-usb-{0}.txt' -f (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
if (Test-Path -LiteralPath $report) { throw 'Report already exists.' }
Set-Content -LiteralPath $report -Encoding utf8 -Value 'Read-only USB investigation. No log clearing or device configuration changes.'
function Read-Device([string] $Title, [string[]] $Arguments) {
    Add-Content -LiteralPath $report -Encoding utf8 -Value "`n--- $Title ---"
    $lines = @(& $AdbPath -s $Serial @Arguments 2>&1)
    $exit = $LASTEXITCODE
    $lines | Add-Content -LiteralPath $report -Encoding utf8
    Add-Content -LiteralPath $report -Encoding utf8 -Value "exitCode=$exit"
}
Read-Device 'API' @('shell', 'getprop', 'ro.build.version.sdk')
Read-Device 'Board' @('shell', 'getprop', 'ro.product.board')
Read-Device 'Firmware' @('shell', 'getprop', 'ro.build.display.id')
Read-Device 'Current USB functions' @('shell', 'getprop', 'sys.usb.config')
Read-Device 'Persistent USB functions' @('shell', 'getprop', 'persist.sys.usb.config')
Read-Device 'Applied USB functions' @('shell', 'getprop', 'sys.usb.state')
Read-Device 'ADB daemon state' @('shell', 'getprop', 'init.svc.adbd')
Read-Device 'OEM role node (raw value; do not infer direction from the number alone)' @('shell', 'cat', '/proc/driver/usbroleswitch/0/mode')
Read-Device 'OEM role request' @('shell', 'getprop', 'usb.role.switch')
Read-Device 'OEM charging and power status' @('shell',
    'for p in /sys/class/otg_peripheral /sys/class/host_peripheral; do echo "$p"; for f in charging_mode power pin_status/status pin_fault/status; do echo "$f"; cat "$p/$f"; done; done')
Read-Device 'Installed DiPlay packages' @('shell', 'pm', 'list', 'packages', 'com.shihab.diplay')
Read-Device 'USB1 package' @('shell', 'dumpsys', 'package', 'com.shihab.diplay.repair1.geely8600')
Read-Device 'USB framework state' @('shell', 'dumpsys', 'usb')
Read-Device 'Sysfs device entries (ADB shell identity, not app identity)' @('shell', 'ls', '-l', '/sys/bus/usb/devices')
Read-Device 'Kernel USB identities (no serial numbers)' @('shell',
    'for p in /sys/bus/usb/devices/*; do if [ -f "$p/idVendor" ]; then echo "$p"; for f in idVendor idProduct busnum devnum bConfigurationValue; do echo "$f"; cat "$p/$f"; done; fi; done')
Read-Device 'USB logs before application redaction' @('logcat', '-d', '-v', 'threadtime',
    'xcertplay-usb:V', 'GeelyUsbFdBroker:V', 'GeelyUsbOemBridge:V',
    'UsbHostManager:V', 'UsbProtectProvider:V', 'UsbChargeControl:V', 'AndroidRuntime:E', '*:S')
Write-Output $report
