param([string]$Version='0.1.2')
$ErrorActionPreference='Stop'
if($Version -notmatch '^\d+\.\d+\.\d+$'){throw 'Invalid release version'}
$workspace=Split-Path $PSScriptRoot -Parent
$current=Join-Path $workspace "releases/v$Version"
if(-not (Test-Path -LiteralPath (Join-Path $current 'VCH-Client/app/vch.jar'))){throw 'Build the release packages first'}
$openApps=@(Get-Process -Name VCH-Client,VCH-Server -ErrorAction SilentlyContinue)
if($openApps.Count -gt 0){throw 'Close the VCH client and server before updating, then run this script again.'}
$backup=Join-Path $workspace ('build/update-backups/'+$Version+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
$verified=0
foreach($rootName in @('dist','release')){
    foreach($role in @('Client','Server')){
        $source=Join-Path $current "VCH-$role"
        $target=Join-Path $workspace "$rootName/VCH-$role"
        if(-not(Test-Path -LiteralPath $target -PathType Container)){continue}
        foreach($relative in @("VCH-$role.exe","app/VCH-$role.cfg",'app/vch.jar')){
            $existing=Join-Path $target $relative
            if(Test-Path -LiteralPath $existing){$saved=Join-Path $backup "$rootName/VCH-$role/$relative";New-Item -ItemType Directory -Force (Split-Path $saved -Parent)|Out-Null;Copy-Item -LiteralPath $existing -Destination $saved}
        }
        foreach($file in (Get-ChildItem -LiteralPath $source -Recurse -File)){
            $relative=$file.FullName.Substring($source.Length+1)
            $destination=Join-Path $target $relative
            $hash=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash
            if(-not (Test-Path -LiteralPath $destination -PathType Leaf) -or (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $hash){
                New-Item -ItemType Directory -Force (Split-Path $destination -Parent)|Out-Null
                Copy-Item -LiteralPath $file.FullName -Destination $destination -Force
                if((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $hash){throw "File verification failed: $destination"}
            }
            $verified++
        }
        Write-Host "Updated $rootName/VCH-$role to $Version"
    }
    $apk=Join-Path $current 'VCH-Android-AOSP-debug.apk'
    if(Test-Path -LiteralPath $apk){Copy-Item -LiteralPath $apk -Destination (Join-Path $workspace "$rootName/VCH-Android-AOSP-debug.apk") -Force}
    foreach($doc in @('README.md','RELEASE-NOTES.md')){if(Test-Path -LiteralPath (Join-Path $current $doc)){Copy-Item -LiteralPath (Join-Path $current $doc) -Destination (Join-Path $workspace "$rootName/$doc") -Force}}
}
Write-Host "$verified application files verified. Previous launch files: $backup"
