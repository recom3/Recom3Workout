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
    "$source/TemplateJson.java" "$source/WorkoutTemplate.java" "$source/TemplateStore.java" "$source/TemplateService.java" `
    "$source/RoutineConsent.java" `
    "$source/ProfileStore.java" "$source/DefinitionStore.java" "$source/ExerciseCatalog.java" "$source/DynamicRoutine.java" `
    "$source/CsvFields.java" "$source/DynamicSessionCsv.java" "$source/JsonText.java" `
    "$source/DynamicWorkoutJson.java" "$source/DynamicResults.java" "$source/WorkoutNavigation.java" `
    "$PSScriptRoot/WorkoutChecks.java" "$PSScriptRoot/ReplayRecording.java" "$PSScriptRoot/SessionChecks.java" `
    "$PSScriptRoot/SyncChecks.java" "$PSScriptRoot/TemplateChecks.java" "$PSScriptRoot/DynamicChecks.java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.WorkoutChecks
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.SessionChecks $out
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.SyncChecks $out
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.TemplateChecks $out $PSScriptRoot
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$hostOut = Join-Path $out 'host-android'
& "$Jdk/bin/java.exe" -ea -cp $out com.recom3.workout.DynamicChecks $out
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
New-Item -ItemType Directory -Force -Path $hostOut | Out-Null
$stubs = @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'host-android') -Filter '*.java' -Recurse | Select-Object -ExpandProperty FullName)
& "$Jdk/bin/javac.exe" -encoding UTF-8 -source 1.7 -target 1.7 -cp $out -d $hostOut @stubs `
    "$source/ReconWorkoutTransport.java" "$source/TemplateReceiver.java" "$source/LocalProfiles.java" `
    "$source/SensorStream.java" "$source/CsvRecording.java" "$source/WorkoutHud.java" `
    "$PSScriptRoot/TemplateBroadcastChecks.java" "$PSScriptRoot/DynamicHudChecks.java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp "$hostOut;$out" com.recom3.workout.DynamicHudChecks $out
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& "$Jdk/bin/java.exe" -ea -cp "$hostOut;$out" com.recom3.workout.TemplateBroadcastChecks $out (Join-Path $PSScriptRoot '..')
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
