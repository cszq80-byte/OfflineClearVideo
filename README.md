# OfflineClearVideo — 安卓离线 AI 视频清晰增强（实验性）

中文 Android 视频处理应用：从相册视频选择器或文件管理器选取本地视频、普通画质增强、2 倍 FSRCNN 神经网络超分辨率、MP4 H.264 导出、可兼容音轨直通。本 App 不请求 INTERNET 权限；运行时模型已打包在 APK 中。首次安装后不需要联网。

## 获取 APK

进入 GitHub Actions -> Build Offline Android APK -> 最近一次成功的任务 -> Artifacts 下载 OfflineClearVideo-v0.4.1-AI-APK，解压得到 app-debug.apk。属于调试签名版，安装时可能弹出安全提示。

## AI 模型

构建期间从 Saafke/FSRCNN_Tensorflow 固定提交下载 Apache-2.0 协议的 FSRCNN x2 预训练 GraphDef，转换为 TensorFlow Lite 并核验与原计算图的数值差异。转换在 GitHub Actions 发生，APK 在手机上无需下载模型。处理 Y 亮度分量，色度双线性放大；不保证能恢复不存在的纹理。

## 限制

AI 限制输入 <= 1280×720（同样适用竖屏）、逐帧纯 CPU 运行，可能很慢和耗电；普通增强支持到 4K 输入上限。安卓硬件编码器差异较大，支持 YUV_420_888 帧读写的设备才能导出。HDR/DRM 暂不支持。支持 AAC/AMR 的音轨直接保留，其他音频格式可能静音。务必短视频验证后再处理长视频。
