param([string]$Jdk = $env:JAVA_HOME)

$ErrorActionPreference = 'Stop'
if (-not $Jdk) { throw 'Set JAVA_HOME (JDK 8).' }
$source = Join-Path $PSScriptRoot '../app/src/main/java/com/recom3/workout'
$out = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& "$Jdk/bin/javac.exe" -encoding UTF-8 -source 1.7 -target 1.7 -d $out `
    "$source/Exercise.java" "$source/Profile.java" "$source/RepDetector.java" "$source/MotionEngine.java" `
    "$PSScriptRoot/WorkoutChecks.java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.WorkoutChecks
exit $LASTEXITCODE
