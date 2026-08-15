@echo off
setlocal EnableExtensions

for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "PROJECT=%ROOT%\dev"
set "TOOLS=%ROOT%\.tools"
set "JAVA_HOME=%TOOLS%\jdk"
set "ANDROID_HOME=%TOOLS%\android-sdk"
set "ANDROID_USER_HOME=%ROOT%\.android-home"
set "ANDROID_EMULATOR_HOME=%ROOT%\.android-home"
set "ANDROID_AVD_HOME=%ROOT%\.android-home\avd"
set "GRADLE_USER_HOME=%ROOT%\.gradle-local"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\emulator;%PATH%"
set "GRADLE_VERSION=8.13"
set "GRADLE_HOME=%TOOLS%\gradle-%GRADLE_VERSION%"
set "GRADLE_BAT=%GRADLE_HOME%\bin\gradle.bat"
set "DOWNLOADS=%TOOLS%\_downloads"
set "GRADLE_ZIP=%DOWNLOADS%\gradle-%GRADLE_VERSION%-bin.zip"

if not exist "%JAVA_HOME%\bin\java.exe" goto no_jdk
if exist "%GRADLE_BAT%" goto run_gradle

if not exist "%DOWNLOADS%" mkdir "%DOWNLOADS%"
where curl.exe >nul 2>nul
if errorlevel 1 goto no_curl
where tar.exe >nul 2>nul
if errorlevel 1 goto no_tar

echo [TrueTagger] Downloading local Gradle %GRADLE_VERSION%...
curl.exe -L --fail --retry 3 --output "%GRADLE_ZIP%" "https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip"
if errorlevel 1 exit /b 1
tar.exe -xf "%GRADLE_ZIP%" -C "%TOOLS%"
if errorlevel 1 exit /b 1

:run_gradle
call "%GRADLE_BAT%" -p "%PROJECT%" %*
exit /b %ERRORLEVEL%

:no_jdk
echo [TrueTagger] Local JDK not found.
echo Open TrueTagger.bat and choose option 5.
exit /b 1

:no_curl
echo [TrueTagger] curl.exe was not found.
exit /b 1

:no_tar
echo [TrueTagger] tar.exe was not found.
exit /b 1
