param([switch]$Package,[string]$Destination='releases/v0.2.0-rust',[switch]$Debug)
& "$PSScriptRoot/build-rust.ps1" @PSBoundParameters
