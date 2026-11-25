param([switch]$SkipLint)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Set-Location $projectRoot
if (-not $env:ASYL_KEYSTORE -or -not (Test-Path -LiteralPath $env:ASYL_KEYSTORE -PathType Leaf)) {
    throw 'Set ASYL_KEYSTORE to your local signing keystore file.'
}
if (-not $env:ASYL_STORE_PASSWORD) { throw 'Set ASYL_STORE_PASSWORD in the environment.' }
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr' }
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
$buildTools = Join-Path $sdkRoot 'build-tools/36.0.0'
$keyAlias = if ($env:ASYL_KEY_ALIAS) { $env:ASYL_KEY_ALIAS } else { 'asyltas' }
$keyPassword = if ($env:ASYL_KEY_PASSWORD) { 'env:ASYL_KEY_PASSWORD' } else { 'env:ASYL_STORE_PASSWORD' }
$tasks = @(':app:assembleRelease', ':app:assembleReleaseAndroidTest')
if (-not $SkipLint) { $tasks += ':app:lintDebug' }
& ./android/gradlew.bat -p android @tasks --max-workers=2
if ($LASTEXITCODE -ne 0) { throw 'Android build failed' }
$qaDir = Join-Path $projectRoot 'reports/qa_android_v4_20261125'
New-Item -ItemType Directory -Path $qaDir -Force | Out-Null
New-Item -ItemType Directory -Path (Join-Path $projectRoot 'deliverables') -Force | Out-Null
$artifacts = @(
    @{ Source = 'android/app/build/outputs/apk/release/app-release-unsigned.apk'; Output = 'deliverables/AsylTas-4.0.apk' },
    @{ Source = 'android/app/build/outputs/apk/androidTest/release/app-release-androidTest.apk'; Output = 'reports/qa_android_v4_20261125/portal-test.apk' }
)
foreach ($artifact in $artifacts) {
    & "$buildTools/zipalign.exe" -f -p 4 $artifact.Source $artifact.Output
    if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed' }
    & "$buildTools/apksigner.bat" sign --ks $env:ASYL_KEYSTORE --ks-key-alias $keyAlias --ks-pass env:ASYL_STORE_PASSWORD --key-pass $keyPassword $artifact.Output
    if ($LASTEXITCODE -ne 0) { throw 'APK signing failed' }
    & "$buildTools/apksigner.bat" verify $artifact.Output
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed' }
    Write-Output ("Ready: " + $artifact.Output)
}
