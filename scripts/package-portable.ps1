$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$portable=Join-Path $root 'releases/v0.1.2/VCH-Portable'
New-Item -ItemType Directory -Force $portable | Out-Null
Copy-Item build/app/* $portable -Recurse -Force
$launchClient = '#!/usr/bin/env sh' + "`n" + 'cd "$(dirname "$0")"' + "`n" + 'exec java --add-modules jdk.httpserver -Xmx1024m -cp "./*" chat.vch.ClientApp' + "`n"
$launchServer = $launchClient.Replace('chat.vch.ClientApp','chat.vch.ServerApp')
$launchDaemon = $launchClient.Replace('chat.vch.ClientApp','chat.vch.ServerDaemon')
[IO.File]::WriteAllText((Join-Path $portable 'client.sh'),$launchClient)
[IO.File]::WriteAllText((Join-Path $portable 'server.sh'),$launchServer)
[IO.File]::WriteAllText((Join-Path $portable 'server-headless.sh'),$launchDaemon)
[IO.File]::WriteAllText((Join-Path $portable 'Client.command'),$launchClient)
[IO.File]::WriteAllText((Join-Path $portable 'Server.command'),$launchServer)
Copy-Item README.md,docs/SECURITY.md,docs/VOICE.md,docs/THIRD-PARTY.md $portable -Force
Copy-Item licenses (Join-Path $portable 'licenses') -Recurse -Force
Write-Host 'Portable desktop build: releases/v0.1.2/VCH-Portable (Java 17+ required)'
