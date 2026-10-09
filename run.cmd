@echo off
rem Abre o cliente com o console ja em UTF-8, para os acentos do log/chat
rem saírem corretos no cmd/PowerShell (codepage legado, ex.: 850/437, senão).
rem Uso: run.cmd [--embedded-server] [--port=N]
chcp 65001 >nul
java -jar "%~dp0target\domino-client.jar" %*
