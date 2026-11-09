param([switch]$Release,[switch]$CreateSigningKey,[switch]$Offline)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
if(-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME 'bin/javac.exe'))){
    $jdk=Get-ChildItem 'C:/Program Files/Eclipse Adoptium' -Directory -Filter 'jdk-17*' | Select-Object -First 1
    if(-not $jdk){throw 'Install JDK 17 and set JAVA_HOME.'}
    $env:JAVA_HOME=$jdk.FullName
}
if(-not $env:ANDROID_HOME){$env:ANDROID_HOME=Join-Path $root 'build/tools/android-sdk'}
$env:GRADLE_USER_HOME=Join-Path $root 'build/tools/gradle-home'
$env:CARGO_TARGET_DIR=Join-Path $root 'target'
$ndk=Join-Path $env:ANDROID_HOME 'ndk/27.2.12479018/toolchains/llvm/prebuilt/windows-x86_64/bin'
if(-not (Test-Path $ndk)){throw 'Install Android NDK 27.2.12479018.'}
foreach($model in @('dac.json','dac-encoder.onnx','dac-decoder.onnx')){if(-not(Test-Path (Join-Path $root "build/models/$model"))){throw 'Export DAC models first: python voice/export_onnx.py'}}
if($Release){
    $signing=Join-Path $root 'build/android-signing'
    $settings=Join-Path $signing 'signing.json'
    if($CreateSigningKey -and -not(Test-Path $settings)){
        New-Item -ItemType Directory -Force $signing | Out-Null
        $secret=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
        $keystore=Join-Path $signing 'vch-flip-release.p12'
        if(Test-Path $keystore){throw 'Existing signing key found without settings. Recover its password; do not replace it.'}
        $env:VCH_ANDROID_STORE_PASSWORD=$secret
        & (Join-Path $env:JAVA_HOME 'bin/keytool.exe') -genkeypair -keystore $keystore -storetype PKCS12 -storepass:env VCH_ANDROID_STORE_PASSWORD -alias vch-flip -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=VCH Flip, O=VCH'
        if($LASTEXITCODE -ne 0){throw 'Signing key generation failed'}
        @{keystore=$keystore;password=$secret;alias='vch-flip'} | ConvertTo-Json | Set-Content -LiteralPath $settings
    }
    if(-not $env:VCH_ANDROID_KEYSTORE -and (Test-Path $settings)){
        $config=Get-Content -LiteralPath $settings -Raw | ConvertFrom-Json
        $env:VCH_ANDROID_KEYSTORE=$config.keystore;$env:VCH_ANDROID_STORE_PASSWORD=$config.password;$env:VCH_ANDROID_KEY_ALIAS=$config.alias
    }
    if(-not $env:VCH_ANDROID_KEYSTORE){throw 'Configure signing environment variables, or pass -CreateSigningKey once. Preserve build/android-signing privately for future updates.'}
}
$profile=if($Release){'release'}else{'debug'}
$env:RUSTFLAGS='-C link-arg=-Wl,-z,max-page-size=16384'
foreach($target in @(@('aarch64-linux-android','arm64-v8a'),@('x86_64-linux-android','x86_64'))){
    $triple=$target[0];$abi=$target[1];$key=$triple.Replace('-','_');$upper=$key.ToUpperInvariant()
    [Environment]::SetEnvironmentVariable("CARGO_TARGET_${upper}_LINKER",(Join-Path $ndk "$($triple)29-clang.cmd"),'Process')
    [Environment]::SetEnvironmentVariable("CC_$key",(Join-Path $ndk "$($triple)29-clang.cmd"),'Process')
    [Environment]::SetEnvironmentVariable("AR_$key",(Join-Path $ndk 'llvm-ar.exe'),'Process')
    $cargoArgs=@('build','--locked','--manifest-path','android/native/Cargo.toml','--target',$triple)
    if($Release){$cargoArgs+='--release'};if($Offline){$cargoArgs+='--offline'}
    & cargo @cargoArgs
    if($LASTEXITCODE -ne 0){throw "Rust build failed for $triple"}
    $destination=Join-Path $root "android/app/build/rustJniLibs/$abi";New-Item -ItemType Directory -Force $destination | Out-Null
    Copy-Item -LiteralPath (Join-Path $root "target/$triple/$profile/libvch_android.so") -Destination $destination -Force
}
$gradle=Join-Path $root 'build/tools/gradle-8.9/bin/gradle.bat'
if(-not(Test-Path $gradle)){$gradle=Join-Path $root 'android/gradlew.bat'}
$variant=if($Release){'Release'}else{'Debug'}
$gradleArgs=@('-p','android','--no-daemon',":app:assemble$variant",":app:lint$variant")
if(-not $Release){$gradleArgs+=':app:assembleDebugAndroidTest'}
if($Offline){$gradleArgs+='--offline'}
& $gradle @gradleArgs
if($LASTEXITCODE -ne 0){throw 'Android build or lint failed'}
$out=Join-Path $root 'releases/v0.2.1-sonim';New-Item -ItemType Directory -Force $out | Out-Null
$apk=Join-Path $out "VCH-Flip-0.2.1-$profile.apk"
Copy-Item -LiteralPath (Join-Path $root "android/app/build/outputs/apk/$profile/app-$profile.apk") -Destination $apk -Force
$hash=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
"$hash  $([IO.Path]::GetFileName($apk))" | Set-Content -LiteralPath "$apk.sha256"
Write-Host "Built $apk"
