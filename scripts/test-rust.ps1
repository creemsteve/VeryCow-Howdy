param([switch]$Coverage,[switch]$Browser,[switch]$AllowMissingModels)
$ErrorActionPreference='Stop'
$root=Split-Path $PSScriptRoot -Parent
Set-Location $root
$env:CARGO_INCREMENTAL='0'
New-Item -ItemType Directory -Force build/tmp | Out-Null
$env:TEMP=Join-Path $root 'build/tmp';$env:TMP=$env:TEMP
& cargo fmt --all --check
if($LASTEXITCODE -ne 0){throw 'Rust formatting failed'}
& cargo clippy --locked --all-targets -- -D warnings
if($LASTEXITCODE -ne 0){throw 'Rust lint failed'}
$testArgs=@('test','--locked','--all-targets')
if($AllowMissingModels){$testArgs=@('test','--locked','--no-default-features','--all-targets')}
if($Coverage){$testArgs=@('llvm-cov','--locked','--all-targets','--html','--output-dir','target/coverage')}
& cargo @testArgs
if($LASTEXITCODE -ne 0){throw 'Rust tests failed'}
if($Browser){
    & cargo build --locked --examples
    if($LASTEXITCODE -ne 0){throw 'Browser fixture compilation failed'}
    $python=if(Test-Path .venv/Scripts/python.exe){Join-Path $root '.venv/Scripts/python.exe'}else{'python'}
    foreach($script in @('test-rust-voice.py','test-voice-interop.py','test-video.py')){
        & $python (Join-Path $PSScriptRoot $script)
        if($LASTEXITCODE -ne 0){throw "$script failed"}
    }
}
