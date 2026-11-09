param([switch]$Coverage,[switch]$Browser,[switch]$AllowMissingModels)
& "$PSScriptRoot/test-rust.ps1" @PSBoundParameters
