param(
    [ValidateSet('Server', 'Client')]
    [string]$Mode = 'Client'
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Dung cac thu vien da co trong Maven cache, khong can lenh mvn trong PATH.
$repository = Join-Path $env:USERPROFILE '.m2\repository'
$fxJars = @(Get-ChildItem -LiteralPath (Join-Path $repository 'org\openjfx') -Recurse -Filter '*17.0.8-win.jar')
$sqliteJar = Join-Path $repository 'org\xerial\sqlite-jdbc\3.45.1.0\sqlite-jdbc-3.45.1.0.jar'
$slf4jJar = Join-Path $repository 'org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar'
if ($fxJars.Count -lt 4 -or !(Test-Path -LiteralPath $sqliteJar) -or !(Test-Path -LiteralPath $slf4jJar)) {
    throw 'Thieu thu vien trong Maven cache. Hay reload Maven project trong IDE de tai dependencies.'
}

$fxPath = $fxJars.FullName -join ';'
$classPath = "target/classes;$sqliteJar;$slf4jJar"
$sources = (Get-ChildItem -LiteralPath 'src/main/java' -Recurse -Filter '*.java').FullName
$javaCompiler = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { 'javac' }
$javaRuntime = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
New-Item -ItemType Directory -Force 'target/classes' | Out-Null
& $javaCompiler --release 17 -encoding UTF-8 --module-path $fxPath --add-modules javafx.controls,javafx.fxml -d target/classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Bien dich that bai.' }
if (Test-Path -LiteralPath 'src/main/resources') {
    Copy-Item -Path 'src/main/resources/*' -Destination 'target/classes' -Recurse -Force
}

$mainClass = if ($Mode -eq 'Server') { 'com.auction.server.ServerApplication' } else { 'com.auction.client.ClientApplication' }
& $javaRuntime --module-path $fxPath --add-modules javafx.controls,javafx.fxml -cp $classPath $mainClass
exit $LASTEXITCODE
