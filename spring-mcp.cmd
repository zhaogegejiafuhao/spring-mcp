@echo off
rem ---------------------------------------------------------------------------
rem spring-mcp launcher (Windows)
rem
rem Lets an MCP client start the server without knowing where java is:
rem   { "command": "C:\path\to\spring-mcp.cmd", "args": ["--project=C:\my\app"] }
rem
rem Looks for the fat jar next to this script first, then under target\ -- so it
rem works both from a fresh checkout and from an unpacked release.
rem
rem Kept ASCII-only with CRLF endings on purpose: cmd.exe parses batch files using
rem the OEM code page, and a UTF-8 Chinese message here corrupts the script.
rem ---------------------------------------------------------------------------
setlocal

set "DIR=%~dp0"
set "JAR=%DIR%spring-mcp-all.jar"
if not exist "%JAR%" set "JAR=%DIR%target\spring-mcp-all.jar"

if not exist "%JAR%" (
  echo [spring-mcp] spring-mcp-all.jar not found.>&2
  echo [spring-mcp] Build it first: mvnw.cmd package>&2
  exit /b 1
)

set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" set "JAVA=%JAVA_HOME%\bin\java.exe"
if not defined JAVA (
  for %%I in (java.exe) do if not "%%~$PATH:I"=="" set "JAVA=%%~$PATH:I"
)

if not defined JAVA (
  echo [spring-mcp] No Java runtime found.>&2
  echo [spring-mcp] spring-mcp needs JDK 21+. Either install one, or set JAVA_HOME:>&2
  echo [spring-mcp]   set JAVA_HOME=C:\path\to\jdk-21>&2
  exit /b 1
)

rem -Dfile.encoding=UTF-8 keeps Chinese log lines readable on a GBK console.
rem It only affects stderr logging; the MCP channel is always UTF-8.
"%JAVA%" -Dfile.encoding=UTF-8 -jar "%JAR%" %*
exit /b %ERRORLEVEL%
