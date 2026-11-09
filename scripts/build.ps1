param([switch]$Package,[string]$Destination='releases/v0.1.3')
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root
$javac = (Get-Command javac -ErrorAction Stop).Source
$jdk = Split-Path (Split-Path $javac -Parent) -Parent
New-Item -ItemType Directory -Force build/classes,build/test-classes,build/app,build/tmp | Out-Null
# Remove obsolete generated peer-transport files when rebuilding an existing checkout.
Get-ChildItem -LiteralPath (Join-Path $root 'build/classes/chat/vch') -Filter 'VoiceTransport*.class' -File -ErrorAction SilentlyContinue | Remove-Item -Force
Get-ChildItem -LiteralPath (Join-Path $root 'build/app') -Filter 'webrtc-java-*.jar' -File | Remove-Item -Force
$sources = @(Get-ChildItem src/main/java -Recurse -Filter '*.java' | ForEach-Object FullName)
& $javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -cp 'lib/*' -d build/classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Java compilation failed' }
Copy-Item src/main/resources/* build/classes -Recurse -Force
Copy-Item voice/dac_worker.py build/classes -Force
& (Join-Path $jdk 'bin/jar.exe') "-J-Djava.io.tmpdir=$root/build/tmp" --create --file build/app/vch.jar -C build/classes .
if ($LASTEXITCODE -ne 0) { throw 'Jar creation failed' }
Get-ChildItem lib -Filter '*.jar' -File | Where-Object { $_.Name -notlike 'webrtc-java-*' } | Copy-Item -Destination build/app -Force
if (Test-Path build/models/dac.json) { New-Item -ItemType Directory -Force build/app/models | Out-Null; Copy-Item build/models/dac*.onnx,build/models/dac.json build/app/models -Force }
Write-Host 'Built build/app/vch.jar'
if ($Package) {
    $packageRoot = Join-Path $root $Destination
    New-Item -ItemType Directory -Force $packageRoot | Out-Null
    foreach ($role in @('Client','Server')) {
        if (Test-Path (Join-Path $packageRoot "VCH-$role")) { throw "$Destination/VCH-$role already exists. Choose another -Destination before rebuilding the package." }
        $inputDir=Join-Path $root "build/package-$role"
        New-Item -ItemType Directory -Force $inputDir | Out-Null
        Get-ChildItem -LiteralPath $inputDir -Filter 'webrtc-java-*.jar' -File | Remove-Item -Force
        if($role -eq 'Client') { Copy-Item build/app/* $inputDir -Recurse -Force }
        else { Copy-Item build/app/vch.jar,lib/gson-2.11.0.jar,lib/bcprov-jdk18on-1.83.jar,lib/bcpkix-jdk18on-1.83.jar,lib/bcutil-jdk18on-1.83.jar $inputDir -Force }
        & (Join-Path $jdk 'bin/jpackage.exe') "-J-Djava.io.tmpdir=$root/build/tmp" --type app-image --name "VCH-$role" --app-version 0.1.3 --vendor VCH --icon assets/vch.ico --input $inputDir --main-jar vch.jar --main-class "chat.vch.${role}App" --dest $packageRoot --java-options '--add-modules=jdk.httpserver' --java-options '-Xmx1024m' --add-modules java.base,java.desktop,java.logging,java.naming,java.net.http,java.management,java.security.jgss,java.instrument,jdk.crypto.ec,jdk.httpserver,jdk.unsupported
        if ($LASTEXITCODE -ne 0) { throw "$role packaging failed" }
        Copy-Item README.md (Join-Path $packageRoot "VCH-$role/README.md") -Force
        Copy-Item docs (Join-Path $packageRoot "VCH-$role/docs") -Recurse -Force
        Copy-Item licenses (Join-Path $packageRoot "VCH-$role/licenses") -Recurse -Force
    }
    Write-Host "Windows application images are in $Destination/VCH-Client and $Destination/VCH-Server. Keep each EXE with its app and runtime folders."
}
