# 签名密钥放这里

本目录下所有 `*.jks` / `*.keystore` 都已在 `.gitignore` 里，**放什么都不会被提交**。

仓库里**不含任何签名密钥**——签名密钥不该进版本库。所以：

- 随便编译一下：不需要任何配置，`./gradlew assembleRelease` 会自动回退到 debug 签名；
- 想用自己的正式签名构建：

**1. 生成一个 keystore**

```bash
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -alias purecam \
  -keyalg RSA -keysize 2048 -validity 10000
```

**2. 在 `local.properties` 里补上口令**

`local.properties` 同样已被 gitignore，不会提交。

```properties
purecam.storePassword=你的口令
purecam.keyAlias=purecam
purecam.keyPassword=你的口令
```

之后 `./gradlew assembleRelease` 就会用你的签名打包。

> 切换不同来源（不同签名）的 APK 之前，需要先 `adb uninstall com.purecam.app`，
> 否则会报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
