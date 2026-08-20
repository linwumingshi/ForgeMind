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

rem Encoding alignment: switch console to UTF-8 codepage and force JVM output to UTF-8.
rem (JDK 22 defaults stdout.encoding=GBK on this machine, mismatching UTF-8 terminals.
rem  On JDK 17 stdout/stderr.encoding are ignored but harmless; file.encoding covers it.)
chcp 65001 >nul
java -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -jar "%JAR%" %*
exit /b %errorlevel%
