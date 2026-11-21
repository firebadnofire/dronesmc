param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$CacheDirectory,
    [string]$BuildDirectory
)
$ErrorActionPreference = 'Stop'
if (-not $JavaHome -or -not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin\java.exe'))) {
    throw 'Provide -JavaHome pointing to a Java 8 JDK, or set JAVA_HOME.'
}
$version = & (Join-Path $JavaHome 'bin\java.exe') -version 2>&1 | Out-String
if ($version -notmatch '1\.8\.') { throw "Java 8 JDK required. Found: $version" }
$previousJava = $env:JAVA_HOME
$previousCache = $env:GRADLE_USER_HOME
$previousBuild = $env:DRONESMC_BUILD_DIR
Push-Location (Join-Path $PSScriptRoot '..')
try {
    $env:JAVA_HOME = $JavaHome
    if ($CacheDirectory) { $env:GRADLE_USER_HOME = [IO.Path]::GetFullPath($CacheDirectory) }
    if ($BuildDirectory) { $env:DRONESMC_BUILD_DIR = [IO.Path]::GetFullPath($BuildDirectory) }
    & .\gradlew.bat build
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE; see the Gradle output above." }
} finally {
    $env:JAVA_HOME = $previousJava
    $env:GRADLE_USER_HOME = $previousCache
    $env:DRONESMC_BUILD_DIR = $previousBuild
    Pop-Location
}
