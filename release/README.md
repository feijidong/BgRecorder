# 预编译安装包

| 文件 | 说明 |
|---|---|
| `BgRecorder-1.0-uw-1080p30-debug.apk` | v1.0，**debug 签名**，2.2 MB，minSdk 29 / targetSdk 34 |

```
SHA-256  26cbeeacc0c3d7d7e2e3191990205390867ac39aec67497b937eb172cddd0fd7
```

安装：

```bash
adb install -r BgRecorder-1.0-uw-1080p30-debug.apk
```

或把 APK 传到手机上点开安装（需允许「安装未知来源应用」）。

## 注意

- 这是 **Android Debug 签名**的包：可以正常侧载自用，但**不能上架应用商店**，也无法与正式签名的包互相覆盖升级（签名不同）。
- 自己出正式包：

  ```bash
  keytool -genkey -v -keystore my.jks -keyalg RSA -keysize 2048 -validity 10000 -alias bgrec
  ./gradlew assembleRelease        # 需在 app/build.gradle.kts 里配置 signingConfig
  ```

- 更强的分发方式是 GitHub Releases：把 APK 挂到 Release 附件里，仓库本身只留源码，避免每次构建都让仓库变大。
