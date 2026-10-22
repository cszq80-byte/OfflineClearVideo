# OfflineClearVideo v0.7.1 — 修复相册只读视频启动失败

## 问题
在 Android Photo Picker 选择视频后，有时点击「开始离线处理」出现：
`UID ... does not have permission to content://media/picker/...`

根因：v0.7 的前台服务启动 Intent 把源视频与目标 MP4 URI 一起放在 ClipData 中并同时使用 `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION`。Android 会尝试为每个 URI 授予读写权限，但 Photo Picker 原视频是只读，导致 Service 根本不能启动。

## v0.7.1 修复
- 前台服务与界面本来就是同一个 App UID，直接以 String extra 传递视频 URI，无需对 URI 再授权；完全移除错误的 ClipData 和读写授权 flags。
- 启动前在当前已取得授权的 App 上下文中以只读 `openFileDescriptor(uri, "r")` 验证源视频可访问；如果授权过期，显示「重新选择视频」的明确提示。
- 保留 Photo Picker 回调中的 `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` 尝试，符合 Android 官方针对长时任务的建议。
- 增加真实错误 URI 结构的自动化回归测试，确认启动 Intent 不再对 Photo Picker 请求写权限。
- 完全保留原有离线 AI 模型、自然/均衡/明显强度、本地原片对比、高码率导出和前台通知。

## 使用
重新从系统相册选择视频，选择增强模式，点导出，出现保存对话框后选目标目录。导出完成前保留 App 的后台活动权限；如遇原视频读取授权过期，重新从相册选择一次即可。

## 测试限制
自动构建和 Android 模拟器可检查服务 Intent 及合成视频导出，但无法保证 OnePlus Ace 2/ColorOS 所有系统定制上的 Photo Picker 生命周期行为。仍需在你的手机用 2～5 秒短视频验证。
