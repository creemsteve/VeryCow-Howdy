param([switch]$Coverage,[switch]$AllowMissingModels)
& "$PSScriptRoot/test-rust.ps1" @PSBoundParameters
