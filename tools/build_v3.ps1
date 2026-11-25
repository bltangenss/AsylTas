$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not $env:ASYL_KEYSTORE -or -not (Test-Path -LiteralPath $env:ASYL_KEYSTORE -PathType Leaf)) {
    throw 'Set ASYL_KEYSTORE to your local signing keystore file.'
}
if (-not $env:ASYL_STORE_PASSWORD) { throw 'Set ASYL_STORE_PASSWORD in the environment.' }
$alias = if ($env:ASYL_KEY_ALIAS) { $env:ASYL_KEY_ALIAS } else { 'asyltas' }
$keyPassword = if ($env:ASYL_KEY_PASSWORD) { 'env:ASYL_KEY_PASSWORD' } else { 'env:ASYL_STORE_PASSWORD' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr' }
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
& ./android/gradlew.bat -p android :app:assembleRelease :app:lintDebug --max-workers=2
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
$apk = 'deliverables/AsylTas-3.0.apk'
& "$sdk/build-tools/36.0.0/zipalign.exe" -f -p 4 android/app/build/outputs/apk/release/app-release-unsigned.apk $apk
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
& "$sdk/build-tools/36.0.0/apksigner.bat" sign --ks $env:ASYL_KEYSTORE --ks-key-alias $alias --ks-pass env:ASYL_STORE_PASSWORD --key-pass $keyPassword $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
& "$sdk/build-tools/36.0.0/apksigner.bat" verify $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
Write-Output "Ready: $apk"
