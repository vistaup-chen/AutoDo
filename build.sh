#!/bin/bash
# 本地构建脚本 - 不影响系统环境变量
# 使用 Android Studio 内置的 JDK

export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"

echo "=========================================="
echo "开始构建 AutoTask"
echo "=========================================="

# 运行构建
./gradlew.bat assembleDebug 2>&1

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "构建成功！"
    echo "=========================================="

    APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

    # 检查模拟器是否连接
    echo ""
    echo "检查模拟器连接..."
    adb devices

    # 安装到模拟器
    echo ""
    echo "正在安装到模拟器..."
    adb install -r "$APK_PATH"

    if [ $? -eq 0 ]; then
        echo ""
        echo "=========================================="
        echo "安装成功！"
        echo "=========================================="

        # 自动开启无障碍服务（adb 有系统权限，可静默开启，省去每次手动点击）
        # 注意：改过 accessibility_service_config.xml 的版本仍需手动重开一次
        echo ""
        echo "自动开启无障碍服务..."
        adb shell settings put secure enabled_accessibility_services com.autotask/com.autotask.service.AutoTaskAccessibilityService
        adb shell settings put secure accessibility_enabled 1
        echo "无障碍服务已开启"

        # 启动应用
        echo ""
        echo "启动应用..."
        adb shell am start -n com.autotask/.MainActivity
    else
        echo ""
        echo "安装失败，请检查模拟器连接"
    fi
else
    echo ""
    echo "构建失败，请检查上方错误信息"
fi
