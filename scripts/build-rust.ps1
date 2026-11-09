param([switch]$Package,[string]$Destination='releases/v0.2.0-rust',[switch]$Debug)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$configuration=if($Debug){'debug'}else{'release'}
$cargoArgs=@('build','--locked','--bins')
if(-not $Debug){$cargoArgs+='--release'}
& cargo @cargoArgs
if($LASTEXITCODE -ne 0){throw 'Rust build failed'}
if($Package){
    $destinationPath=[IO.Path]::GetFullPath((Join-Path $root $Destination))
    if(Test-Path -LiteralPath $destinationPath){throw 'The package destination already exists. Choose a new -Destination.'}
    New-Item -ItemType Directory -Path $destinationPath | Out-Null
    foreach($binary in @('vch','vch-client','vch-server')){Copy-Item -LiteralPath (Join-Path $root "target/$configuration/$binary.exe") -Destination $destinationPath}
    foreach($role in @('client','server','daemon')){
        $launcher='@echo off'+"`r`n"+'cd /d "%~dp0"'+"`r`n"+'vch.exe '+$role+' --models "%~dp0models" %*'+"`r`n"
        [IO.File]::WriteAllText((Join-Path $destinationPath "$role.cmd"),$launcher)
    }
    if(Test-Path build/models/dac.json){
        $models=Join-Path $destinationPath 'models';New-Item -ItemType Directory -Path $models | Out-Null
        Copy-Item build/models/dac.json,build/models/dac-encoder.onnx,build/models/dac-decoder.onnx -Destination $models
        if(Test-Path build/models/onnxruntime.dll){Copy-Item build/models/onnxruntime.dll -Destination $models}
        else{Write-Warning 'ONNX Runtime is not bundled; set ORT_DYLIB_PATH before using voice.'}
    }
    Copy-Item README.md,LICENSE -Destination $destinationPath
    Copy-Item docs,licenses -Destination $destinationPath -Recurse
    Write-Host "Native Rust application packaged in $destinationPath"
}
