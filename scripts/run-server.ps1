$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot -Parent)
& java --add-modules jdk.httpserver -cp 'build/app/*' chat.vch.ServerApp
