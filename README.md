# OfflineClearVideo — 离线 AI 视频修复（v0.5，实验性）
Android 8.0+；无 INTERNET 权限，全部视频逐帧处理均在手机离线完成。

## 真正的 AI 选项
- **高画质 AI（Real-ESRGAN x4v3 → 2x 输出）**：RGB 全彩模型，生成更丰富的画面细节；默认推荐仅针对短片测试，移动端 CPU 推理可能非常慢。输入上限 720P。
- **快速 AI（FSRCNN）**：旧版，仅亮度通道增强，细节改善有限。
- 普通高清/4K：传统锐化与图像插值，只提高分辨率，不能产生新细节。

## 模型和校验
编译时从 `CoderViking/realesr-general-x4v3-onnx` 下载 Real-ESRGAN 的 ONNX 导出，SHA256 严格校验；采用 ONNX Runtime Android CPU 推理。模型原为 x4，局部裁切、推理之后下采样到 2x，控制内存和导出大小。公开权重 BSD-3-Clause。另保留旧 FSRCNN 模型。

工作流不仅测试模型和 MP4 导出，还在合成图案上验证新版模型与插值输出存在显著差异；合成图案对比不能证明用户实际视频必定显著改善。测试证据在 GitHub Actions 的构建产物内。

## 已知限制
新模型初版为 **CPU 推理**，可能每帧耗时数秒乃至更长，不建议直接处理长视频。不同手机的视频硬件编码兼容性仍需实测。失焦、噪点极重、严重压缩片源可能无法可靠恢复真实细节。输出一般为 H.264 MP4；AAC/AMR 音轨可直通，其他音频格式可能丢失。HDR/DRM 暂不支持。

## 构建
打开 GitHub Actions > Build Offline Android APK > 最近一次成功任务，下载 `OfflineClearVideo-v0.5-RealESRGAN-APK` ZIP，解压 app-debug.apk 安装。
