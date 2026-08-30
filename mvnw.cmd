@REM ----------------------------------------------------------------------------
@REM Licensed to the Apache Software Foundation (ASF) under one
@REM or more contributor license agreements. See the NOTICE file
@REM distributed with this work for additional information.
@REM ----------------------------------------------------------------------------
@IF "%__MVNW_CMD__%"=="" (SET __MVNW_CMD__=%~nx0)
@SET WRAPPER_VERSION=3.3.4
@SET WRAPPER_JAR=%~dp0.mvn\wrapper\maven-wrapper.jar
@SET WRAPPER_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/%WRAPPER_VERSION%/maven-wrapper-%WRAPPER_VERSION%.jar
@IF EXIST "%WRAPPER_JAR%" GOTO run
@WHERE curl >NUL 2>NUL
@IF %ERRORLEVEL% NEQ 0 (
  @ECHO curl is required to bootstrap Maven Wrapper.
  @EXIT /B 1
)
@ECHO Downloading Maven Wrapper...
@curl.exe -fsSL %WRAPPER_URL% -o "%WRAPPER_JAR%"
@IF %ERRORLEVEL% NEQ 0 EXIT /B %ERRORLEVEL%
:run
@java.exe -Dmaven.multiModuleProjectDirectory=%~dp0 -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
