@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title TruePlayer

set "ROOT=%~dp0"
set "PROJECT=%ROOT%dev"
set "APP=%ROOT%app"
set "TT_HOME=%USERPROFILE%\.truetagger"
set "TOOLS=%TT_HOME%\tools"
set "JDK_HOME=%TOOLS%\jdk"
set "ANDROID_SDK=%TOOLS%\android-sdk"
set "ADB=%ANDROID_SDK%\platform-tools\adb.exe"
set "GRADLE_HOME=%TOOLS%\gradle-8.13"
set "GRADLE=%GRADLE_HOME%\bin\gradle.bat"
set "JAVA_HOME=%JDK_HOME%"
set "LOG=%ROOT%build-log.txt"
set "APK=%APP%\build\outputs\apk\debug\app-debug.apk"

if not exist "%PROJECT%\settings.gradle.kts" if not exist "%PROJECT%\settings.gradle" (
  echo ERRO: projeto Gradle nao encontrado: %PROJECT%
  goto fail
)
if not exist "%APP%\build.gradle.kts" if not exist "%APP%\build.gradle" (
  echo ERRO: modulo app nao encontrado: %APP%
  goto fail
)
if not exist "%JDK_HOME%\bin\java.exe" (
  echo ERRO: JDK 21 nao encontrado: %JDK_HOME%
  goto fail
)
if not exist "%GRADLE%" (
  echo ERRO: Gradle 8.13 nao encontrado: %GRADLE%
  goto fail
)
if not exist "%ADB%" (
  echo ERRO: ADB nao encontrado: %ADB%
  goto fail
)

set "ANDROID_HOME=%ANDROID_SDK%"
set "ANDROID_SDK_ROOT=%ANDROID_SDK%"
set "PATH=%JDK_HOME%\bin;%ANDROID_SDK%\platform-tools;%GRADLE_HOME%\bin;%PATH%"

if exist "%LOG%" del /f /q "%LOG%" >nul 2>&1

>"%LOG%" echo ===== TruePlayer - Log completo =====
>>"%LOG%" echo Projeto: %PROJECT%
>>"%LOG%" echo Modulo app: %APP%
>>"%LOG%" echo Inicio: %date% %time%
>>"%LOG%" echo.

"%JDK_HOME%\bin\java.exe" -version >>"%LOG%" 2>&1
call "%GRADLE%" -version >>"%LOG%" 2>&1

echo.
echo ==========================================
echo [1/3] Compilando TruePlayer...
echo ==========================================
echo.

pushd "%PROJECT%"
call "%GRADLE%" :app:assembleDebug --console=plain --warning-mode all --stacktrace >>"%LOG%" 2>&1
set "BUILD_RC=!ERRORLEVEL!"
popd

type "%LOG%"
echo.
if not "!BUILD_RC!"=="0" (
  echo ==========================================
  echo BUILD FALHOU
  echo ==========================================
  echo Log: %LOG%
  goto fail
)

if not exist "%APK%" (
  echo ERRO: APK nao encontrado:
  echo %APK%
  goto fail
)

echo ==========================================
echo BUILD SUCCESSFUL
echo ==========================================
echo APK: %APK%
echo.

echo ==========================================
echo [2/3] Procurando dispositivo...
echo ==========================================
echo.
"%ADB%" start-server >nul 2>&1
set "DEVICE="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  if "%%B"=="device" if not "%%A"=="" if not defined DEVICE set "DEVICE=%%A"
)
if not defined DEVICE (
  echo NENHUM celular/emulador autorizado foi encontrado.
  goto fail
)
echo Dispositivo: %DEVICE%
echo.

echo ==========================================
echo [3/3] Instalando e abrindo TruePlayer...
echo ==========================================
echo.
echo O instalador tem limite de 120 segundos por tentativa.
echo.

set "ADB_OUT=%ROOT%adb_install.out"
set "ADB_ERR=%ROOT%adb_install.err"
if exist "%ADB_OUT%" del /f /q "%ADB_OUT%" >nul 2>&1
if exist "%ADB_ERR%" del /f /q "%ADB_ERR%" >nul 2>&1

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$p=Start-Process -FilePath '%ADB%' -ArgumentList @('-s','%DEVICE%','install','-r','--no-streaming','--no-incremental','%APK%') -NoNewWindow -PassThru -RedirectStandardOutput '%ADB_OUT%' -RedirectStandardError '%ADB_ERR%'; if(-not $p.WaitForExit(120000)){ try{$p.Kill()}catch{}; try{$p.WaitForExit()}catch{}; exit 124 }; exit $p.ExitCode"
set "INSTALL_RC=!ERRORLEVEL!"
if exist "%ADB_OUT%" type "%ADB_OUT%"
if exist "%ADB_ERR%" type "%ADB_ERR%"

if "!INSTALL_RC!"=="124" (
  echo.
  echo O ADB travou por mais de 120 segundos.
  echo Reiniciando o ADB e tentando uma instalacao limpa...
  "%ADB%" kill-server >nul 2>&1
  "%ADB%" start-server >nul 2>&1
  "%ADB%" -s "%DEVICE%" uninstall dev.truetagger >nul 2>&1
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$p=Start-Process -FilePath '%ADB%' -ArgumentList @('-s','%DEVICE%','install','--no-streaming','--no-incremental','%APK%') -NoNewWindow -PassThru -RedirectStandardOutput '%ADB_OUT%' -RedirectStandardError '%ADB_ERR%'; if(-not $p.WaitForExit(120000)){ try{$p.Kill()}catch{}; try{$p.WaitForExit()}catch{}; exit 124 }; exit $p.ExitCode"
  set "INSTALL_RC=!ERRORLEVEL!"
  if exist "%ADB_OUT%" type "%ADB_OUT%"
  if exist "%ADB_ERR%" type "%ADB_ERR%"
)

if not "!INSTALL_RC!"=="0" (
  echo.
  echo FALHA AO INSTALAR O APK. Codigo: !INSTALL_RC!
  echo.
  echo Diagnostico:
  "%ADB%" -s "%DEVICE%" get-state
  "%ADB%" -s "%DEVICE%" shell pm list packages dev.truetagger
  goto fail
)

"%ADB%" -s "%DEVICE%" shell am force-stop dev.truetagger >nul 2>&1
"%ADB%" -s "%DEVICE%" logcat -c >nul 2>&1

echo.
echo Instalacao concluida. Abrindo TruePlayer...
"%ADB%" -s "%DEVICE%" shell monkey -p dev.truetagger -c android.intent.category.LAUNCHER 1
if errorlevel 1 (
  echo.
  echo Monkey falhou. Tentando launcher por intent...
  "%ADB%" -s "%DEVICE%" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p dev.truetagger
)

echo.
echo ==========================================
echo TruePlayer instalado e iniciado!
echo ==========================================
echo.
start "TruePlayer - Logs" cmd /k ""%ADB%" -s "%DEVICE%" logcat -v time -s TruePlayer:E AndroidRuntime:E System.err:E"
echo.
pause
exit /b 0

:fail
echo.
echo ==========================================
echo O TruePlayer nao conseguiu continuar.
echo ==========================================
echo.
pause
exit /b 1
