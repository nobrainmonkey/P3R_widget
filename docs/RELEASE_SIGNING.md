# 发布签名记录

## 当前正式签名：3.3.0 起

2026-10-17，项目所有者确认没有保存 3.2.0 的签名备份，在恢复尝试未找到私钥后，明确授权「如果你找不回来的话就直接用新的得了」。这条新授权取代此前「永久沿用 3.2.0，否则暂停发布」的要求。

3.3.0 使用新生成的 RSA 4096 位正式密钥，证书 SHA-256 为：

`83d4395bcac9e7b33b20f3bd2b1327c856e56a6f0bd4816c52a42550a2b653ba`

包名仍为 `app.p3r.planner`。**3.3.0 不能覆盖安装 3.2.0 或更早正式版**；先在旧版「我的 → 备份与恢复 → 分享备份」将数据保存到应用之外，确认备份能找到，再卸载旧版、安装新版并恢复。需要保留旧逐日攻略打勾进度的，也须先单独导出。

后续版本应沿用这份 3.3.0 密钥。正式发布入口为 `scripts/build_release.sh` 或 Signed Android release 工作流，校验最终 APK 只能由当前证书签名；直接运行 Gradle 得到的 release 构建不等于已通过正式发布校验。CI 的 debug 包不能作为正式更新包。

私有持久备份已上传并回读校验，恢复材料也已验证。

[3.6.2 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.6.2) 于 2026-10-22 发布，补上望月绫时的羁绊剧情、人格面具的电玩城训练信息，以及「今天可做」中的羁绊剧情。沿用上述证书，可覆盖 3.3.0～3.6.1。源码 `62005f460065e20b341b611bd0082d877d50e7df` 的完整 Android CI [39696741458](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39696741458) 已通过；本地 210 项针对性 Java 测试、33 项 Python 规则测试、release lint 和正式签名构建通过。经[预构建包发布流程 39699169905](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39699169905) 发布为 latest，非草稿、非预发布。

最终 `P3R-Planner-3.6.2.apk` 为 **1,221,672 bytes**，SHA-256 为：

`7df11983f204917de16682aac1cf3d14d047bb117521c1f02220bed063d80c73`

已下载公开 APK 和校验文件，与本地审计文件逐字节一致；标签指向上述源码，包名 `app.p3r.planner`、版本码 45、正式签名、zipalign 及内嵌规则／全书均已核对。Release 恰有 APK 和 88-byte 校验文件两个附件，发布 ID 为 `420179798`。规则包更新为 `3.6.2`（SHA-256 `532e66096a1fdee974db39ce7593bfa66e901200faa466a3b0c1f921403780f7`），策略版本仍为 5；规则身份变化会更新旧日程缓存，保留游戏记录和已有羁绊剧情选择。临时下载分支 `apk-preview/v3.6.2` 保留在 `fa4fc1ef84f147483c8f58f03928249d1a786722`，以保持已发送给所有者的 APK 链接有效。

[3.6.1 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.6.1) 于 2026-10-21 发布，修复使用网格日历或活动日历后，下次打开 App 立即闪退的问题。沿用上述证书，可覆盖 3.3.0～3.6.0。源码 `b82c9956693d404e98ef04d5ee78756f00ba0ddd` 的完整 Android CI [39692532769](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39692532769) 已通过；本地 504 项 UI 测试、release lint 和正式签名构建通过。四项启动回归测试使用 Android 注册滚动监听器时的即时回调，覆盖默认 Robolectric shadow 未模拟的行为。经[预构建包发布流程 39694028957](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39694028957) 发布为 latest，非草稿、非预发布。

最终 `P3R-Planner-3.6.1.apk` 为 **1,218,760 bytes**，SHA-256 为：

`fcfebfc84f7426607d3b0b442f76d35201ffd4d0342b88e7325bcd72a6b61d8b`

已下载公开 APK 和校验文件，与本地审计文件逐字节一致；标签指向上述源码，包名 `app.p3r.planner`、版本码 44、正式签名、zipalign 及内嵌规则／全书均已核对。Release 恰有 APK 和 88-byte 校验文件两个附件，发布 ID 为 `420154931`。规则包仍为 `3.6.0`，策略版本仍为 5；不强制重排已有日程，保留游戏进度、记录和日历选择。临时下载分支 `apk-preview/v3.6.1` 保留在 `593873958660f11a5350071bf43b6d5d2c0ec2d4`，以保持已发送给所有者的 APK 链接有效。

[3.6.0 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.6.0) 于 2026-10-20 发布，沿用上述证书，可覆盖 3.3.0～3.5.1。源码 `73cab56c4d20f6c8d2a92e2f9b2c8c8db3a8f531` 的完整 Android CI [39501279106](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39501279106) 已通过；本地 1,506 项 Android 测试零失败、1 项跳过，最终活动与界面补测 24 项通过，release lint 和正式签名构建通过。经[预构建包发布流程 39508093429](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39508093429) 发布为 latest，非草稿、非预发布。

最终 `P3R-Planner-3.6.0.apk` 为 **1,218,804 bytes**，SHA-256 为：

`3cd3c4c6cb7b5c93b23c869f6687318526b80a53bbe2f65b77f64e9bc3824eb0`

已下载公开 APK 和校验文件，与本地验证文件逐字节一致；包名 `app.p3r.planner`、版本码 43、正式签名及内嵌规则／全书均已核对。Release 恰有 APK 和 88-byte 校验文件两个附件，发布 ID 为 `418942942`。规则包为 `3.6.0`，策略版本为 5，旧策略缓存会重新计算。

本次标签触发的源码签名流程 [39507788079](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39507788079) 因四项 `ANDROID_*` secrets 仍未配置而停止。使用本地现有私钥签名的包发布，没有更换密钥。推送 main 或只创建标签不代表 Release 已完成；需确认公开 Release 及 APK、校验文件均已上传。

[3.5.1 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.5.1) 于 2026-10-19 发布，沿用上述证书，可覆盖 3.3.0～3.5.0。源码 `4acf72109d4e8efb4b45910b49c397e8b2ef9b2b` 的完整 Android CI [39319649477](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39319649477) 已通过：1,294 项测试，1 项跳过，零失败。release lint 和正式签名构建通过，经[预构建包发布流程 39321609609](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39321609609) 发布为 latest，非草稿、非预发布。

最终 `P3R-Planner-3.5.1.apk` 为 **1,103,032 bytes**，SHA-256 为：

`a113e20137d41130796f361f208cc9329a770697e4d5feddab31eb36234fa1ff`

已下载公开 APK 和校验文件回读，与本地文件逐字节一致；核对 tag、包名、版本码 42、非调试属性、签名、zipalign，以及内嵌规则和图鉴数据与源码一致。Release 恰有 APK 和 88-byte 校验文件两个附件，发布 ID 为 `417731104`。本次仅更新界面和图鉴出处，规则包仍为 `3.5.0`、策略版本仍为 4，不强制重排已有日程。

[3.5.0 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.5.0) 于 2026-10-19 发布，沿用上述证书，可覆盖 3.3.0～3.4.2。源码 `9848ac51251dbb1dfc51e67ce7e10a664c607091` 的完整 Android CI [39247004731](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39247004731) 已通过：1,280 项测试，1 项跳过，零失败。正式签名构建和 release lint 通过，经[预构建包发布流程 39248976870](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39248976870) 发布为 latest，非草稿、非预发布。

最终 `P3R-Planner-3.5.0.apk` 为 **1,090,748 bytes**，SHA-256 为：

`a79ce11f00a567c8f21b78cc7d09a305974f06ad8dadb6d9e63e7a2c569d1836`

已下载公开 APK 和校验文件回读，两者均与本地发布文件逐字节一致；核对 tag 指向上述源码、包名、版本码 41、非调试属性、签名、zipalign，以及内嵌规则和图鉴数据与提交一致。Release 恰有 APK 和 88-byte 校验文件两个附件，发布 ID 为 `417242786`。

[3.4.2 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.4.2) 于 2026-10-19 发布。本地 1,199 项单元测试、规则测试、release lint 和正式签名构建均通过，沿用上述证书，可覆盖 3.3.0～3.4.1。

最终 `P3R-Planner-3.4.2.apk` 为 **991,076 bytes**，SHA-256 为：

`7b03ecf4a34ab89b309165aaefb7b24c77a71a5afe0eae8cdac57f2d9afec8a9`

上一版 [3.4.1 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.4.1) 于 2026-10-18 发布，当时确认为 latest，非草稿、非预发布。源码 `51e0ae1ff0ed4d6e85f97281cd042f508330ab60` 的完整 Android CI [39157582456](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39157582456) 已通过；本地 release lint 和正式签名构建通过。通过[预构建包发布流程](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39159213883)发布，沿用上述证书，可覆盖 3.3.0／3.4.0。

最终 `P3R-Planner-3.4.1.apk` 为 **989,324 bytes**，SHA-256 为：

`446d240187cb889256f4db3e18f0efa55a408dc6a404988a9f9c62685e22c524`

已核对 tag 指向上述源码、包名、版本码 39、非调试属性、签名、zipalign、内嵌规则与提交逐字节一致，以及公开附件的大小和 SHA-256。Release 恰有正式 APK 和 88-byte 校验文件两个附件，发布 ID 为 `416657125`。

上一版 [3.4.0 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.4.0) 于 2026-10-18 发布，当时确认为 latest，非草稿、非预发布。源码 `b8d5f52110fa5b289e2714e1a71a3e6b8394ec5d` 的完整 Android CI [39118246698](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39118246698) 已通过；本地 1,160 项测试、release lint 和正式签名构建均通过。通过[预构建包发布流程](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39119147338)发布，沿用上述证书，可覆盖 3.3.0。

最终 `P3R-Planner-3.4.0.apk` 为 **982,956 bytes**，SHA-256 为：

`aa0342eb209fb2d2720f62b60f291f2e1f62be9350ff5c8b29608f6677deec26`

已核对包名、版本码 38、非调试属性、签名、zipalign、内嵌规则与提交逐字节一致，以及公开附件的大小和 SHA-256。Release 恰有正式 APK 和 88-byte 校验文件两个附件。

更早版本 [3.3.0 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.3.0) 于 2026-10-17 发布。源码提交 `f2be4042515540def723ac5f419c19c817faa284` 的完整 Android CI [39019148832](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39019148832) 已通过。

最终 `P3R-Planner-3.3.0.apk` 为 **931,500 bytes**，SHA-256 为：

`4c35c908d5f17598a023eed0dabf9f855b00e2079c9d83c4488f30a99389bb03`

已核对包名、版本码 37、非调试属性、新证书、zipalign 及内嵌规则。Release 恰有两个附件：正式 APK 和 88-byte 校验文件，不含私有签名材料。

## 3.2.0 的签名变更

3.1.2 及此前正式 3.x 安装包使用的签名证书 SHA-256 为：

`90ebc64fb86da3f2c5501fd3315a869db53bae48d5f4fde9d1d83282ab8a79a0`

历史审计记录旧私钥位于原 Mac 项目的 `.signing/p3r-release.jks`。该 Mac 已丢失，旧私钥无法找回；项目所有者已同意更换正式签名并发布 3.2.0，接受无法覆盖旧安装的限制。

3.2.0 当时的新正式签名使用 RSA 4096 位密钥，证书 SHA-256 为：

`7123294095e29e1458eb01a3f3331a025e7dd1a5d7877b25c56fe0d8291beb2f`

当时要求后续版本沿用这份私钥以便覆盖更新。已验证的 `P3R-Planner-3.2.0.apk` 为 905,984 bytes，文件 SHA-256 为：

`9512cb85c6b78c30c8fb25067952172a27aebeed1242b632c92a7ebd6a9431d9`

后来找不到 3.2.0 私钥时，所有者曾要求永久保留该证书并暂停正式发布；在确认没有备份且恢复无果后，又明确授权 3.3.0 换新密钥。该历史要求已被上述新授权取代，不应再作为阻止当前发布的指令。3.2.0 的 tag、安装包及历史校验和保持原样。

## 私钥和备份

正式签名材料保存在被 Git 忽略的 `.signing/` 中。私钥、密钥密码及 `keystore.properties` 不进入 Git，也不作为公开 GitHub Release 附件；APK 和证书指纹可以公开。

当前本地密钥为 `.signing/p3r-release-2026-10-17.p12`，配套配置为 `.signing/keystore.properties`。私有备份文件为：

- `dist/P3R-signing-backup-2026-10-17.zip`：密钥和配套配置。
- `dist/P3R-signing-recovery-2026-10-17.json`：私有恢复材料。

以上两份附件已上传到所有者的私有[签名恢复页面](https://chatgpt.com/space/page_bd2a4b411600819194ee6d5425c8e2b3)（「P3R 正式签名私有备份」），插入页面引用并回读验证。页面已确认由所有者持有，没有直接共享、继承共享或 Space/workspace 共享。ZIP 的 SHA-256 为 `0621d8c40dd6d59296cdb968ae80054ee4396cb6559b7816aeefd99b88df998c`；恢复 JSON 的 SHA-256 为 `ce7d637c0c96eab9d6812fe9d591c6b4624e0b2ca4f56b7d8f4cb4898cbbdc8b`，回读内容与本地一致，恢复已验证。

恢复时，以有权访问该页面的账号读取私有恢复 JSON，在 `functions` 隔离执行环境内恢复密钥和 `.signing/` 配置，全程不要打印密钥、密码或 JSON 正文，再核对证书必须等于本文的 **3.3.0** 指纹。已有这份持久备份即可恢复，不依赖临时工作区或用户先手动下载；所有者也可另存私有副本。

3.2.0 发布时生成的本地备份为 `dist/P3R-signing-backup-2026-10-16.zip`，包含 `.signing/p3r-release-2026.p12` 和 `keystore.properties`。原始提交 `ccbe7e8` 只记载了本地 ZIP，没有已核实的持久附件、第二份副本或用户下载成功记录。2026-10-17 的新环境缺少该 ZIP 和私钥，正式工作流也确认四项 `ANDROID_*` secrets 未配置；所有者确认没有备份，目前没有已知可恢复来源。新的持久备份不会补回旧的 3.2.0 密钥，也不能让新包覆盖旧签名。

所有备份均含私密签名材料，不可公开上传。公开 Release 只附可安装 APK、校验和及面向用户的说明；私钥及密码不写入本文。

## GitHub Actions 后续发布

3.3.0 发布时，四项签名 secrets 未配置，源码签名工作流 [39019718556](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39019718556) 因此停止。正式包在本地使用已备份的新密钥构建并校验，再通过预构建包发布流程上传；发布流程 [39019952354](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39019952354) 已成功。3.4.1 仍采用同一路径，[发布流程 39159213883](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39159213883) 已成功。该路径没有使用调试签名，也没有放宽证书校验。

仓库的 `Signed Android release` 工作流要求配置四项 Actions secrets：

- `ANDROID_KEYSTORE_BASE64`：当前私有备份中 `p3r-release-2026-10-17.p12` 的 base64 编码。
- `ANDROID_KEYSTORE_PASSWORD`：私有 `keystore.properties` 中的 `storePassword`。
- `ANDROID_KEY_ALIAS`：`p3r-release`。
- `ANDROID_KEY_PASSWORD`：私有 `keystore.properties` 中的 `keyPassword`。

这些值只放在仓库的 Actions secrets 中，不写进源码、发布说明或日志。工作流在配置缺失或证书与当前 **3.3.0** 指纹不符时会停止。配置完成后，推送对应版本 tag 或手动选择已有 tag 即可构建并发布；普通 main 分支 CI 仍只做测试和 debug 构建。
