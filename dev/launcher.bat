@echo off
setlocal EnableExtensions

title TrueTagger Dev
for %%I in ("%~dp0..") do set "ROOT=%%~fI"
set "PROJECT=%ROOT%\dev"
set "TOOLS=%ROOT%\.tools"
set "JDK=%TOOLS%\jdk"
set "ANDROID_SDK=%TOOLS%\android-sdk"
set "DOWNLOADS=%TOOLS%\_downloads"
set "SDKMANAGER=%ANDROID_SDK%\cmdline-tools\latest\bin\sdkmanager.bat"
set "AVDMANAGER=%ANDROID_SDK%\cmdline-tools\latest\bin\avdmanager.bat"
set "EMULATOR=%ANDROID_SDK%\emulator\emulator.exe"
set "ADB=%ANDROID_SDK%\platform-tools\adb.exe"
set "AVD_NAME=TrueTagger_Phone_API36"
set "AVD_PACKAGE=system-images;android-36;google_apis;x86_64"
set "SERIAL=emulator-5556"

call :cleanup_old_layout
call :set_env

:menu
cls
echo ========================================
echo               TrueTagger
echo ========================================
echo Android-only project - v0.10.2
echo.
echo   1. Update and open TrueTagger
echo   2. Open installed TrueTagger
echo   3. Add music to virtual phone
echo   4. Simulate YouTube Share to TrueTagger
echo   5. Build Android APK
echo   6. Install / repair local build tools
echo   7. Reset virtual phone
echo   8. Clean build files
echo   9. Exit
echo.
choice /C 123456789 /N /M "Choose: "
if errorlevel 9 goto end
if errorlevel 8 goto clean
if errorlevel 7 goto reset_emulator
if errorlevel 6 goto setup
if errorlevel 5 goto apk
if errorlevel 4 goto share_test
if errorlevel 3 goto add_music
if errorlevel 2 goto open_app
if errorlevel 1 goto test_emulator

goto menu

:test_emulator
echo.
echo [TrueTagger] Updating app...
call :ensure_build_tools
if errorlevel 1 goto action_failed
call :build_debug
if errorlevel 1 goto action_failed

echo.
echo [TrueTagger] Checking virtual phone...
call :ensure_connected_phone
if errorlevel 1 goto action_failed

echo.
echo [TrueTagger] Installing update...
call :install_debug
if errorlevel 1 goto action_failed

echo.
echo [TrueTagger] Opening TrueTagger...
call :launch_app
if errorlevel 1 goto action_failed

echo.
echo [TrueTagger] TrueTagger updated and opened.
goto action_done

:open_app
call :ensure_connected_phone
if errorlevel 1 goto action_failed
"%ADB%" -s "%SERIAL%" shell pm path dev.truetagger >nul 2>nul
if errorlevel 1 goto app_not_installed
call :launch_app
if errorlevel 1 goto action_failed
echo.
echo [TrueTagger] TrueTagger is open on the virtual phone.
goto action_done

:ensure_connected_phone
"%ADB%" start-server >nul 2>nul
"%ADB%" -s "%SERIAL%" get-state 2>nul | findstr /X /C:"device" >nul
if not errorlevel 1 exit /b 0

echo [TrueTagger] Virtual phone is not running. Starting it now...
call :ensure_emulator
if errorlevel 1 exit /b 1
call :start_emulator
exit /b %ERRORLEVEL%

:add_music
call :ensure_emulator
if errorlevel 1 goto action_failed
call :start_emulator
if errorlevel 1 goto action_failed
echo.
set "MUSIC_FILE="
set /P "MUSIC_FILE=Drag a music file here and press Enter: "
set "MUSIC_FILE=%MUSIC_FILE:"=%"
if not exist "%MUSIC_FILE%" goto music_not_found
for %%F in ("%MUSIC_FILE%") do set "MUSIC_NAME=%%~nxF"
"%ADB%" -s "%SERIAL%" shell mkdir -p /sdcard/Music/TrueTaggerTest >nul
"%ADB%" -s "%SERIAL%" push "%MUSIC_FILE%" "/sdcard/Music/TrueTaggerTest/%MUSIC_NAME%"
if errorlevel 1 goto action_failed
"%ADB%" -s "%SERIAL%" shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Music/TrueTaggerTest/%MUSIC_NAME%" >nul
echo.
echo [TrueTagger] Music copied to the virtual phone.
echo [TrueTagger] In the app, tap Atualizar if it does not appear immediately.
goto action_done

:music_not_found
echo.
echo [TrueTagger] File not found.
goto action_done

:share_test
call :ensure_connected_phone
if errorlevel 1 goto action_failed

goto share_device_ready

:share_device_ready
"%ADB%" -s "%SERIAL%" shell pm path dev.truetagger >nul 2>nul
if errorlevel 1 goto app_not_installed
set "SHARE_URL="
echo.
set /P "SHARE_URL=Paste a YouTube URL: "
if "%SHARE_URL%"=="" goto action_done
"%ADB%" -s "%SERIAL%" shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "%SHARE_URL%" -n dev.truetagger/dev.truetagger.android.ShareReceiverActivity
if errorlevel 1 goto action_failed
echo.
echo [TrueTagger] Share sent. Check the Android notification.
goto action_done

:app_not_installed
echo.
echo [TrueTagger] TrueTagger is not installed on the virtual phone yet.
echo [TrueTagger] Use option 1 to update/install it.
goto action_done

:apk
call :ensure_build_tools
if errorlevel 1 goto action_failed
call :build_debug
if errorlevel 1 goto action_failed
call :copy_apk
if errorlevel 1 goto action_failed
goto action_done

:setup
call :setup_build_tools
if errorlevel 1 goto action_failed
goto action_done

:reset_emulator
call :ensure_emulator
if errorlevel 1 goto action_failed
"%ADB%" -s "%SERIAL%" emu kill >nul 2>nul
timeout /t 2 /nobreak >nul
call "%AVDMANAGER%" delete avd -n "%AVD_NAME%" >nul 2>nul
if exist "%ANDROID_AVD_HOME%\%AVD_NAME%.avd" rmdir /S /Q "%ANDROID_AVD_HOME%\%AVD_NAME%.avd"
if exist "%ANDROID_AVD_HOME%\%AVD_NAME%.ini" del /Q "%ANDROID_AVD_HOME%\%AVD_NAME%.ini"
call :create_avd
if errorlevel 1 goto action_failed
echo.
echo [TrueTagger] Virtual phone was reset.
goto action_done

:clean
call :ensure_build_tools
if errorlevel 1 goto action_failed
call "%PROJECT%\gradlew.bat" clean
if errorlevel 1 goto action_failed
if exist "%ROOT%\dist" rmdir /S /Q "%ROOT%\dist"
echo.
echo [TrueTagger] Build files cleaned.
goto action_done

:build_debug
echo.
echo [TrueTagger] Building Android app...
call "%PROJECT%\gradlew.bat" :app:assembleDebug
exit /b %ERRORLEVEL%

:install_debug
set "APK=%ROOT%\app\build\outputs\apk\debug\app-debug.apk"
set "INSTALL_LOG=%PROJECT%\install-output.txt"
if not exist "%APK%" exit /b 1
echo.
echo [TrueTagger] Installing app on virtual phone...
set /A INSTALL_TRY=0

:install_debug_retry
set /A INSTALL_TRY+=1
"%ADB%" -s "%SERIAL%" install -r "%APK%" > "%INSTALL_LOG%" 2>&1
if not errorlevel 1 goto install_debug_success
findstr /I /C:"device is still booting" "%INSTALL_LOG%" >nul
if not errorlevel 1 goto install_debug_wait_boot
findstr /I /C:"device offline" "%INSTALL_LOG%" >nul
if not errorlevel 1 goto install_debug_wait_boot
findstr /I /C:"device not found" "%INSTALL_LOG%" >nul
if not errorlevel 1 goto install_debug_wait_boot
findstr /I /C:"Can't find service: package" "%INSTALL_LOG%" >nul
if not errorlevel 1 goto install_debug_wait_boot
findstr /I /C:"Could not access the Package Manager" "%INSTALL_LOG%" >nul
if not errorlevel 1 goto install_debug_wait_boot

echo.
type "%INSTALL_LOG%"
echo [TrueTagger] APK installation failed for a reason unrelated to boot.
exit /b 1

:install_debug_wait_boot
if %INSTALL_TRY% GEQ 160 goto install_debug_boot_timeout
if %INSTALL_TRY% EQU 1 echo [TrueTagger] Android package service is not ready yet. Waiting automatically...
set /A INSTALL_STATUS_TICK=INSTALL_TRY %% 10
if %INSTALL_STATUS_TICK% EQU 0 echo [TrueTagger] Waiting for Android package service... attempt %INSTALL_TRY%/160
timeout /t 3 /nobreak >nul
goto install_debug_retry

:install_debug_success
"%ADB%" -s "%SERIAL%" shell pm path dev.truetagger >nul 2>nul
if errorlevel 1 exit /b 1
if exist "%INSTALL_LOG%" del /Q "%INSTALL_LOG%" >nul 2>nul
echo [TrueTagger] APK installed successfully.
exit /b 0

:install_debug_boot_timeout
echo.
echo [TrueTagger] Android stayed in boot mode for too long.
echo [TrueTagger] Boot diagnostics:
echo   sys.boot_completed:
"%ADB%" -s "%SERIAL%" shell getprop sys.boot_completed 2>nul
echo   dev.bootcomplete:
"%ADB%" -s "%SERIAL%" shell getprop dev.bootcomplete 2>nul
echo   boot animation service:
"%ADB%" -s "%SERIAL%" shell getprop init.svc.bootanim 2>nul
echo.
echo [TrueTagger] Last install response:
type "%INSTALL_LOG%"
echo.
echo [TrueTagger] If the Android home screen never becomes usable, use option 7 once to recreate the virtual phone.
exit /b 1

:launch_app
echo [TrueTagger] Waking and unlocking virtual phone...
call :wake_phone
echo [TrueTagger] Opening app...
"%ADB%" -s "%SERIAL%" logcat -c >nul 2>nul
"%ADB%" -s "%SERIAL%" shell am force-stop dev.truetagger >nul 2>nul
"%ADB%" -s "%SERIAL%" shell am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n dev.truetagger/dev.truetagger.android.MainActivity
if errorlevel 1 goto launch_app_failed
timeout /t 2 /nobreak >nul
"%ADB%" -s "%SERIAL%" shell pidof dev.truetagger >nul 2>nul
if errorlevel 1 goto launch_app_failed
echo [TrueTagger] App process is alive.
echo [TrueTagger] On first launch Android may ask for music permission - tap Allow.
exit /b 0

:launch_app_failed
echo.
echo [TrueTagger] The app did not stay open. Showing only errors from TrueTagger:
echo.
"%ADB%" -s "%SERIAL%" logcat -d -v time -t 240 AndroidRuntime:E *:S
echo.
echo [TrueTagger] Full error log saved to dev\last-error-logcat.txt
"%ADB%" -s "%SERIAL%" logcat -d -v time AndroidRuntime:E *:S > "%PROJECT%\last-error-logcat.txt"
exit /b 1

:wake_phone
"%ADB%" -s "%SERIAL%" shell input keyevent 224 >nul 2>nul
"%ADB%" -s "%SERIAL%" shell wm dismiss-keyguard >nul 2>nul
"%ADB%" -s "%SERIAL%" shell input keyevent 82 >nul 2>nul
"%ADB%" -s "%SERIAL%" shell svc power stayon true >nul 2>nul
"%ADB%" -s "%SERIAL%" shell settings put system screen_off_timeout 2147483647 >nul 2>nul
exit /b 0

:ensure_build_tools
if not exist "%JDK%\bin\java.exe" goto build_tools_missing
if not exist "%ANDROID_SDK%\platforms\android-36\android.jar" goto build_tools_missing
if not exist "%ADB%" goto build_tools_missing
exit /b 0

:build_tools_missing
echo [TrueTagger] Local Android build tools are missing or incomplete.
echo [TrueTagger] Installing them inside the TrueTagger folder...
call :setup_build_tools
exit /b %ERRORLEVEL%

:setup_build_tools
if not exist "%TOOLS%" mkdir "%TOOLS%"
if not exist "%DOWNLOADS%" mkdir "%DOWNLOADS%"
if not exist "%ANDROID_SDK%" mkdir "%ANDROID_SDK%"
where curl.exe >nul 2>nul
if errorlevel 1 goto no_curl
where tar.exe >nul 2>nul
if errorlevel 1 goto no_tar

if exist "%JDK%\bin\java.exe" goto sdk_check
echo [TrueTagger] Downloading local JDK 21...
curl.exe -L --fail --retry 3 --output "%DOWNLOADS%\jdk.zip" "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
if errorlevel 1 exit /b 1
if exist "%DOWNLOADS%\jdk-extract" rmdir /S /Q "%DOWNLOADS%\jdk-extract"
mkdir "%DOWNLOADS%\jdk-extract"
tar.exe -xf "%DOWNLOADS%\jdk.zip" -C "%DOWNLOADS%\jdk-extract"
if errorlevel 1 exit /b 1
for /D %%D in ("%DOWNLOADS%\jdk-extract\*") do goto move_jdk
exit /b 1

:move_jdk
move "%%~fD" "%JDK%" >nul
if errorlevel 1 exit /b 1

:sdk_check
if exist "%SDKMANAGER%" goto sdk_packages
echo [TrueTagger] Downloading local Android command-line tools...
curl.exe -L --fail --retry 3 --output "%DOWNLOADS%\android-cmdline.zip" "https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip"
if errorlevel 1 exit /b 1
if exist "%DOWNLOADS%\android-cmdline" rmdir /S /Q "%DOWNLOADS%\android-cmdline"
mkdir "%DOWNLOADS%\android-cmdline"
tar.exe -xf "%DOWNLOADS%\android-cmdline.zip" -C "%DOWNLOADS%\android-cmdline"
if errorlevel 1 exit /b 1
if not exist "%ANDROID_SDK%\cmdline-tools" mkdir "%ANDROID_SDK%\cmdline-tools"
move "%DOWNLOADS%\android-cmdline\cmdline-tools" "%ANDROID_SDK%\cmdline-tools\latest" >nul
if errorlevel 1 exit /b 1

:sdk_packages
call :set_env
if exist "%ANDROID_SDK%\platforms\android-36\android.jar" goto gradle_check
echo [TrueTagger] Installing Android SDK packages locally...
call "%SDKMANAGER%" --sdk_root="%ANDROID_SDK%" "platform-tools" "platforms;android-36" "build-tools;36.0.0"
if errorlevel 1 exit /b 1

:gradle_check
echo [TrueTagger] Checking local Gradle...
call "%PROJECT%\gradlew.bat" --version
if errorlevel 1 exit /b 1
echo.
echo [TrueTagger] Local build environment is ready.
exit /b 0

:ensure_emulator
call :ensure_build_tools
if errorlevel 1 exit /b 1
if exist "%EMULATOR%" if exist "%ANDROID_SDK%\system-images\android-36\google_apis\x86_64\package.xml" goto emulator_avd
echo.
echo [TrueTagger] First emulator setup can download a few GB.
echo [TrueTagger] Everything stays inside this TrueTagger folder.
echo [TrueTagger] Installing Android Emulator and API 36 phone image...
call "%SDKMANAGER%" --sdk_root="%ANDROID_SDK%" "emulator" "%AVD_PACKAGE%"
if errorlevel 1 exit /b 1

:emulator_avd
if not exist "%ANDROID_AVD_HOME%" mkdir "%ANDROID_AVD_HOME%"
if exist "%ANDROID_AVD_HOME%\%AVD_NAME%.avd" exit /b 0
call :create_avd
exit /b %ERRORLEVEL%

:create_avd
echo [TrueTagger] Creating local virtual phone...
echo no|call "%AVDMANAGER%" create avd -n "%AVD_NAME%" -k "%AVD_PACKAGE%" --force
if errorlevel 1 exit /b 1
exit /b 0

:start_emulator
"%ADB%" start-server >nul 2>nul
"%ADB%" -s "%SERIAL%" get-state 2>nul | findstr /X /C:"device" >nul
if not errorlevel 1 goto emulator_connected

if exist "%ANDROID_AVD_HOME%\%AVD_NAME%.avd\snapshots" rmdir /S /Q "%ANDROID_AVD_HOME%\%AVD_NAME%.avd\snapshots" >nul 2>nul
echo [TrueTagger] Starting virtual Android phone...
start "TrueTagger Android Emulator" "%EMULATOR%" -avd "%AVD_NAME%" -port 5556 -no-snapshot -no-boot-anim -gpu host -netdelay none -netspeed full
set /A WAIT_COUNT=0

:wait_for_device
"%ADB%" -s "%SERIAL%" get-state 2>nul | findstr /X /C:"device" >nul
if not errorlevel 1 goto emulator_connected
set /A WAIT_COUNT+=1
if %WAIT_COUNT% GEQ 90 goto emulator_timeout_device
timeout /t 2 /nobreak >nul
goto wait_for_device

:emulator_connected
call :wake_phone
echo [TrueTagger] Virtual phone connected.
exit /b 0

:emulator_timeout_device
echo [TrueTagger] The emulator window opened, but ADB never connected.
echo.
"%ADB%" devices -l
exit /b 1

:set_env
set "JAVA_HOME=%JDK%"
set "ANDROID_HOME=%ANDROID_SDK%"
set "ANDROID_USER_HOME=%ROOT%\.android-home"
set "ANDROID_EMULATOR_HOME=%ROOT%\.android-home"
set "ANDROID_AVD_HOME=%ROOT%\.android-home\avd"
set "GRADLE_USER_HOME=%ROOT%\.gradle-local"
if not exist "%ROOT%\.android-home" mkdir "%ROOT%\.android-home"
if not exist "%ANDROID_AVD_HOME%" mkdir "%ANDROID_AVD_HOME%"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ANDROID_HOME%\emulator;%PATH%"
exit /b 0

:copy_apk
for /F "tokens=1,* delims==" %%A in ('findstr /B "appVersion=" "%PROJECT%\gradle.properties"') do set "APP_VERSION=%%B"
set "APK=%ROOT%\app\build\outputs\apk\debug\app-debug.apk"
if not exist "%APK%" goto apk_not_found
if not exist "%ROOT%\dist" mkdir "%ROOT%\dist"
copy /Y "%APK%" "%ROOT%\dist\TrueTagger-%APP_VERSION%-debug.apk" >nul
echo.
echo [TrueTagger] APK created:
echo %ROOT%\dist\TrueTagger-%APP_VERSION%-debug.apk
exit /b 0

:apk_not_found
echo [TrueTagger] Gradle finished, but the APK was not found.
exit /b 1

:cleanup_old_layout
if exist "%ROOT%\app\android" rmdir /S /Q "%ROOT%\app\android"
if exist "%ROOT%\app\desktop" rmdir /S /Q "%ROOT%\app\desktop"
if exist "%ROOT%\app\shared" rmdir /S /Q "%ROOT%\app\shared"
if exist "%ROOT%\gradle" rmdir /S /Q "%ROOT%\gradle"
if exist "%ROOT%\gradle.properties" del /Q "%ROOT%\gradle.properties"
if exist "%ROOT%\gradlew.bat" del /Q "%ROOT%\gradlew.bat"
if exist "%ROOT%\gradlew" del /Q "%ROOT%\gradlew"
if exist "%ROOT%\settings.gradle.kts" del /Q "%ROOT%\settings.gradle.kts"
if exist "%ROOT%\build.gradle.kts" del /Q "%ROOT%\build.gradle.kts"
if exist "%ROOT%\local.properties" del /Q "%ROOT%\local.properties"
exit /b 0

:action_failed
echo.
echo [TrueTagger] The operation could not be completed.

:action_done
echo.
pause
goto menu

:no_curl
echo [TrueTagger] curl.exe was not found.
exit /b 1

:no_tar
echo [TrueTagger] tar.exe was not found.
exit /b 1

:end
endlocal
exit /b 0
