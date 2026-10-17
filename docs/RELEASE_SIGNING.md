# 发布签名记录

## 3.2.0 的签名变更

3.1.2 及此前正式 3.x 安装包使用的签名证书 SHA-256 为：

`90ebc64fb86da3f2c5501fd3315a869db53bae48d5f4fde9d1d83282ab8a79a0`

历史审计记录旧私钥位于原 Mac 项目的 `.signing/p3r-release.jks`。该 Mac 已丢失，旧私钥无法找回；项目所有者已同意更换正式签名并发布 3.2.0，接受无法覆盖旧安装的限制。

本次新正式签名使用 RSA 4096 位密钥，证书 SHA-256 为：

`7123294095e29e1458eb01a3f3331a025e7dd1a5d7877b25c56fe0d8291beb2f`

后续版本应继续使用本次的新私钥，以便覆盖更新这一版。已验证的 `P3R-Planner-3.2.0.apk` 为 905,984 bytes，文件 SHA-256 为：

`9512cb85c6b78c30c8fb25067952172a27aebeed1242b632c92a7ebd6a9431d9`

项目所有者于 2026-10-17 明确要求：**所有后续版本永久沿用 3.2.0 的正式签名，不再更换正式密钥。** 不需要再寻找丢失 Mac 上的 3.1.2 旧密钥。当前环境缺少 3.2.0 私钥时，应恢复下述私有备份或配置相同密钥的 Actions secrets；恢复前暂停正式发布，不能生成替代密钥、使用调试签名或降低证书校验来发布。

正式发布入口为 `scripts/build_release.sh` 或 Signed Android release 工作流。两者都校验最终 APK 必须仅由上述 3.2.0 证书签名，校验通过后才复制正式安装包或上传 Release；直接运行 Gradle 得到的 release 构建不等于正式发布包。

包名保持 `app.p3r.planner`。安装过旧签名 3.x 的用户须先在旧版「我的 → 备份与恢复」导出并确认保存，再卸载旧版、安装新版并导入。详细步骤见[3.2.0 安装说明](RELEASE_NOTES_3.2.0.md#安装前签名变更先备份)。

## 私钥和备份

正式签名材料保存在被 Git 忽略的 `.signing/` 中。私钥、密钥密码及 `keystore.properties` 不进入 Git，也不作为公开 GitHub Release 附件；APK 和证书指纹可以公开。

本次仅供项目所有者私下下载的签名备份包为 `dist/P3R-signing-backup-2026-10-16.zip`，文件权限为 `0600`，包含 `.signing/p3r-release-2026.p12` 和 `keystore.properties`。**该包包含私密签名材料，不可公开上传。** 下载后应将它和恢复所需密码保存在自己的安全位置，并另留一份私有备份，避免仅保存在当前构建环境中。

公开 Release 只附可安装 APK、校验和及面向用户的说明。本文记录的证书指纹及 APK 校验和已经过本次正式构建验证，私钥内容不写入本文。


## GitHub Actions 后续发布

仓库的 `Signed Android release` 工作流要求配置四项 Actions secrets：

- `ANDROID_KEYSTORE_BASE64`：私有备份中的 `p3r-release-2026.p12` 的 base64 编码。
- `ANDROID_KEYSTORE_PASSWORD`：私有 `keystore.properties` 中的 `storePassword`。
- `ANDROID_KEY_ALIAS`：`p3r-release`。
- `ANDROID_KEY_PASSWORD`：私有 `keystore.properties` 中的 `keyPassword`。

这些值只放在仓库的 Actions secrets 中，不写进源码、发布说明或日志。工作流在配置缺失或证书与本记录不符时会停止。配置完成后，推送对应版本 tag 或手动选择已有 tag 即可构建并发布；普通 main 分支 CI 仍只做测试和 debug 构建。
