@ECHO OFF
SETLOCAL
SET "MAVEN_PROJECTBASEDIR=%~dp0"
SET "WRAPPER_DIR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper"
SET "WRAPPER_JAR=%WRAPPER_DIR%\maven-wrapper.jar"
SET "WRAPPER_PROPERTIES=%WRAPPER_DIR%\maven-wrapper.properties"
IF NOT DEFINED JAVA_HOME FOR /F "tokens=3" %%J IN ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /C:"java.home ="') DO SET "JAVA_HOME=%%J"
IF NOT EXIST "%JAVA_HOME%\bin\java.exe" (
  FOR /D %%J IN ("D:\jdk17*") DO IF EXIST "%%J\bin\java.exe" SET "JAVA_HOME=%%~fJ"
)
IF NOT EXIST "%JAVA_HOME%\bin\java.exe" (
  FOR /D %%J IN ("C:\Program Files\Java\jdk*") DO IF EXIST "%%J\bin\java.exe" SET "JAVA_HOME=%%~fJ"
)

IF NOT EXIST "%WRAPPER_JAR%" (
  IF EXIST "%USERPROFILE%\.m2\wrapper\dists" (
    FOR /R "%USERPROFILE%\.m2\wrapper\dists" %%F IN (mvn.cmd) DO IF EXIST "%%F" (
      SET "CACHED_MVN_CMD=%%F"
      GOTO RUN_CACHED
    )
  )
  powershell.exe -NoProfile -NonInteractive -Command "$ErrorActionPreference='Stop'; [Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; (New-Object Net.WebClient).DownloadFile('https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar', '%WRAPPER_JAR%')"
  IF ERRORLEVEL 1 (
    ECHO Maven Wrapper download failed. 1>&2
    EXIT /B 1
  )
)

IF DEFINED JAVA_HOME (
  SET "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) ELSE (
  SET "JAVA_EXE=java.exe"
)

"%JAVA_EXE%" -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
SET "WRAPPER_EXIT=%ERRORLEVEL%"
ENDLOCAL & EXIT /B %WRAPPER_EXIT%

:RUN_CACHED
CALL "%CACHED_MVN_CMD%" %*
SET "WRAPPER_EXIT=%ERRORLEVEL%"
ENDLOCAL & EXIT /B %WRAPPER_EXIT%
