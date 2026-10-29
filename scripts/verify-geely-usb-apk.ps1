#Requires -Version 7.2
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string] $ApkPath,
    [Parameter(Mandatory)][string] $AuthenticationAssetsDirectory,
    [Parameter(Mandatory)][string] $SdkDirectory,
    [string] $BuildToolsVersion = '37.0.0',
    [ValidateSet('USB1', 'USB2', 'USB3-A', 'USB3-B', 'USB3-C')][string] $ExpectedUsbVersion = 'USB1',
    [string] $BaselineApkPath
)

$ErrorActionPreference = 'Stop'
$utf8 = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
$resolvedApk = (Resolve-Path -LiteralPath $ApkPath).Path
if ($ExpectedUsbVersion -like 'USB3-*' -and -not $BaselineApkPath) {
    throw 'USB3 candidates require BaselineApkPath to verify data-preserving replacement identity'
}
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
if ($BaselineApkPath) {
    $baselineSignature = & (Join-Path $toolsDirectory 'apksigner.bat') verify --print-certs --min-sdk-version 18 (Resolve-Path -LiteralPath $BaselineApkPath).Path 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Baseline signature verification failed' }
    $certificatePattern = 'certificate SHA-256 digest: ([0-9a-fA-F]{64})'
    $candidateCertificate = (@([regex]::Matches($signature, $certificatePattern) | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Sort-Object) -join ',')
    $baselineCertificate = (@([regex]::Matches(($baselineSignature -join "`n"), $certificatePattern) | ForEach-Object { $_.Groups[1].Value.ToLowerInvariant() } | Sort-Object) -join ',')
    if (-not $baselineCertificate -or $candidateCertificate -ne $baselineCertificate) {
        throw 'Candidate certificate differs from installed USB2 baseline; do not install'
    }
    $baselineBadging = & (Join-Path $toolsDirectory 'aapt2.exe') dump badging (Resolve-Path -LiteralPath $BaselineApkPath).Path
    if ($LASTEXITCODE -ne 0) { throw 'Could not read baseline package identity' }
    $identityPattern = "package: name='([^']+)' versionCode='([^']+)'"
    $candidateIdentity = [regex]::Match($badging, $identityPattern).Value
    $baselineIdentity = [regex]::Match(($baselineBadging -join "`n"), $identityPattern).Value
    if (-not $candidateIdentity -or $candidateIdentity -ne $baselineIdentity) {
        throw 'Candidate package ID/versionCode must match baseline for bidirectional test replacement'
    }
}

$manifestLines = & (Join-Path $toolsDirectory 'aapt2.exe') dump xmltree $resolvedApk --file AndroidManifest.xml
if ($LASTEXITCODE -ne 0) { throw 'Could not read packaged manifest' }
$manifest = $manifestLines -join "`n"
if ($ExpectedUsbVersion -ne 'USB1' -and $manifest -notmatch '(?s)GEELY_USB_RUNTIME_RECOVERY[^\n]*\n[^\n]*android:value[^\n]*(?:=true|0xffffffff)') {
    throw 'USB2 runtime recovery metadata is missing'
}
if ($manifest -notmatch '(?s)GEELY_USB_BYPASS[^\n]*\n[^\n]*android:value[^\n]*(?:=true|0xffffffff)') {
    throw 'The isolated test metadata is not explicitly true'
}
if ($ExpectedUsbVersion -eq 'USB3-C') {
    $filterLines = & (Join-Path $toolsDirectory 'aapt2.exe') dump xmltree $resolvedApk --file res/xml/usb_device_filter.xml
    if ($LASTEXITCODE -ne 0) { throw 'Could not read packaged USB default-device filter' }
    $filters = $filterLines -join "`n"
    if ($filters -notmatch 'vendor-id=1452' -or $filters -notmatch 'vendor-id=6790' -or $filters -notmatch 'product-id=21778') {
        throw 'C must retain the Apple default association fix and the CH341 filter'
    }
}

$archive = [IO.Compression.ZipFile]::OpenRead($resolvedApk)
try {
    if ($ExpectedUsbVersion -like 'USB3-*') {
        $stage = $ExpectedUsbVersion.Substring(5)
        if ($manifest -notmatch "(?s)GEELY_MEDIA_STAGE[^\n]*\n[^\n]*android:value[^\n]*`"$stage`"") {
            throw 'Performance stage metadata does not match candidate identity'
        }
        $native = $archive.GetEntry('lib/armeabi-v7a/libxcertplay_chacha.so')
        if (($stage -in @('B', 'C') -and $null -eq $native) -or ($stage -eq 'A' -and $null -ne $native)) {
            throw 'Native media library must be present in B/C and absent from A'
        }
    }
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
