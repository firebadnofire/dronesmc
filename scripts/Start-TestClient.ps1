param(
    [Parameter(Mandatory=$true)][string]$Instance,
    [string]$Jar = (Join-Path $PSScriptRoot '..\build\libs\dronesmc-0.1.1-beta.jar'),
    [string]$PrismRoot = "$env:APPDATA\PrismLauncher",
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$Baseline,
    [string]$Server,
    [int]$Port = 25575
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Instance = [IO.Path]::GetFullPath($Instance)
New-Item -ItemType Directory -Force $Instance,(Join-Path $Instance 'mods'),(Join-Path $Instance 'natives') | Out-Null
if ($Baseline -and (Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter 'dronesmc*.jar')) {
    throw 'Baseline instance already contains a DronesMC JAR. Choose a separate clean baseline directory.'
}
# Testing is silent. Only the explicit isolated instance is modified.
$optionsPath = Join-Path $Instance 'options.txt'
$options = if (Test-Path -LiteralPath $optionsPath) { @(Get-Content -LiteralPath $optionsPath) } else { @() }
$options = @($options | Where-Object { $_ -notmatch '^soundCategory_master:' }) + 'soundCategory_master:0.0'
$options | Set-Content -LiteralPath $optionsPath
if (-not $Baseline) {
    $jarName = [IO.Path]::GetFileName($Jar)
    foreach ($oldJar in Get-ChildItem -LiteralPath (Join-Path $Instance 'mods') -Filter 'dronesmc*.jar' | Where-Object { $_.Name -ne $jarName }) {
        $history = Join-Path $Instance 'test-artifact-history'
        New-Item -ItemType Directory -Force $history | Out-Null
        $hash = (Get-FileHash -LiteralPath $oldJar.FullName).Hash.Substring(0,12)
        $backup = Join-Path $history "$($oldJar.BaseName)-$hash.jar"
        Write-Output "Archiving previous test artifact: $($oldJar.FullName) -> $backup"
        Move-Item -LiteralPath $oldJar.FullName -Destination $backup -Force
    }
    Copy-Item -LiteralPath $Jar -Destination (Join-Path $Instance "mods\$jarName") -Force
}
function Get-LibraryPath($Library, $Artifact, [string]$Classifier = '') {
    $parts = $Library.name.Split(':')
    if (-not $Classifier -and $parts.Length -gt 3) { $Classifier = $parts[3] }
    $suffix = if ($Classifier) { "-$Classifier" } else { '' }
    $relative = "$($parts[0].Replace('.','/'))/$($parts[1])/$($parts[2])/$($parts[1])-$($parts[2])$suffix.jar"
    $path = Join-Path $PrismRoot "libraries\$relative"
    if (-not (Test-Path -LiteralPath $path)) { throw "Missing cached library: $path. Install Forge 1.12.2-14.23.5.2859 in Prism first." }
    if ($Artifact.sha1 -and (Get-FileHash -LiteralPath $path -Algorithm SHA1).Hash -ne $Artifact.sha1) { throw "Library hash mismatch: $path" }
    return $path
}
$minecraft = Get-Content (Join-Path $PrismRoot 'meta\net.minecraft\1.12.2.json') -Raw | ConvertFrom-Json
$forge = Get-Content (Join-Path $PrismRoot 'meta\net.minecraftforge\14.23.5.2859.json') -Raw | ConvertFrom-Json
$lwjgl = Get-Content (Join-Path $PrismRoot 'meta\org.lwjgl\2.9.4-nightly-20150209.json') -Raw | ConvertFrom-Json
$classpath = [Collections.Generic.List[string]]::new()
foreach ($library in @($forge.libraries) + @($minecraft.libraries) + @($lwjgl.libraries)) {
    $allowed = -not $library.rules
    foreach ($rule in $library.rules) {
        if (-not $rule.os -or $rule.os.name -eq 'windows') { $allowed = $rule.action -eq 'allow' }
    }
    if (-not $allowed) { continue }
    if ($library.natives) {
        if ($library.natives.windows) {
            $native = $library.natives.windows
            $path = Get-LibraryPath $library $library.downloads.classifiers.$native $native
            $zip = [IO.Compression.ZipFile]::OpenRead($path)
            try {
                foreach ($entry in $zip.Entries | Where-Object { $_.FullName -match '^[^/\\]+\.dll$' }) {
                    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, (Join-Path $Instance "natives\$($entry.Name)"), $true)
                }
            } finally { $zip.Dispose() }
        }
    } elseif ($library.downloads.artifact) {
        $classpath.Add((Get-LibraryPath $library $library.downloads.artifact))
    }
}
$classpath.Add((Get-LibraryPath $minecraft.mainJar $minecraft.mainJar.downloads.artifact))
$java = Join-Path $JavaHome 'bin\javaw.exe'
$version = & (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String
if ($version -notmatch '1\.8\.') { throw "Java 8 required; found $version" }
$arguments = @('-Xms512m','-Xmx3g',"-Djava.library.path=$Instance\natives",'-Dlog4j2.formatMsgNoLookups=true',
    '-cp',($classpath -join ';'),'net.minecraft.launchwrapper.Launch','--tweakClass','net.minecraftforge.fml.common.launcher.FMLTweaker',
    '--username','DroneTester','--uuid','00000000000000000000000000000001','--accessToken','0','--userType','legacy',
    '--version','1.12.2','--gameDir',$Instance,'--assetsDir',(Join-Path $PrismRoot 'assets'),'--assetIndex','1.12','--width','1000','--height','700')
if ($Server) { $arguments += @('--server',$Server,'--port',"$Port") }
# Quote process arguments, not shell code. No account tokens or existing profiles are read.
$quoted = $arguments | ForEach-Object { '"' + $_.Replace('"','\"') + '"' }
$process = Start-Process -FilePath $java -ArgumentList $quoted -WorkingDirectory $Instance -PassThru `
    -RedirectStandardOutput (Join-Path $Instance 'console.log') -RedirectStandardError (Join-Path $Instance 'console-error.log')
Write-Output "Started isolated local-test client PID $($process.Id) in $Instance. This test profile does not authenticate to online servers."
