param([string]$Data)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$argsList=@('run','--locked','--release','--','server')
if($Data){$argsList+=@('--data',$Data)}
& cargo @argsList
