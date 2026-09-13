@echo off
setlocal EnableExtensions
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "PROJECT=%ROOT%\dev"
set "TT_HOME=%USERPROFILE%\.truetagger"
set "JDK_HOME=%TT_HOME%\tools\jdk"
set "GRADLE_HOME=%USERPROFILE%\.gradle\truetagger\gradle-8.13"
if not exist "%JDK_HOME%\bin\java.exe" (
 echo [TrueTagger] JDK ainda nao foi preparado. Rode TrueTagger.bat.
 exit /b 1
)
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
 echo [TrueTagger] Gradle ainda nao foi preparado. Rode TrueTagger.bat.
 exit /b 1
)
set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JDK_HOME%\bin;%PATH%"
call "%GRADLE_HOME%\bin\gradle.bat" -p "%PROJECT%" %*
exit /b %ERRORLEVEL%
