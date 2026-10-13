# P3R Daily 1.1 — 离线安卓打卡 + 原生桌面小组件

> **这是安卓应用源码工程，不是 APK。** 无需在本机安装 Android Studio：使用与此工程一起交付的 **`P3R_小组件v1.1_Colab云端生成APK.ipynb`**，把 `.ipynb` 上传到 [Google Colab](https://colab.research.google.com/)，点击「运行时 → 全部运行」，构建成功后下载 APK。云端构建流程及真机运行尚未执行验证。

## v1.1 更新

- **小组件的「下一待办 ›」只在桌面切换任务，不打开 App**，并可从当天最后一项跨到后续日期。浏览过任务不会标记完成；下次仍可按未完成状态查找。到末尾后会循环回第一项未完成任务。
- **自适应尺寸与字号**：Android 12+ 读取启动器提供的精确 Widget 尺寸并分别生成布局；Android 8–11 使用大小范围作为回退。字体按可用宽高和中文标题长度缩放，文字太长时省略，点击任务正文看完整步骤。
- **省空间的标题设计**：顶栏整合斜切 `P3` 标识、紧凑日期、前后换日和当天完成进度。取消原先的 `P3R / DAILY` 独立横幅，日期由大号缩小到约 16–21sp。
- **两套扁平 P3R 风格配色**：DAY 皇家蓝 / NIGHT 深海蓝，白色正文、冰蓝强调。较小的 Widget 隐藏单独的时段标题；较高的 Widget 显示可用的地点信息。
- **原先的功能不变**：普通待办在 Widget 内一键完成；复杂多步骤（例如 4 月 21 日的碎片）用「查看步骤」进入 App，不会被一键错误完成。任务正文、`P3` 标志、日期也可用于打开 App。左右箭头仍是切换游戏日期。

## 应用/数据

- 包含 **284 个游戏日期、691 条记录、623 条可打卡任务**，离线使用。App 支持任务检索、原文重组后的步骤、黄昏碎片分项打卡、深蓝/亮蓝日夜主题、JSON 进度导出导入。
- 原生小组件与 WebView 使用同一个 `SharedPreferences` 持久进度，不需要联网和账户，也不会跟现实日期自动跳转。
- Widget 的当前位置单独保存；没有点击「完成」的待办不会因为「下一待办」被划掉。
- **日期与进度**：上方小数字例如 `04.22` 是游戏日期，右边 `0/1` 是这一天已完成/总打卡任务。点击「下一待办」可跨日；点击两侧箭头只按日期切换。

## 从 v1.0 升级的注意事项

- 仍使用原应用 ID `app.p3r.dayplanner` 与相同的存储字段，保留原任务 ID；版本已设为 1.1.0 / versionCode 2。
- **重要：如果两次 APK 是在不同的 Colab 临时运行环境中使用 Debug 签名构建的，签名密钥通常不同，安卓系统可能拒绝直接覆盖安装。** 请先在已安装的旧 App 中「导出进度 JSON」到手机文件，再卸载旧 App，安装新 APK，最后导入备份。**卸载旧 App 会清除内部打卡数据，不要跳过备份。**
- 如要长期无缝更新，应使用安全持久保存的个人签名密钥构建 Release APK；当前这个一键 Colab 版本仍是方便个人安装的 Debug APK。

## 技术要求

- `minSdk 26`（Android 8.0）、`compileSdk 35`、Java 17、Gradle 8.9、Android Gradle Plugin 8.7.3。
- `PlannerWidget.java`：Android 12+ `RemoteViews(Map<SizeF, RemoteViews>)` 精确尺寸布局；旧系统使用 `OPTION_APPWIDGET_MIN/MAX_*` 和 `onAppWidgetOptionsChanged`。
- 小组件所有按键均使用 `PendingIntent`，`下一待办` 是发送给 `AppWidgetProvider` 的 Broadcast，不会启动 Activity。
- 当前环境缺少 Android SDK，**已完成 XML 解析、RemoteViews 允许的控件类型核对、Java stub 类型/语法编译与任务导航算法测试，但未完成真正的 Android 编译、APK 安装或真机点击测试**。

该工具为非官方粉丝作品，与 ATLUS / SEGA 无关联，未使用游戏原生素材。
