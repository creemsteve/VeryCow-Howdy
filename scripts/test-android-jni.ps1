$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$env:CARGO_TARGET_DIR=Join-Path $root 'target'
& cargo build --locked --offline --manifest-path android/native/Cargo.toml
if($LASTEXITCODE -ne 0){throw 'Host JNI build failed'}
$jdk=if($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin/javac.exe'))){$env:JAVA_HOME}else{(Get-ChildItem 'C:/Program Files/Eclipse Adoptium' -Directory -Filter 'jdk-17*'|Select-Object -First 1).FullName}
New-Item -ItemType Directory -Force build/android-jni-test | Out-Null
& (Join-Path $jdk 'bin/javac.exe') -d build/android-jni-test android/native/tests/java/chat/vch/flip/NativeClient.java
if($LASTEXITCODE -ne 0){throw 'JNI harness compilation failed'}
& (Join-Path $jdk 'bin/java.exe') -cp build/android-jni-test chat.vch.flip.NativeClient (Join-Path $root 'target/debug/vch_android.dll') (Join-Path $root 'build/android-jni-test') (Join-Path $root 'build/models')
if($LASTEXITCODE -ne 0){throw 'JNI smoke tests failed'}
