@echo off
title OctoGuide Backend (8080)
set "JAVA_HOME=C:\Program Files\Java\jdk1.8.0_192"
call "D:\programli\Java web\java\apache-maven-3.6.3\bin\mvn.cmd" -f "%~dp0backend\pom.xml" spring-boot:run
pause
