# Builds dist\DataImporter\DataImporter.exe (with its own bundled Java runtime).
# Run from the project folder:  powershell -ExecutionPolicy Bypass -File .\build-exe.ps1

$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$appName = 'DataImporter'
$jdkHome = if ($env:JAVA_HOME) { $env:JAVA_HOME } else { 'C:\Program Files\Java\jdk-27' }
$jpackage = Join-Path $jdkHome 'bin\jpackage.exe'
if (-not (Test-Path $jpackage)) { throw "jpackage not found at $jpackage - set JAVA_HOME to a JDK 21+." }

Write-Host '1/3 Building the jar...'
& .\mvnw.cmd -q clean package -DskipTests
if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }

$jar = Get-ChildItem target\*.jar | Where-Object { $_.Name -notlike '*.original' } | Select-Object -First 1

Write-Host '2/3 Preparing jpackage input...'
$inputDir = 'target\jpackage-input'
New-Item -ItemType Directory -Force $inputDir | Out-Null
Copy-Item $jar.FullName $inputDir

if (Test-Path "dist\$appName") { Remove-Item -Recurse -Force "dist\$appName" }

Write-Host '3/3 Creating the exe...'
# Heap is a percentage of the PC's RAM, so it adapts to each machine instead of a fixed -Xmx.
& $jpackage `
    --type app-image `
    --name $appName `
    --input $inputDir `
    --main-jar $jar.Name `
    --dest dist `
    --win-console `
    --java-options '-XX:InitialRAMPercentage=5' `
    --java-options '-XX:MaxRAMPercentage=50' `
    --java-options '-XX:+UseG1GC' `
    --java-options '-XX:+ExitOnOutOfMemoryError' `
    --java-options '-Dfile.encoding=UTF-8'
if ($LASTEXITCODE -ne 0) { throw 'jpackage failed.' }

Write-Host "Done: dist\$appName\$appName.exe  (ship the whole dist\$appName folder)"
