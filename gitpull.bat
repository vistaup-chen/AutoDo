@echo off
:: 通过在指定目录反复拉取github

::拉取超时时间
set pulltime=5000

:: 重试次数标记
set /a times=0

:: 清屏
cls

:: 检查外部传入的地址
if "%1"=="" (
	echo 未传入地址，使用当前目录
	goto AA
) else (
	echo 跳转到指定目录：%1
	cd /d %1
	goto AA
)

:AA
::echo 当前目录%cd%

::输出空行
echo.
set /a times+=1
echo 当前拉取第%times%次


::获取开始的时间戳
set "$=%temp%\Spring"
>%$% Echo WScript.Echo((new Date()).getTime())
for /f %%a in ('cscript -nologo -e:jscript %$%') do set timestamp1=%%a
del /f /q %$%
::时间戳
::echo %timestamp1%
::时间戳倒数第8个及其之后的字符（避免数字过长）
set /a time1=%timestamp1:~-8%


:: 拉取代码
git pull


::获取结束的时间戳
set "$=%temp%\Spring"
>%$% Echo WScript.Echo((new Date()).getTime())
for /f %%a in ('cscript -nologo -e:jscript %$%') do set timestamp2=%%a
del /f /q %$%
::时间戳
::echo %timestamp2%
::时间戳倒数第8个及其之后的字符（避免数字过长）
set /a time2=%timestamp2:~-8%


::判断消耗的时间
set /a costtime=%time2%-%time1%
echo 消耗的时间：%costtime% 毫秒


REM EQU - 等于
REM NEQ - 不等于
REM LSS - 小于
REM LEQ - 小于或等于
REM GTR - 大于
REM GEQ - 大于或等于
if %costtime% GTR %pulltime% (
	goto AA
) else (
	goto BB
)

goto AA

:BB
echo.
echo.

echo 拉取结束，共尝试%times%次
@pause