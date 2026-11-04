@echo off
rem ---------------------------------------------------------------------------
rem spring-mcp preflight (read-only), Windows
rem
rem Answers one question: can this machine start the spring-mcp MCP server?
rem It looks for a JDK, looks for the jar, prints the config snippet for this
rem machine. Installs nothing, downloads nothing, writes nothing.
rem
rem   preflight.cmd
rem   preflight.cmd C:\tools\spring-mcp\spring-mcp-all.jar
rem
rem ASCII only, CRLF endings: cmd.exe parses batch files with the OEM code page.
rem ---------------------------------------------------------------------------
setlocal enabledelayedexpansion

set "READY=1"
set "JAR="

if not "%~1"=="" if exist "%~1" set "JAR=%~f1"
if not defined JAR if defined SPRING_MCP_JAR if exist "%SPRING_MCP_JAR%" set "JAR=%SPRING_MCP_JAR%"
if not defined JAR if exist "spring-mcp-all.jar" set "JAR=%CD%\spring-mcp-all.jar"
if not defined JAR if exist "target\spring-mcp-all.jar" set "JAR=%CD%\target\spring-mcp-all.jar"

set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA for %%I in (java.exe) do if not "%%~$PATH:I"=="" set "JAVA=%%~$PATH:I"

if not defined JAVA (
  echo java        MISSING  spring-mcp needs JDK 21 or newer
  set "READY=0"
) else (
  set "RAW="
  for /f "tokens=3" %%v in ('""!JAVA!" -version 2^>^&1 ^| findstr /i "version""') do if not defined RAW set "RAW=%%~v"
  set "MAJOR="
  for /f "tokens=1 delims=." %%a in ("!RAW!") do set "MAJOR=%%a"
  if "!MAJOR!" GEQ "21" (
    echo java        OK       !RAW!
  ) else (
    echo java        TOO OLD  !RAW! ^(need 21+^)
    set "READY=0"
  )
)

if not defined JAR (
  echo jar         MISSING  spring-mcp-all.jar not found here
  set "READY=0"
) else (
  echo jar         OK       !JAR!
)

echo.
if "!READY!"=="0" (
  echo Not ready. Get the missing pieces:
  echo   jar : https://github.com/zhaogegejiafuhao/spring-mcp/releases
  echo   java: any JDK 21+ ^(Temurin, Zulu, Microsoft OpenJDK^)
  exit /b 1
)

set "JARJ=!JAR:\=\\!"
set "JAVAJ=!JAVA:\=\\!"
echo project     --       pass --project=DIR, or projectRoot on each call
echo.
echo Add this to your MCP client config, then restart the client:
echo.
echo {
echo   "mcpServers": {
echo     "spring-mcp": {
echo       "command": "!JAVAJ!",
echo       "args": ["-jar", "!JARJ!", "--project=/abs/path/to/your-spring-app"]
echo     }
echo   }
echo }
echo.
echo Verify: ask the agent to call list-projects. It must answer, not error.
exit /b 0
