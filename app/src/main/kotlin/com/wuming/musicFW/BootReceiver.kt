package com.wuming.musicFW

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.wuming.musicFW.services.FloatingLyricsService
import com.wuming.musicFW.utils.LogHelper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            LogHelper.d("收到开机广播，启动 FloatingLyricsService")
            val serviceIntent = Intent(context, FloatingLyricsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            LogHelper.d("FloatingLyricsService 启动成功")
        }
    }
}
