@echo off
rem ForgeMind launcher (Windows cmd, no bash/powershell required)
rem Usage:
rem   forgemind.cmd                              -> interactive REPL
rem   forgemind.cmd "task"                       -> single task
rem   forgemind.cmd --yes "task"
rem   forgemind.cmd --working-dir D:\workspace "task"
rem   forgemind.cmd --config config.yml "task"
setlocal

set "JAR=%~dp0agent-cli\target\forgemind.jar"

if not exist "%JAR%" (
    echo [ERROR] forgenind.jar not found: "%JAR%"
    echo [ERROR] build it first:  mvn package
    exit /b 1
)

where java >nul 2>nul
if errorlevel 1 (
    echo [ERROR] java not found in PATH. Please install JDK 17+ or set JAVA_HOME.
    exit /b 1
)

java -jar "%JAR%" %*
exit /b %errorlevel%
