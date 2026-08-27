# 定时播放 App（Android）

定时打开网址 / 定时播放音视频文件的安卓 App。典型场景：校园广播、店铺背景音乐、会议签到播放、公共屏内容定时切换。

## 功能

- **任务列表**：卡片展示任务（名称、频次、时间、内容类型、停止方式），开关启停，显示"下一个任务"
- **新建任务**（所有选项一屏展示）：
  - 触发时间（时:分）
  - 频次：每天 / 每周（勾选周几）/ 特定日期
  - 内容：打开网址 / 导入音视频文件（mp3、wav、mp4 等）
  - 停止设置：
    - 自动停止：循环 N 次后停止（N=1 即播放完自然停止）/ 播放 N 分钟后停止
    - 锁定 L 分钟：前 L 分钟不显示关闭选项，到点出现停止按钮；若没手动停，按自动停止条件结束
- **任务详情**：配置汇总、立即播放、删除
- **播放页**：全屏计时播放，锁定倒计时、暂停/继续、停止、自动停止
- **后台调度**：workmanager 每 15 分钟检查一次到期任务，到点自动打开网址（音视频后台播放见"已知限制"）

## 技术栈

- Flutter（Dart）
- shared_preferences：任务本地持久化
- workmanager：Android 后台周期任务
- url_launcher：打开网址
- just_audio：音频播放（本地文件 / 网络流）
- video_player：视频播放
- file_picker：选择导入音视频文件

## 构建方式（二选一）

### 方式一：GitHub Actions 云端打包（推荐，无需本地环境）

1. 在 GitHub 新建仓库，把本目录代码推上去（或直接上传文件）
2. 仓库 Actions 页面会自动执行 `Build APK` 工作流（推 main 分支自动触发；也可手动 Run workflow）
3. 构建完成后，在 Actions 运行记录页底部 **Artifacts** 区下载 `app-release-apk`
4. 把 `app-release.apk` 传到手机安装即可

### 方式二：本地构建

1. 安装 [Flutter SDK](https://docs.flutter.dev/get-started/install)（含 Android Studio / Android SDK，约 30 分钟）
2. 在本目录执行：

```bash
flutter create --platforms=android --project-name scheduled_player_app .
flutter pub get
flutter run          # 连接手机或模拟器直接运行调试
flutter build apk --release   # 生成 release APK
```

3. APK 输出位置：`build/app/outputs/flutter-apk/app-release.apk`

## 安装到手机

- 将 APK 通过微信/QQ/数据线传到手机
- 点击 APK 安装；若提示"未知来源"，在设置中允许安装此来源应用

## 已知限制与进阶路线

1. **后台播放音视频**：Android 后台拉起媒体播放受系统限制。当前骨架中后台只自动打开网址；音视频到点触发建议升级为**前台服务（Foreground Service）+ 媒体通知**方案（Flutter 可用 `audio_service` 插件），播放不中断且锁屏可见。
2. **触发精度**：workmanager 最小周期为 15 分钟，任务触发存在 0~15 分钟延迟。如需精确到分钟，可接入 Android 精确闹钟（`android_alarm_manager_plus`，Android 12+ 需申请 `SCHEDULE_EXACT_ALARM` 权限，本项目的 AndroidManifest 已预留）。
3. **开机自启**：已声明 `RECEIVE_BOOT_COMPLETED`，workmanager 会自动恢复周期任务；部分国产 ROM 需在系统设置中允许应用自启动。
4. 本地调试时如遇插件版本冲突，可执行 `flutter pub upgrade` 升级到兼容版本。

## 项目结构

```
lib/
├── main.dart                 # 入口 + workmanager 初始化
├── models/task.dart          # 任务数据模型
├── storage/task_store.dart   # 本地持久化（JSON）
├── scheduler/scheduler.dart  # 后台到期检查
├── pages/
│   ├── home_page.dart        # 任务列表
│   ├── create_page.dart      # 新建任务
│   └── detail_page.dart      # 任务详情
└── widgets/
    └── player_page.dart      # 播放页（计时/锁定/自动停止）
```
