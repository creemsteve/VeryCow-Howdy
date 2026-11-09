param([string]$Source='releases/v0.2.0-rust',[string]$Destination='dist/VCH')
$ErrorActionPreference='Stop'
$workspace=Split-Path $PSScriptRoot -Parent
$sourcePath=[IO.Path]::GetFullPath((Join-Path $workspace $Source))
$destinationPath=[IO.Path]::GetFullPath((Join-Path $workspace $Destination))
if($sourcePath -eq $destinationPath -or $sourcePath.StartsWith($destinationPath+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or $destinationPath.StartsWith($sourcePath+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'Source and destination must be separate directories'}
foreach($binary in @('vch.exe','vch-client.exe','vch-server.exe')){if(-not(Test-Path -LiteralPath (Join-Path $sourcePath $binary) -PathType Leaf)){throw "Missing native Rust executable: $binary"}}
$openApps=@(Get-Process -Name vch,vch-client,vch-server -ErrorAction SilentlyContinue | Where-Object {$_.Path -and $_.Path.StartsWith($destinationPath+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)})
if($openApps.Count -gt 0){throw 'Close the destination VCH apps before updating.'}
$backup=Join-Path $workspace ('build/update-backups/rust-'+[guid]::NewGuid().ToString('N'))
$verified=0
foreach($file in (Get-ChildItem -LiteralPath $sourcePath -Recurse -File)){
    if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Package must not contain reparse points'}
    $relative=$file.FullName.Substring($sourcePath.Length+1)
    $target=Join-Path $destinationPath $relative
    $hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
    if(Test-Path -LiteralPath $target -PathType Leaf){
        if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -eq $hash){$verified++;continue}
        $saved=Join-Path $backup $relative
        New-Item -ItemType Directory -Force (Split-Path $saved -Parent)|Out-Null
        Copy-Item -LiteralPath $target -Destination $saved
    }
    New-Item -ItemType Directory -Force (Split-Path $target -Parent)|Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $target -Force
    if((Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash -ne $hash){throw "File verification failed: $target"}
    $verified++
}
Write-Host "$verified native package files verified in $destinationPath. Replaced files, if any, are backed up in $backup"
