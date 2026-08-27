package com.autotask.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.autotask.service.AutoTaskAccessibilityService

/**
 * 开机自启广播接收器
 * 确保设备重启后无障碍服务能自动恢复
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AT-BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i(TAG, "收到广播: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                // 无障碍服务由系统自动管理，用户在设置中开启后
                // 系统会在开机时自动启动，无需手动 startService
                // 这里只做日志记录和状态检查
                val enabled = AutoTaskAccessibilityService.isAccessibilityServiceEnabled(context)
                Log.i(TAG, "无障碍服务状态: ${if (enabled) "已启用" else "未启用"}")

                if (!enabled) {
                    Log.w(TAG, "无障碍服务未启用，用户需要在设置中手动开启")
                    // 发送通知提醒用户（可选）
                    notifyUserEnableAccessibility(context)
                }
            }
        }
    }

    private fun notifyUserEnableAccessibility(context: Context) {
        // 可以发送一个通知提醒用户重新开启无障碍服务
        // 这里只记录日志，实际可根据需求添加通知
        Log.i(TAG, "建议用户开启无障碍服务以确保自动化任务正常运行")
    }
}
