# 发布签名记录

## 当前正式签名：3.3.0 起

2026-10-17，项目所有者确认没有保存 3.2.0 的签名备份，在恢复尝试未找到私钥后，明确授权「如果你找不回来的话就直接用新的得了」。这条新授权取代此前「永久沿用 3.2.0，否则暂停发布」的要求。

3.3.0 使用新生成的 RSA 4096 位正式密钥，证书 SHA-256 为：

`83d4395bcac9e7b33b20f3bd2b1327c856e56a6f0bd4816c52a42550a2b653ba`

包名仍为 `app.p3r.planner`。**3.3.0 不能覆盖安装 3.2.0 或更早正式版**；先在旧版「我的 → 备份与恢复 → 分享备份」将数据保存到应用之外，确认备份能找到，再卸载旧版、安装新版并恢复。需要保留旧逐日攻略打勾进度的，也须先单独导出。

后续版本应沿用这份 3.3.0 密钥。正式发布入口为 `scripts/build_release.sh` 或 Signed Android release 工作流，校验最终 APK 只能由当前证书签名；直接运行 Gradle 得到的 release 构建不等于已通过正式发布校验。CI 的 debug 包不能作为正式更新包。

私有持久备份已上传并回读校验，恢复材料也已验证。

[3.4.1 正式版](https://github.com/nobrainmonkey/P3R_widget/releases/tag/v3.4.1) 于 2026-10-18 发布，确认为 latest，非草稿、非预发布。源码 `51e0ae1ff0ed4d6e85f97281cd042f508330ab60` 的完整 Android CI [39157582456](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39157582456) 已通过；本地 release lint 和正式签名构建通过。通过[预构建包发布流程](https://github.com/nobrainmonkey/P3R_widget/actions/runs/39159213883)发布，沿用上述证书，可覆盖 3.3.0／3.4.0。

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
