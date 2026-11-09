$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& "$PSScriptRoot/build.ps1"
if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
$sources=@(Get-ChildItem src/test/java -Recurse -Filter '*.java' | ForEach-Object FullName)
& javac --release 17 --add-modules jdk.httpserver -encoding UTF-8 -cp 'build/classes;lib/*' -d build/test-classes @sources
if ($LASTEXITCODE -ne 0) { throw 'Test compilation failed' }
$jdk = Split-Path (Split-Path (Get-Command javac).Source -Parent) -Parent
& (Join-Path $jdk 'bin/java.exe') --add-modules jdk.httpserver -cp 'build/classes;build/test-classes;lib/*' chat.vch.TorTransportTest
if ($LASTEXITCODE -ne 0) { throw 'Tor-only transport tests failed' }
& (Join-Path $jdk 'bin/java.exe') --add-modules jdk.httpserver -cp 'build/classes;build/test-classes;lib/*' chat.vch.VoiceRelayTest
if ($LASTEXITCODE -ne 0) { throw 'Voice relay tests failed' }
& (Join-Path $jdk 'bin/java.exe') --add-modules jdk.httpserver -cp 'build/classes;build/test-classes;lib/*' chat.vch.VideoRelayTest
if ($LASTEXITCODE -ne 0) { throw 'Video relay tests failed' }
& (Join-Path $jdk 'bin/java.exe') -cp 'build/classes;build/test-classes;lib/*' chat.vch.JoinAuthTest
if ($LASTEXITCODE -ne 0) { throw 'Join protocol tests failed' }
& (Join-Path $jdk 'bin/java.exe') --add-modules jdk.httpserver -cp 'build/classes;build/test-classes;lib/*' chat.vch.IntegrationTest
if ($LASTEXITCODE -ne 0) { throw 'Tests failed' }
& (Join-Path $jdk 'bin/java.exe') --add-modules jdk.httpserver -cp 'build/classes;build/test-classes;lib/*' chat.vch.JoinNetworkTest
if ($LASTEXITCODE -ne 0) { throw 'Join network tests failed' }
& (Join-Path $jdk 'bin/java.exe') --add-modules jdk.httpserver -cp 'build/classes;build/test-classes;lib/*' chat.vch.MigrationTest
if ($LASTEXITCODE -ne 0) { throw 'Migration tests failed' }
& (Join-Path $jdk 'bin/java.exe') --add-modules jdk.httpserver -cp 'build/classes;build/test-classes;lib/*' chat.vch.ChannelKeysTest
if ($LASTEXITCODE -ne 0) { throw 'Managed channel-key tests failed' }
if (Test-Path build/models/dac.json) {
    & (Join-Path $jdk 'bin/java.exe') -Xmx1024m -cp 'build/classes;build/test-classes;lib/*' chat.vch.CodecTest
    if ($LASTEXITCODE -ne 0) { throw 'Codec tests failed' }
    & (Join-Path $jdk 'bin/java.exe') -Xmx1400m --add-modules jdk.httpserver -cp 'build/classes;build/test-classes;lib/*' chat.vch.DesktopVoiceTest
    if ($LASTEXITCODE -ne 0) { throw 'Native desktop voice tests failed' }
}
