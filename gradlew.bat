@rem Gradle bootstrap for this small repository.
@if "%JAVA_HOME%"=="" set "JAVA_HOME=C:\Program Files\Zulu\zulu-17"
@set GRADLE_VERSION=9.3.1
@set GRADLE_HOME=%TEMP%\wallart-gradle-%GRADLE_VERSION%
@if not exist "%GRADLE_HOME%\bin\gradle.bat" powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip'; $z=Join-Path $env:TEMP 'wallart-gradle.zip'; Invoke-WebRequest -Uri $u -OutFile $z; Expand-Archive -LiteralPath $z -DestinationPath $env:TEMP -Force; Rename-Item -LiteralPath (Join-Path $env:TEMP 'gradle-%GRADLE_VERSION%') -NewName ('wallart-gradle-%GRADLE_VERSION%') -Force"
@call "%GRADLE_HOME%\bin\gradle.bat" %*
