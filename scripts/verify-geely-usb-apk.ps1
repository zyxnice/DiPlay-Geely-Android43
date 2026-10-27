#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $ApkPath,
    [Parameter(Mandatory)][string] $AuthenticationAssetsDirectory,
    [Parameter(Mandatory)][string] $SdkDirectory,
    [string] $BuildToolsVersion = '37.0.0',
    [ValidateSet('USB1', 'USB2')][string] $ExpectedUsbVersion = 'USB1'
)

$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
$toolsDirectory = Join-Path $SdkDirectory "build-tools/$BuildToolsVersion"

$badgingLines = & (Join-Path $toolsDirectory 'aapt2.exe') dump badging $resolvedApk
if ($LASTEXITCODE -ne 0) { throw 'aapt2 could not read the APK' }
$badging = $badgingLines -join "`n"
if ($badging -notmatch "package: name='com\.shihab\.diplay\.repair1\.geely8600'") {
    throw 'Unexpected application ID; this test must coexist with the original application'
}
if ($badging -notmatch "versionName='[^']*H53-$ExpectedUsbVersion'" -or
    $badging -notmatch "(?m)^(?:minSdkVersion|sdkVersion):'18'$" -or
    $badging -notmatch "native-code:.*'armeabi-v7a'") {
    throw 'APK version, API-18 minimum or ARMv7 support is incorrect'
}
if ($badging -notmatch 'DiPlay 8600 USB测试') { throw 'Test application label is missing' }

# Verify the API-18 signing path explicitly; modern v2-only signatures cannot install there.
$signatureLines = & cmd.exe /d /c (Join-Path $toolsDirectory 'apksigner.bat') verify --verbose --print-certs --min-sdk-version 18 $resolvedApk 2>&1
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed for API 18' }
$signature = $signatureLines -join "`n"
if ($signature -notmatch 'Verified using v1 scheme \(JAR signing\): true') {
    throw 'APK lacks the required v1 signature'
}
if ($signature -notmatch 'certificate SHA-256 digest: [0-9a-fA-F]{64}') {
    throw 'The signing certificate could not be read back'
}

$manifestLines = & (Join-Path $toolsDirectory 'aapt2.exe') dump xmltree $resolvedApk --file AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Could not read packaged manifest' }
$manifest = $manifestLines -join "`n"
if ($ExpectedUsbVersion -eq 'USB2' -and $manifest -notmatch '(?s)GEELY_USB_RUNTIME_RECOVERY[^\n]*\n[^\n]*android:value[^\n]*(?:=true|0xffffffff)') {
    throw 'USB2 runtime recovery metadata is missing'
}
if ($manifest -notmatch '(?s)GEELY_USB_BYPASS[^\n]*\n[^\n]*android:value[^\n]*(?:=true|0xffffffff)') {
    throw 'The isolated test metadata is not explicitly true'
}

$archive = [IO.Compression.ZipFile]::OpenRead($resolvedApk)
try {
    $helper = $archive.GetEntry('assets/geely-usb/helper.dex')
    if ($null -eq $helper -or $helper.Length -lt 8) { throw 'Standalone helper DEX asset missing' }
    $helperStream = $helper.Open()
    try {
        $header = [byte[]]::new(8)
        if ($helperStream.Read($header, 0, 8) -ne 8 -or
            [Convert]::ToHexString($header) -ne '6465780A30333500') {
            throw 'Helper is not a DEX035 file'
        }
    } finally { $helperStream.Dispose() }
    foreach ($filename in @('identity.pk8', 'certificate.p7b')) {
        $entry = $archive.GetEntry("assets/offline-mfi/$filename")
        $inputPath = Join-Path $AuthenticationAssetsDirectory "offline-mfi/$filename"
        if ($null -eq $entry -or $entry.Length -eq 0) { throw "Missing packaged authentication file: $filename" }
        $entryStream = $entry.Open()
        try {
            $packagedHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($entryStream))
        } finally { $entryStream.Dispose() }
        if ($packagedHash -ne (Get-FileHash -LiteralPath $inputPath -Algorithm SHA256).Hash) {
            throw "Packaged authentication hash mismatch: $filename"
        }
    }
    $armLibraries = @($archive.Entries | Where-Object { $_.FullName -match '^lib/armeabi-v7a/[^/]+\.so$' })
    if ($armLibraries.Count -eq 0) { throw 'No ARMv7 native libraries in APK' }
} finally { $archive.Dispose() }

Write-Output ($badgingLines | Where-Object { $_ -match '^(package:|minSdkVersion:|sdkVersion:|targetSdkVersion:|application-label:|native-code:)' })
Write-Output ($signatureLines | Where-Object { $_ -match '^Verified using|certificate (DN|SHA-256 digest):' })
Write-Output 'Verified: isolated bypass metadata, DEX035 helper, ARMv7 libraries, and both explicit authentication hashes.'
Get-FileHash -LiteralPath $resolvedApk -Algorithm SHA256
