# feature/about — 关于页与开发者入口

展示版本 / 免责声明 / 捐赠入口；**连续点击版本号 7 次开启开发者选项**。开启后可进入与设置页相同的 `DeveloperSettingsScreen`（不维护第二套开发者 UI）。

## 职责

- 版本信息、开源/仓库链接、免责声明、捐赠（微信/支付宝二维码保存到相册、爱发电外链；第 10 次打开后一次性提示）。
- 开发者入口（连点 7 次）；诊断与调试能力经注入控制器与设置共用组件。

## 入口

1. `AboutScreen.kt` — 全部（单文件）。
2. `MainActivity.AppNavHost`（入口）、`feature/settings/screens/DeveloperSettingsScreen`（共用）、`core/preferences/SettingsRepository`（开发者开关）。

## 数据流

```text
版本号连点 7 次 → DeveloperConfig.enabled = true → 设置首页出现「开发者选项」分类
DeveloperSettingsScreen ← 关于页与设置页共用（不再维护第二套开发者 UI）
```

## 不变量 / 边界

- 不直接操作 WindowManager / Root / JNI；诊断与调试经注入控制器。
- 关于页开发者入口可直接 `save`（旁路）；设置页「开发者选项」分类内开关可关闭。
- 捐赠图片保存：API ≤ 28 需要 WRITE_EXTERNAL_STORAGE；经 MediaStore 写入「相册/HZZS」。

## 改这个包前必读

- 改开发者入口：同步 `feature/settings/screens/DeveloperSettingsScreen` 与 `core/model/DeveloperConfig`。
- 改版本号：同步 `app/build.gradle.kts` 与 CHANGELOG（若用户可见）。
- 改捐赠保存：`MainActivity.saveDonationImage`（二维码资源 + MediaStore + 存储权限分支）。
- 仓库 GitHub/Gitee 链接、许可证等关键信息不得无故删除。

## 测试

- 门禁：`python tools/quality/check_resources.py`、`python tools/quality/check_project.py`。
- 真机验证：连点解锁、捐赠图片保存、免责声明展示。
