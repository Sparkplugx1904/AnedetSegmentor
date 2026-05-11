@echo off
echo ========================================
echo Conjunctiva Segmentation - Project Verification
echo ========================================
echo.

echo [1/10] Checking project structure...
if exist "app\src\main\java\com\conjunctiva\segmentation\MainActivity.kt" (
    echo [OK] MainActivity.kt found
) else (
    echo [ERROR] MainActivity.kt not found
    goto :error
)

if exist "app\src\main\java\com\conjunctiva\segmentation\ConjunctivaSegmentor.kt" (
    echo [OK] ConjunctivaSegmentor.kt found
) else (
    echo [ERROR] ConjunctivaSegmentor.kt not found
    goto :error
)

if exist "app\src\main\java\com\conjunctiva\segmentation\ImageUtils.kt" (
    echo [OK] ImageUtils.kt found
) else (
    echo [ERROR] ImageUtils.kt not found
    goto :error
)

if exist "app\src\main\java\com\conjunctiva\segmentation\OverlayView.kt" (
    echo [OK] OverlayView.kt found
) else (
    echo [ERROR] OverlayView.kt not found
    goto :error
)

echo.
echo [2/10] Checking model file...
if exist "app\src\main\assets\best_float16.tflite" (
    echo [OK] Model file found in assets
) else (
    echo [WARNING] Model file not found in assets
    echo Checking models folder...
    if exist "models\best_float16.tflite" (
        echo [INFO] Model found in models folder
        echo [ACTION] Copy to assets: mkdir app\src\main\assets ^& copy models\best_float16.tflite app\src\main\assets\
    ) else (
        echo [ERROR] Model file not found anywhere
        goto :error
    )
)

echo.
echo [3/10] Checking build files...
if exist "app\build.gradle" (
    echo [OK] app/build.gradle found
) else (
    echo [ERROR] app/build.gradle not found
    goto :error
)

if exist "build.gradle" (
    echo [OK] build.gradle found
) else (
    echo [ERROR] build.gradle not found
    goto :error
)

if exist "settings.gradle" (
    echo [OK] settings.gradle found
) else (
    echo [ERROR] settings.gradle not found
    goto :error
)

echo.
echo [4/10] Checking resources...
if exist "app\src\main\res\layout\activity_main.xml" (
    echo [OK] activity_main.xml found
) else (
    echo [ERROR] activity_main.xml not found
    goto :error
)

if exist "app\src\main\AndroidManifest.xml" (
    echo [OK] AndroidManifest.xml found
) else (
    echo [ERROR] AndroidManifest.xml not found
    goto :error
)

echo.
echo [5/10] Checking documentation...
if exist "README.md" (
    echo [OK] README.md found
) else (
    echo [WARNING] README.md not found
)

if exist "QUICKSTART.md" (
    echo [OK] QUICKSTART.md found
) else (
    echo [WARNING] QUICKSTART.md not found
)

echo.
echo [6/10] Checking Gradle wrapper...
if exist "gradlew.bat" (
    echo [OK] gradlew.bat found
) else (
    echo [ERROR] gradlew.bat not found
    goto :error
)

if exist "gradle\wrapper\gradle-wrapper.properties" (
    echo [OK] gradle-wrapper.properties found
) else (
    echo [ERROR] gradle-wrapper.properties not found
    goto :error
)

echo.
echo [7/10] Checking local.properties...
if exist "local.properties" (
    echo [OK] local.properties found
) else (
    echo [WARNING] local.properties not found
    echo [INFO] Will be created by Android Studio on first sync
)

echo.
echo [8/10] Checking test files...
if exist "app\src\test\java\com\conjunctiva\segmentation\ImageUtilsTest.kt" (
    echo [OK] Unit tests found
) else (
    echo [WARNING] Unit tests not found
)

if exist "app\src\androidTest\java\com\conjunctiva\segmentation\ModelTest.kt" (
    echo [OK] Instrumented tests found
) else (
    echo [WARNING] Instrumented tests not found
)

echo.
echo [9/10] Checking ProGuard rules...
if exist "app\proguard-rules.pro" (
    echo [OK] ProGuard rules found
) else (
    echo [WARNING] ProGuard rules not found
)

echo.
echo [10/10] Summary...
echo.
echo ========================================
echo VERIFICATION COMPLETE
echo ========================================
echo.
echo Project structure: OK
echo Core files: OK
echo Build files: OK
echo Resources: OK
echo.
echo Next steps:
echo 1. Open project in Android Studio
echo 2. Sync Gradle (File ^> Sync Project with Gradle Files)
echo 3. Build project (Build ^> Make Project)
echo 4. Run on device (Run ^> Run 'app')
echo.
echo For detailed instructions, see:
echo - QUICKSTART.md
echo - BUILD_INSTRUCTIONS.md
echo.
goto :end

:error
echo.
echo ========================================
echo VERIFICATION FAILED
echo ========================================
echo.
echo Please check the errors above and fix them.
echo.
goto :end

:end
pause
