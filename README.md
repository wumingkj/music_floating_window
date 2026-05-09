# MusicFloatingWindow - 音乐悬浮窗歌词应用

一个基于 Kotlin 开发的 Android 应用，用于在悬浮窗中显示音乐播放时的歌词。支持多种音乐应用的通知监听，自动同步歌词显示。

## 📱 项目概述

MusicFloatingWindow 是一个歌词悬浮窗应用，可以：
- 监听多种音乐应用的播放通知
- 自动获取并解析歌词
- 在悬浮窗中实时显示当前歌词
- 支持歌词的拖动和交互

## 🏗️ 项目架构

music_floating_window/
 ├── app/
 │ └── src/
 │ └── main/
 │ ├── kotlin/
 │ │ └── com/wuming/musicFW/
 │ │ ├── MainActivity.kt # 主界面活动
 │ │ ├── BootReceiver.kt # 开机自启动接收器
 │ │ ├── services/ # 服务层
 │ │ │ ├── NotificationListenerService.kt # 通知监听服务
 │ │ │ ├── FloatingLyricsService.kt # 悬浮歌词服务
 │ │ │ ├── MediaNotificationService.kt # 媒体通知服务
 │ │ │ └── NetEaseMusicApi.kt # 网易云音乐API
 │ │ ├── managers/ # 管理器层
 │ │ │ ├── PermissionManager.kt # 权限管理器
 │ │ │ ├── AppStateManager.kt # 应用状态管理器
 │ │ │ └── LyricsManager.kt # 歌词管理器
 │ │ ├── models/ # 数据模型层
 │ │ │ ├── SongInfo.kt # 歌曲信息模型
 │ │ │ ├── LyricsLine.kt # 歌词行模型
 │ │ │ └── AppConfig.kt # 应用配置模型
 │ │ │ └── settings/ # 设置相关
 │ │ │ └── SettingsActivity.kt # 设置活动
 │ │ └── utils/ # 工具类
 │ │ ├── LogHelper.kt # 日志帮助类
 │ │ ├── TimeFormatter.kt # 时间格式化工具
 │ │ └── LyricsParser.kt # 歌词解析器
 │ ├── res/ # 资源文件
 │ └── AndroidManifest.xml # 应用清单文件
 ├── build.gradle.kts # 项目构建配置
 └── settings.gradle.kts # 项目设置


## 🔧 技术栈

- **开发语言**: Kotlin
- **最低 SDK**: 24 (Android 7.0)
- **目标 SDK**: 34 (Android 14)
- **构建工具**: Gradle 8.14
- **Android Gradle Plugin**: 8.2.0

### 主要依赖

- **OkHttp**: 4.12.0 - HTTP 客户端，用于网络请求
- **Gson**: 2.10.1 - JSON 解析库
- **Kotlin Coroutines**: 1.7.3 - 协程支持
- **AndroidX Core KTX**: 1.12.0 - Kotlin 扩展
- **AndroidX AppCompat**: 1.6.1 - 兼容性支持

## 📋 核心功能

### 1. 通知监听服务 (NotificationListenerService)

**功能**: 
- 监听系统通知，捕获音乐播放信息
- 识别支持的音乐应用（网易云、QQ音乐、酷狗、酷我、Spotify等）
- 获取当前播放歌曲的详细信息

**支持的音乐应用**:
- 网易云音乐 (netease, cloudmusic)
- QQ音乐 (qqmusic)
- 酷狗音乐 (kugou)
- 酷我音乐 (kuwo)
- Spotify (spotify)
- YouTube Music (youtube)
- 其他包含 "music" 关键词的应用

**核心方法**:
```kotlin
fun setCallbacks(
    onSongChanged: ((SongInfo) -> Unit)?,
    onPositionUpdate: ((Long) -> Unit)?
)
```

### 2. 网易云音乐API (NetEaseMusicApi)

**功能**:
- 搜索歌曲
- 获取歌词
- 生成歌曲分享链接

**主要方法**:
```kotlin
suspend fun searchSong(keyword: String): JsonObject?
suspend fun getLyric(songId: Long): JsonObject?
fun getShareUrl(songId: Long): String
```

### 3. 悬浮歌词服务 (FloatingLyricsService)

**功能**:
- 管理悬浮窗的显示和隐藏
- 更新悬浮窗中的歌词内容
- 提供前台服务支持

**主要方法**:
```kotlin
fun requestShow(context: Context, lyrics: String)
fun requestHide(context: Context)
fun requestUpdate(context: Context, lyrics: String)
fun isRunning(): Boolean
```

### 4. 权限管理 (PermissionManager)

**功能**:
- 检查通知监听权限
- 检查悬浮窗权限
- 请求权限授权

**主要方法**:
```kotlin
fun hasNotificationListenerPermission(): Boolean
fun hasOverlayPermission(): Boolean
fun requestNotificationListenerPermission(activity: Activity)
fun requestOverlayPermission(activity: Activity)
fun getAllPermissionsStatus(): Map<String, Boolean>
```

### 5. 歌词解析器 (LyricsParser)

**功能**:
- 解析 LRC 格式歌词
- 提取时间戳和歌词文本
- 查找当前播放行

**主要方法**:
```kotlin
fun parseLyric(lyricText: String): List<LyricsLine>
fun findCurrentLineIndex(lyrics: List<LyricsLine>, position: Long): Int
```

## 🎨 UI设计

### 主界面 (MainActivity)

**布局结构**:
- 标题栏: 应用名称
- 歌曲信息区: 显示当前歌曲名称、艺术家、专辑
- 位置信息: 显示当前播放位置
- 歌词显示区: 显示当前歌词列表
- 控制按钮区:
  - 开始/停止监听按钮
  - 权限设置按钮
  - 开启/关闭悬浮窗按钮
- 调试日志区: 显示应用运行日志

### 悬浮窗 (FloatingLyricsView)

**特性**:
- 半透明黑色背景
- 圆角设计
- 可拖动位置
- 点击交互支持
- 自定义文本颜色和大小

## ⚙️ 权限说明

### 必需权限

```xml
<!-- 开机自启动 -->
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

<!-- 悬浮窗显示 -->
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />

<!-- 前台服务 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />

<!-- 通知权限 -->
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<!-- 通知监听 -->
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />

<!-- 无障碍服务 -->
<uses-permission android:name="android.permission.BIND_ACCESSIBILITY_SERVICE" />
```

### 权限配置说明

1. **通知监听权限**: 需要用户在系统设置中手动授权
2. **悬浮窗权限**: 需要用户在系统设置中手动授权
3. **其他权限**: 在 Android 6.0+ 需要运行时请求

## 🔌 组件注册

### 服务组件

```xml
<!-- 通知监听服务 -->
<service
    android:name="com.wuming.musicFW.services.NotificationListenerService"
    android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.service.notification.NotificationListenerService" />
    </intent-filter>
</service>

<!-- 悬浮窗服务 -->
<service
    android:name="com.wuming.musicFW.services.FloatingLyricsService"
    android:exported="true" />
```

### 广播接收器

```xml
<!-- 开机自启动 -->
<receiver
    android:name=".BootReceiver"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
    </intent-filter>
</receiver>
```

## 🚀 使用说明

### 首次使用

1. 安装应用
2. 打开应用主界面
3. 点击"权限设置"按钮
4. 在系统设置中授权：
   - 通知监听权限
   - 悬浮窗权限
5. 返回应用，点击"开始监听"
6. 播放音乐，应用将自动获取歌词

### 功能测试

1. **监听测试**:
   - 点击"开始监听"按钮
   - 播放音乐（使用支持的音乐应用）
   - 查看歌曲信息是否正确显示
   - 查看歌词是否正确获取

2. **悬浮窗测试**:
   - 确保已授权悬浮窗权限
   - 点击"开启悬浮"按钮
   - 验证悬浮窗是否正常显示
   - 拖动悬浮窗测试交互

### 调试日志

应用界面底部有实时日志显示区域，可以查看：
- 权限状态
- 服务连接状态
- 歌曲信息更新
- 歌词获取状态
- 错误信息

## 🐛 常见问题

### 1. 通知监听不工作

**可能原因**:
- 未在系统设置中授权通知监听权限
- 通知监听服务未启用
- 正在使用的音乐应用不被支持

**解决方法**:
1. 检查系统设置中的通知访问权限
2. 重启应用
3. 尝试播放其他音乐应用

### 2. 悬浮窗不显示

**可能原因**:
- 未授权悬浮窗权限
- 系统限制了悬浮窗功能
- 悬浮窗服务未正常启动

**解决方法**:
1. 检查系统设置中的悬浮窗权限
2. 在应用权限设置中开启悬浮窗权限
3. 重启应用

### 3. 无法获取歌词

**可能原因**:
- 网络连接问题
- 网易云音乐 API 不可用
- 歌曲在网易云音乐中不存在

**解决方法**:
1. 检查网络连接
2. 稍后重试
3. 尝试搜索其他歌曲

### 4. 应用崩溃

**可能原因**:
- 权限问题
- 服务冲突
- 代码异常

**解决方法**:
1. 查看应用日志
2. 检查所有权限是否已授权
3. 重启应用或手机

## 📝 开发说明

### 构建项目

```bash
# 清理并构建 Debug 版本
.\gradlew.bat clean
.\gradlew.bat assembleDebug

# 构建 Release 版本
.\gradlew.bat assembleRelease
```

### 查看日志

```bash
# 查看应用相关日志
adb logcat | grep -E "MusicFW|NotificationListenerService|FloatingLyricsService"

# 查看所有日志
adb logcat
```

### 代码规范

- 使用 Kotlin 标准库
- 遵循 Android 编码规范
- 添加适当的注释和文档
- 使用 LogHelper 进行日志记录

## 🔮 未来规划

- [ ] 完善权限管理模块
- [ ] 实现歌词缓存机制
- [ ] 添加设置界面
- [ ] 支持更多音乐应用
- [ ] 添加歌词桌面小部件
- [ ] 实现通知栏歌词显示
- [ ] 添加深色/浅色主题切换
- [ ] 支持自定义悬浮窗样式
- [ ] 添加歌词翻译功能
- [ ] 实现多语言支持

## 📄 许可证

本项目仅供学习交流使用，请勿用于商业目的。

## 📞 联系方式

如有问题或建议，请通过以下方式联系：
- 提交 Issue
- 发送邮件至：[邮箱地址]

## 🙏 致谢

感谢以下开源项目：
- OkHttp
- Gson
- Kotlin Coroutines
- AndroidX

---

**版本**: 1.0.0  
**更新日期**: 2026-05-09  
**许可证**: MIT License
