param(
    [ValidateSet('Server', 'Client', 'Demo', 'Preview')]
    [string]$Mode = 'Client',
    [int]$Port = 0,
    [switch]$Public,
    [string]$Server = ''
)

$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# Bat UTF-8 cho console Windows de hien thi dung tieng Viet co dau
try {
    & chcp 65001 | Out-Null
    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [Console]::InputEncoding = $utf8NoBom
    [Console]::OutputEncoding = $utf8NoBom
    $OutputEncoding = $utf8NoBom
} catch {
}

# Dung cac thu vien da co trong Maven cache, khong can lenh mvn trong PATH.
$repository = Join-Path $env:USERPROFILE '.m2\repository'
$pom = [xml](Get-Content -LiteralPath 'pom.xml' -Raw)
$javafxVersion = $pom.project.properties.'javafx.version'
$fxJars = @(Get-ChildItem -LiteralPath (Join-Path $repository 'org\openjfx') -Recurse -Filter "*-$javafxVersion-win.jar" -ErrorAction SilentlyContinue)
$sqliteJar = Join-Path $repository 'org\xerial\sqlite-jdbc\3.45.1.0\sqlite-jdbc-3.45.1.0.jar'
$slf4jJar = Join-Path $repository 'org\slf4j\slf4j-api\1.7.36\slf4j-api-1.7.36.jar'
$gsonJar = Join-Path $repository 'com\google\code\gson\gson\2.10.1\gson-2.10.1.jar'
$optionalDbJars = @(
    Get-ChildItem -LiteralPath (Join-Path $repository 'org\postgresql\postgresql') -Recurse -Filter 'postgresql-*.jar' -ErrorAction SilentlyContinue
    Get-ChildItem -LiteralPath (Join-Path $repository 'com\mysql\mysql-connector-j') -Recurse -Filter 'mysql-connector-j-*.jar' -ErrorAction SilentlyContinue
) | Where-Object { $_.Name -notlike '*-sources.jar' -and $_.Name -notlike '*-javadoc.jar' }
if ($fxJars.Count -lt 4 -or !(Test-Path -LiteralPath $sqliteJar) -or !(Test-Path -LiteralPath $slf4jJar) -or !(Test-Path -LiteralPath $gsonJar)) {
    throw "Thieu thu vien JavaFX $javafxVersion hoac dependency trong Maven cache. Hay reload Maven project trong IDE de tai dependencies, sau do chay lai script."
}

$fxPath = $fxJars.FullName -join ';'
$dependencyParts = @($sqliteJar, $slf4jJar, $gsonJar) + @($optionalDbJars | ForEach-Object { $_.FullName })
$dependencyPath = $dependencyParts -join ';'
$classPath = "target/classes;$dependencyPath"
$sources = (Get-ChildItem -LiteralPath 'src/main/java' -Recurse -Filter '*.java').FullName
$javaHomeCompiler = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\javac.exe' } else { $null }
$javaHomeRuntime = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { $null }
if ($javaHomeCompiler -and (Test-Path -LiteralPath $javaHomeCompiler) -and (Test-Path -LiteralPath $javaHomeRuntime)) {
    $javaCompiler = $javaHomeCompiler
    $javaRuntime = $javaHomeRuntime
} else {
    if ($env:JAVA_HOME) {
        Write-Warning "JAVA_HOME '$env:JAVA_HOME' khong phai thu muc JDK; se dung java/javac trong PATH."
    }
    $javaCompiler = 'javac'
    $javaRuntime = 'java'
}
New-Item -ItemType Directory -Force 'target/classes' | Out-Null
& $javaCompiler --release 17 -encoding UTF-8 --module-path $fxPath --add-modules javafx.controls,javafx.fxml -classpath $dependencyPath -d target/classes $sources
if ($LASTEXITCODE -ne 0) { throw 'Bien dich that bai.' }
if (Test-Path -LiteralPath 'src/main/resources') {
    Copy-Item -Path 'src/main/resources/*' -Destination 'target/classes' -Recurse -Force
}

$mainClass = if ($Mode -eq 'Server') { 'com.auction.server.ServerApplication' } else { 'com.auction.client.ClientApplication' }
$appArguments = @()
if ($Mode -eq 'Server') {
    if ($Port -gt 0) { $appArguments += @('--port', "$Port") }
    if ($Public) { $appArguments += '--public' }
} elseif ($Mode -eq 'Demo') {
    $appArguments = @('--demo')
} elseif ($Mode -eq 'Preview') {
    $appArguments = @('--preview')
} elseif ($Server -and $Server.Trim().Length -gt 0) {
    $appArguments = @("--server=$($Server.Trim())")
}

& $javaRuntime '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' '-Dsun.stdout.encoding=UTF-8' '-Dsun.stderr.encoding=UTF-8' --module-path $fxPath --add-modules javafx.controls,javafx.fxml -cp $classPath $mainClass @appArguments
exit $LASTEXITCODE
