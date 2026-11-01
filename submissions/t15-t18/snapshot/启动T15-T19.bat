@echo off
cd /d "%~dp0"
echo Starting Campus Lost and Found...
echo Open http://localhost:8080/app/index.html after startup
java -jar target\campus-lost-found-0.1.0.jar
pause
