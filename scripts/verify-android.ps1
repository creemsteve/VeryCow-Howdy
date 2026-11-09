param([string]$Apk='releases/v0.2.1-sonim/VCH-Flip-0.2.1-release.apk')
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
if(-not $env:JAVA_HOME -or -not(Test-Path (Join-Path $env:JAVA_HOME 'bin/javac.exe'))){$env:JAVA_HOME=(Get-ChildItem 'C:/Program Files/Eclipse Adoptium' -Directory -Filter 'jdk-17*'|Select-Object -First 1).FullName}
$sdk=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{Join-Path $root 'build/tools/android-sdk'}
$tools=Join-Path $sdk 'build-tools/34.0.0'
& (Join-Path $tools 'apksigner.bat') verify --verbose --print-certs $Apk
if($LASTEXITCODE -ne 0){throw 'APK signature verification failed'}
& (Join-Path $tools 'zipalign.exe') -c -p 4 $Apk
if($LASTEXITCODE -ne 0){throw 'APK ZIP alignment failed'}
$badging=& (Join-Path $tools 'aapt2.exe') dump badging $Apk
if($LASTEXITCODE -ne 0){throw 'APK manifest inspection failed'}
foreach($feature in @('android.hardware.touchscreen','android.hardware.faketouch','android.hardware.camera','android.hardware.camera.autofocus','android.hardware.microphone')){
    if($badging | Where-Object {$_.Trim() -eq "uses-feature: name='$feature'"}){throw "Unexpected required hardware: $feature"}
}
if(-not $badging[0].StartsWith("package: name='chat.vch.flip' versionCode='201' versionName='0.2.1'")){throw 'Unexpected package or version'}
& python (Join-Path $PSScriptRoot 'verify-android-package.py') $Apk
if($LASTEXITCODE -ne 0){throw 'Native/model package checks failed'}
Write-Host 'APK signature, ZIP layout, optional hardware, native libraries, models and notices verified.'
