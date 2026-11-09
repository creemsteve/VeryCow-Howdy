param([string]$Destination='releases/v0.2.0-rust')
& "$PSScriptRoot/build-rust.ps1" -Package -Destination $Destination
