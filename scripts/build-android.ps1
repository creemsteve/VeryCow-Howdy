$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$env:JAVA_HOME=Split-Path (Split-Path (Get-Command javac).Source -Parent) -Parent
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME=Join-Path $root 'build/tools/android-sdk' }
$env:GRADLE_USER_HOME=Join-Path $root 'build/tools/gradle-home'
$gradle=Join-Path $root 'build/tools/gradle-8.9/bin/gradle.bat'
if (-not (Test-Path $gradle)) { throw 'Install Gradle 8.9 and update the gradle path in this script, or open android in Android Studio.' }
if (-not (Test-Path build/models/dac.json)) { throw 'Export the codec models first: python voice/export_onnx.py' }
& $gradle -p android --no-daemon :app:assembleDebug :app:lintDebug
if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed' }
New-Item -ItemType Directory -Force releases/v0.1.3 | Out-Null
Copy-Item android/app/build/outputs/apk/debug/app-debug.apk releases/v0.1.3/VCH-Android-AOSP-debug.apk -Force
Write-Host 'Built releases/v0.1.3/VCH-Android-AOSP-debug.apk (Android 10+, no Google services required)'
