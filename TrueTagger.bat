@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
title TrueTagger - Teste no celular

set "ROOT=%~dp0"
set "PROJECT=%ROOT%dev"
set "TT_HOME=%USERPROFILE%\.truetagger"
set "TOOLS=%TT_HOME%\tools"
set "DOWNLOADS=%TT_HOME%\downloads"
set "JDK_HOME=%TOOLS%\jdk"
set "ANDROID_SDK=%TOOLS%\android-sdk"
set "ADB=%ANDROID_SDK%\platform-tools\adb.exe"
set "SDKMANAGER=%ANDROID_SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
set "GRADLE_HOME=%TOOLS%\gradle-8.13"
set "JAVA_HOME=%JDK_HOME%"

if not exist "%PROJECT%\settings.gradle.kts" (
  echo ERRO: projeto incompleto.
  echo Caminho esperado: %PROJECT%
  goto fail
)

if not exist "%TOOLS%" mkdir "%TOOLS%"
if not exist "%DOWNLOADS%" mkdir "%DOWNLOADS%"

call :prepare_jdk
if errorlevel 1 goto fail
call :prepare_sdk
if errorlevel 1 goto fail
call :prepare_gradle
if errorlevel 1 goto fail

set "PATH=%JDK_HOME%\bin;%ANDROID_SDK%\platform-tools;%GRADLE_HOME%\bin;%PATH%"
set "ANDROID_HOME=%ANDROID_SDK%"
set "ANDROID_SDK_ROOT=%ANDROID_SDK%"

if not exist "%ADB%" (
  echo ERRO: adb.exe nao foi encontrado.
  goto fail
)
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo ERRO: Gradle nao foi encontrado.
  goto fail
)

echo.
echo ==========================================
echo [1/3] Compilando TrueTagger...
echo ==========================================
call "%GRADLE_HOME%\bin\gradle.bat" -p "%PROJECT%" :app:assembleDebug --no-daemon
if errorlevel 1 (
  echo.
  echo BUILD FALHOU. O erro acima e o importante.
  goto fail
)

set "APK=%ROOT%app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" (
  echo ERRO: APK nao encontrado:
  echo %APK%
  goto fail
)

echo.
echo ==========================================
echo [2/3] Procurando celular...
echo ==========================================
"%ADB%" start-server
if errorlevel 1 (
  echo ERRO ao iniciar o ADB.
  goto fail
)

set "DEVICE="
for /f "skip=1 tokens=1,2" %%A in ('"%ADB%" devices 2^>nul') do (
  if "%%B"=="device" if not "%%A"=="" set "DEVICE=%%A"
)

if not defined DEVICE (
  echo.
  echo NENHUM CELULAR AUTORIZADO FOI ENCONTRADO.
  echo.
  echo Confira:
  echo - cabo USB conectado;
  echo - Depuracao USB ativada;
  echo - celular desbloqueado;
  echo - toque em Permitir quando aparecer a autorizacao.
  goto fail
)

echo Celular detectado: !DEVICE!
echo.
echo ==========================================
echo [3/3] Instalando e abrindo TrueTagger...
echo ==========================================
echo Enviando APK para o celular...
set "REMOTE_APK=/data/local/tmp/truetagger.apk"
"%ADB%" -s "!DEVICE!" push "%APK%" "!REMOTE_APK!"
if errorlevel 1 (
  echo.
  echo FALHA AO ENVIAR O APK PARA O CELULAR.
  goto fail
)

echo Instalando APK...
"%ADB%" -s "!DEVICE!" shell pm install -r "!REMOTE_APK!"
if errorlevel 1 (
  echo.
  echo A atualizacao falhou. Tentando reinstalar do zero...
  "%ADB%" -s "!DEVICE!" uninstall dev.truetagger >nul 2>&1
  "%ADB%" -s "!DEVICE!" shell pm install -r "!REMOTE_APK!"
  if errorlevel 1 (
    echo.
    echo FALHA AO INSTALAR O APK.
    goto fail
  )
)
"%ADB%" -s "!DEVICE!" shell rm -f "!REMOTE_APK!" >nul 2>&1
"%ADB%" -s "!DEVICE!" shell am force-stop dev.truetagger >nul 2>&1
"%ADB%" -s "!DEVICE!" logcat -c >nul 2>&1
"%ADB%" -s "!DEVICE!" shell monkey -p dev.truetagger 1 >nul 2>&1

echo.
echo ==========================================
echo TrueTagger instalado e iniciado!
echo ==========================================
echo.
echo Uma segunda janela foi aberta para mostrar os erros do app em tempo real.
echo Deixe essa janela aberta enquanto testa.
start "TrueTagger - Logs" cmd /k ""%ADB%" -s "!DEVICE!" logcat -v time -s TrueTagger:E"
echo.
pause
exit /b 0

:prepare_jdk
if exist "%JDK_HOME%\bin\java.exe" exit /b 0

echo.
echo Preparando JDK 21 - primeira vez apenas...
set "DL_FILE=%DOWNLOADS%\jdk21.zip"
set "DL_URL=https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
call :download "!DL_FILE!" "!DL_URL!"
if errorlevel 1 exit /b 1

if exist "%TOOLS%\jdk-temp" rmdir /s /q "%TOOLS%\jdk-temp"
mkdir "%TOOLS%\jdk-temp"
call :extract_zip "%DL_FILE%" "%TOOLS%\jdk-temp" "JDK 21"
if errorlevel 1 exit /b 1

set "FOUND_JDK="
for /d %%D in ("%TOOLS%\jdk-temp\jdk-*") do (
  if exist "%%~fD\bin\java.exe" if not defined FOUND_JDK set "FOUND_JDK=%%~fD"
)
if not defined FOUND_JDK (
  echo ERRO: o ZIP do JDK nao contem uma pasta JDK valida.
  exit /b 1
)
if exist "%JDK_HOME%" rmdir /s /q "%JDK_HOME%"
move "%FOUND_JDK%" "%JDK_HOME%" >nul
rmdir /s /q "%TOOLS%\jdk-temp" >nul 2>&1
if not exist "%JDK_HOME%\bin\java.exe" (
  echo ERRO: JDK 21 nao foi preparado corretamente.
  exit /b 1
)
echo JDK pronto.
exit /b 0

:prepare_sdk
if not exist "%ANDROID_SDK%" mkdir "%ANDROID_SDK%"

if not exist "%ADB%" (
  echo.
  echo Preparando ADB - primeira vez apenas...
  set "DL_FILE=%DOWNLOADS%\platform-tools.zip"
  set "DL_URL=https://dl.google.com/android/repository/platform-tools-latest-windows.zip"
  call :download "!DL_FILE!" "!DL_URL!"
  if errorlevel 1 exit /b 1
  if exist "%ANDROID_SDK%\platform-tools" rmdir /s /q "%ANDROID_SDK%\platform-tools"
  call :extract_zip "%DL_FILE%" "%ANDROID_SDK%" "ADB"
  if errorlevel 1 exit /b 1
)
if not exist "%ADB%" (
  echo ERRO: adb.exe nao foi encontrado apos a extracao.
  exit /b 1
)

if not exist "%SDKMANAGER%" (
  echo.
  echo Preparando Android SDK Command-line Tools - primeira vez apenas...
  set "DL_FILE=%DOWNLOADS%\cmdline-tools.zip"
  set "DL_URL=https://dl.google.com/android/repository/commandlinetools-win-13114758_latest.zip"
  call :download "!DL_FILE!" "!DL_URL!"
  if errorlevel 1 exit /b 1
  if exist "%ANDROID_SDK%\cmdline-tools" rmdir /s /q "%ANDROID_SDK%\cmdline-tools"
  mkdir "%ANDROID_SDK%\cmdline-tools\temp"
  call :extract_zip "%DL_FILE%" "%ANDROID_SDK%\cmdline-tools\temp" "Android SDK Command-line Tools"
  if errorlevel 1 exit /b 1
  if not exist "%ANDROID_SDK%\cmdline-tools\temp\cmdline-tools\bin\sdkmanager.bat" (
    echo ERRO: formato inesperado das Command-line Tools.
    exit /b 1
  )
  mkdir "%ANDROID_SDK%\cmdline-tools\latest"
  xcopy /e /i /y "%ANDROID_SDK%\cmdline-tools\temp\cmdline-tools\*" "%ANDROID_SDK%\cmdline-tools\latest\" >nul
  rmdir /s /q "%ANDROID_SDK%\cmdline-tools\temp"
)
if not exist "%SDKMANAGER%" (
  echo ERRO: sdkmanager.bat nao foi encontrado.
  exit /b 1
)

set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JDK_HOME%\bin;%ANDROID_SDK%\platform-tools;%PATH%"
if not exist "%ANDROID_SDK%\platforms\android-36\android.jar" (
  echo.
  echo Instalando componentes Android necessarios - primeira vez apenas...
  echo Isso pode levar alguns minutos.
  (for /l %%N in (1,1,30) do @echo y) | call "%SDKMANAGER%" --sdk_root="%ANDROID_SDK%" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
  if errorlevel 1 (
    echo ERRO ao instalar os componentes do Android SDK.
    exit /b 1
  )
)
if not exist "%ANDROID_SDK%\platforms\android-36\android.jar" (
  echo ERRO: Android SDK Platform 36 nao foi instalado.
  exit /b 1
)
exit /b 0

:prepare_gradle
if exist "%GRADLE_HOME%\bin\gradle.bat" exit /b 0

echo.
echo Preparando Gradle 8.13 - primeira vez apenas...
set "DL_FILE=%DOWNLOADS%\gradle-8.13-bin.zip"
set "DL_URL=https://services.gradle.org/distributions/gradle-8.13-bin.zip"
call :download "!DL_FILE!" "!DL_URL!"
if errorlevel 1 exit /b 1

if exist "%TOOLS%\gradle-temp" rmdir /s /q "%TOOLS%\gradle-temp"
mkdir "%TOOLS%\gradle-temp"
call :extract_zip "%DL_FILE%" "%TOOLS%\gradle-temp" "Gradle 8.13"
if errorlevel 1 exit /b 1
if not exist "%TOOLS%\gradle-temp\gradle-8.13\bin\gradle.bat" (
  echo ERRO: o ZIP do Gradle nao contem uma instalacao valida.
  exit /b 1
)
if exist "%GRADLE_HOME%" rmdir /s /q "%GRADLE_HOME%"
move "%TOOLS%\gradle-temp\gradle-8.13" "%GRADLE_HOME%" >nul
rmdir /s /q "%TOOLS%\gradle-temp" >nul 2>&1
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo ERRO: Gradle nao foi preparado corretamente.
  exit /b 1
)
echo Gradle pronto.
exit /b 0

:download
set "DL_FILE=%~1"
set "DL_URL=%~2"
if not defined DL_FILE (
  echo ERRO interno: caminho do download vazio.
  exit /b 1
)
if not defined DL_URL (
  echo ERRO interno: URL do download vazia.
  exit /b 1
)

echo Baixando:
echo %DL_URL%
echo Para:
echo %DL_FILE%
if exist "%DL_FILE%" del /f /q "%DL_FILE%" >nul 2>&1

set "DOWNLOAD_OK=0"
where curl.exe >nul 2>&1
if not errorlevel 1 (
  curl.exe -fL --retry 5 --retry-delay 3 --connect-timeout 30 --output "%DL_FILE%" "%DL_URL%"
  if not errorlevel 1 set "DOWNLOAD_OK=1"
)

if "%DOWNLOAD_OK%"=="0" (
  echo Curl falhou ou nao esta disponivel. Tentando PowerShell...
  if exist "%DL_FILE%" del /f /q "%DL_FILE%" >nul 2>&1
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -UseBasicParsing -Uri '%DL_URL%' -OutFile '%DL_FILE%'"
  if not errorlevel 1 set "DOWNLOAD_OK=1"
)

if "%DOWNLOAD_OK%"=="0" (
  echo ERRO: nao foi possivel baixar o arquivo.
  exit /b 1
)

if not exist "%DL_FILE%" (
  echo ERRO: o download terminou sem criar o arquivo.
  exit /b 1
)

for %%Z in ("%DL_FILE%") do set "DL_SIZE=%%~zZ"
if not defined DL_SIZE set "DL_SIZE=0"
if !DL_SIZE! LSS 1024 (
  echo ERRO: download vazio/invalido. Tamanho: !DL_SIZE! bytes.
  del /f /q "%DL_FILE%" >nul 2>&1
  exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$p='%DL_FILE%'; $b=[IO.File]::ReadAllBytes($p); if($b.Length -lt 4 -or $b[0] -ne 80 -or $b[1] -ne 75){throw 'Arquivo nao e um ZIP valido.'}"
if errorlevel 1 (
  echo ERRO: o arquivo baixado nao e um ZIP valido.
  del /f /q "%DL_FILE%" >nul 2>&1
  exit /b 1
)
echo Download OK: !DL_SIZE! bytes.
exit /b 0

:extract_zip
set "EX_ZIP=%~1"
set "EX_DEST=%~2"
set "EX_NAME=%~3"
if not defined EX_ZIP (
  echo ERRO interno: caminho do ZIP vazio para %EX_NAME%.
  exit /b 1
)
if not defined EX_DEST (
  echo ERRO interno: destino vazio para %EX_NAME%.
  exit /b 1
)
if not exist "%EX_ZIP%" (
  echo ERRO: ZIP nao existe para %EX_NAME%:
echo %EX_ZIP%
  exit /b 1
)
for %%Z in ("%EX_ZIP%") do set "EX_SIZE=%%~zZ"
if not defined EX_SIZE set "EX_SIZE=0"
if !EX_SIZE! LSS 1024 (
  echo ERRO: ZIP vazio/invalido para %EX_NAME%. Tamanho: !EX_SIZE! bytes.
  exit /b 1
)
if not exist "%EX_DEST%" mkdir "%EX_DEST%"

echo Extraindo %EX_NAME%...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Expand-Archive -LiteralPath '%EX_ZIP%' -DestinationPath '%EX_DEST%' -Force"
if errorlevel 1 (
  echo ERRO: nao foi possivel extrair %EX_NAME%.
  exit /b 1
)
echo Extracao OK.
exit /b 0

:fail
echo.
echo ==========================================
echo O TrueTagger nao conseguiu continuar.
echo ==========================================
echo.
echo A janela vai ficar aberta para voce conseguir ver o erro.
pause
exit /b 1
