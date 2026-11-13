param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Keystore = (Join-Path $PSScriptRoot 'app/build/standalone/debug.keystore')
)

$ErrorActionPreference = 'Stop'
if (-not $Sdk -or -not $Jdk) { throw 'Set ANDROID_HOME and JAVA_HOME (JDK 8).' }

function Invoke-BuildTool {
    param([string]$Tool, [string[]]$Arguments)
    & $Tool @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Failed: $Tool ($LASTEXITCODE)" }
}

$env:JAVA_HOME = $Jdk
$tools = Join-Path $Sdk 'build-tools/27.0.3'
$platform = Join-Path $Sdk 'platforms/android-27/android.jar'
$app = Join-Path $PSScriptRoot 'app/src/main'
$recon = Join-Path $PSScriptRoot 'reconUI/src/main'
$build = Join-Path $PSScriptRoot 'app/build/standalone'
$generated = Join-Path $build 'generated'
$classes = Join-Path $build 'classes'
$output = Join-Path $PSScriptRoot 'app/build/outputs/apk/debug'
New-Item -ItemType Directory -Force -Path $generated, $classes, $output | Out-Null

$resources = Join-Path $build 'resources.apk'
$manifest = Join-Path $app 'AndroidManifest.xml'
Invoke-BuildTool "$tools/aapt.exe" @(
    'package', '-f', '-m', '--auto-add-overlay', '-J', $generated, '--extra-packages', 'com.reconinstruments.ui',
    '-M', $manifest, '-S', "$app/res", '-S', "$recon/res", '-I', $platform, '-F', $resources,
    '--debug-mode', '--min-sdk-version', '16', '--target-sdk-version', '16', '--version-code', '1', '--version-name', '0.1.0'
)

$sources = @(Get-ChildItem -Path "$app/java", "$recon/java", $generated -Filter '*.java' -Recurse |
    Select-Object -ExpandProperty FullName)
Invoke-BuildTool "$Jdk/bin/javac.exe" (@(
    '-encoding', 'UTF-8', '-source', '1.7', '-target', '1.7', '-bootclasspath', $platform, '-d', $classes
) + $sources)

$dex = Join-Path $build 'classes.dex'
Invoke-BuildTool "$tools/dx.bat" @('--dex', "--output=$dex", $classes)
Push-Location $build
try {
    Invoke-BuildTool "$tools/aapt.exe" @('add', $resources, 'classes.dex')
} finally {
    Pop-Location
}

$aligned = Join-Path $build 'aligned.apk'
Invoke-BuildTool "$tools/zipalign.exe" @('-f', '4', $resources, $aligned)
if (-not (Test-Path -LiteralPath $Keystore)) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Keystore) | Out-Null
    Invoke-BuildTool "$Jdk/bin/keytool.exe" @(
        '-genkeypair', '-keystore', $Keystore, '-storepass', 'android', '-alias', 'androiddebugkey',
        '-keypass', 'android', '-dname', 'CN=Android Debug,O=Android,C=US', '-keyalg', 'RSA',
        '-keysize', '2048', '-validity', '10000'
    )
}

$apk = Join-Path $output 'app-debug.apk'
Invoke-BuildTool "$tools/apksigner.bat" @(
    'sign', '--ks', $Keystore, '--ks-key-alias', 'androiddebugkey', '--ks-pass', 'pass:android',
    '--key-pass', 'pass:android', '--out', $apk, $aligned
)
Invoke-BuildTool "$tools/apksigner.bat" @('verify', '--min-sdk-version', '16', $apk)
Write-Output $apk
