param(
    [string]$Jdk = $env:JAVA_HOME,
    [string]$Recording,
    [int]$ExpectedReps = -1,
    [long]$QuietAfterMs = -1,
    [double]$Amplitude = -1,
    [double]$Trigger = -1
)

$ErrorActionPreference = 'Stop'
if (-not $Jdk) { throw 'Set JAVA_HOME (JDK 8).' }
$source = Join-Path $PSScriptRoot '../app/src/main/java/com/recom3/workout'
$out = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Force -Path $out | Out-Null
& "$Jdk/bin/javac.exe" -encoding UTF-8 -source 1.7 -target 1.7 -d $out `
    "$source/Exercise.java" "$source/Profile.java" "$source/RepDetector.java" "$source/MotionEngine.java" `
    "$source/WorkoutSession.java" "$source/SessionStore.java" `
    "$source/WorkoutJson.java" "$source/SyncFiles.java" "$source/SyncQueue.java" `
    "$source/WorkoutWire.java" "$source/SyncBatch.java" "$source/SyncRecovery.java" `
    "$PSScriptRoot/WorkoutChecks.java" "$PSScriptRoot/ReplayRecording.java" "$PSScriptRoot/SessionChecks.java" `
    "$PSScriptRoot/SyncChecks.java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.WorkoutChecks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.SessionChecks $out
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.SyncChecks $out
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($Recording) {
    if ($ExpectedReps -lt 0) { throw 'Supply -ExpectedReps from the actual exercise count.' }
    $replayArguments = @($Recording, $ExpectedReps, $QuietAfterMs)
    if ($Amplitude -ge 0 -or $Trigger -ge 0) {
        $replayArguments += $Amplitude.ToString([Globalization.CultureInfo]::InvariantCulture)
    }
    if ($Trigger -ge 0) {
        $replayArguments += $Trigger.ToString([Globalization.CultureInfo]::InvariantCulture)
    }
    & "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.ReplayRecording @replayArguments
}
exit $LASTEXITCODE
