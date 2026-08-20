@echo off
:: 强制将 CMD 控制台编码设置为 UTF-8（解决中文乱码问题）
chcp 65001 >nul

:: 专用于 GitHub 网络不稳时的纯粹 Push 重试脚本（不包含任何 commit 操作）

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
echo.
set /a times+=1
echo 当前尝试推送第 %times% 次...

:: 执行推送命令（如果你的分支不是 main，请自行修改）
git push origin main

:: 检查上一条命令（git push）的执行结果
if %errorlevel% EQU 0 (
	goto BB
) else (
	echo [提示] 推送失败，5 秒后进行第 %times% 次重试...
	choice /t 5 /d y /n >nul
	goto AA
)

:BB
echo.
echo ==========================================
echo  推送成功！共尝试了 %times% 次。
echo ==========================================
@pause