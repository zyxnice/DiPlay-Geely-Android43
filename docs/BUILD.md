# 吉利星越 2019 款 8600 开发与构建

本仓库独立维护星越 2019 款 8600 的 Android 4.3 / API18 CarPlay 适配。`mobile` 中的 `geelyUsb` 为车测变体，包名 `com.shihab.diplay.repair1.geely8600`；保留的 automotive、samples 及其他车型实现不构成本项目支持承诺。以下构建成功均不等同于实车验收。

## 8600 车测变体

使用仓库内 Gradle wrapper；以下命令在 Windows PowerShell 仓库根目录执行：

```powershell
.\gradlew.bat :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleGeelyUsb
```

普通构建未指定本地认证资产时，产物不具备独立连接手机所需的身份。需要车测时，先在本地将 `DIPLAY_AUTH_ASSETS_DIR` 指向已授权的认证资产目录，再构建；不得将认证文件提交到仓库。基础变体版本后缀为 `-H53-USB2`，可通过 `-PgeelyPerformanceStage=B` 选择 USB3-B 变体；A/B/C 是候选实现标识，不代表验收等级，未指定时为 `off`。

产物目录为 `mobile/build/outputs/apk/geelyUsb/`。交付前核对 APK 中的包名、版本、签名、API18/ARMv7 兼容与所选本地认证输入。覆盖安装要求包名和签名一致；发布包及校验值以[本仓库 Releases](https://github.com/zyxnice/Geely-8600-Android43-CarPlay/releases)说明为准。

## 继承的通用构建与打包说明

以下保留源码构建、认证资产与签名边界；通用 debug/release 不替代上面的 8600 车测变体。

Requirements: JDK 25, Android SDK 37, NDK 28.2.13676358 and the included Gradle wrapper.

## Source and CI builds

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleDebug
```

The resulting source-only APK contains no accessory identity. Standalone CarPlay requires runtime authentication provisioning. Tests generate synthetic identities at runtime; no test private-key files are tracked.

## Local release packaging

Provide an external asset directory using `DIPLAY_AUTH_ASSETS_DIR`. The directory must contain exactly the intended runtime files under `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Neither file belongs in Git. The build permits those two files only when this explicit input is set and rejects unexpected credential containers elsewhere in APK assets.

Set `ANDROID_KEYSTORE_PATH`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` locally for your Android signing key. Never commit these values or the keystore. Different signing keys cannot update an existing project-signed installation.

```sh
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintRelease :mobile:assembleRelease
```

Output: `mobile/build/outputs/apk/release/mobile-release.apk`. The release APK deliberately contains the experimental identity described in the notices; it is extractable by recipients. The separate Android signing key is not included. The retired build-beta.py helper is not used; this Gradle workflow uses explicit environment inputs.

The public release source archive corresponds to the tagged source and excludes runtime identities, signing keys, local configuration and build output.

## Standalone car-test APK

Use `:mobile:assembleStandaloneDebug` for a test APK that must connect to an iPhone:

```sh
DIPLAY_AUTH_ASSETS_DIR=/absolute/path/to/runtime-assets ./gradlew :mobile:assembleStandaloneDebug
```

This task refuses missing or empty runtime inputs. `assembleDebug` remains an identity-free
source/CI build when the explicit asset input is absent; do not install that output as a
standalone car-test package. Before delivery, verify both `assets/offline-mfi/identity.pk8`
and `assets/offline-mfi/certificate.p7b` in the APK against the selected local inputs.
Update the existing test app without uninstalling it to preserve its settings.
