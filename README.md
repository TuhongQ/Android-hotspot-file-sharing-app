# 闪桥共享

闪桥共享是一个安卓端局域网文件共享 App。安卓手机作为热点和文件中枢，iPhone/iPad/Mac 连接后可通过浏览器上传、下载、浏览文件，也支持安卓端主动推送文件给在线网页客户端。

## 核心功能

- 安卓端添加一个或多个共享文件夹。
- 安卓端启动前台文件服务，息屏和切后台后继续运行。
- iPhone Safari 打开安卓端地址后浏览共享目录。
- 网页端支持上传、下载、图片缩略图预览、大图预览。
- 网页端支持左滑删除文件或文件夹。
- 安卓端可选择本机文件，发送给当前在线网页客户端。
- 主动发送支持网页端接收进度和安卓端发送进度。
- App 使用自适应启动图标和深色科技风界面。

## Windows 版本

仓库新增 `windows/` 桌面端工程。Windows 电脑打开软件后可选择共享文件夹、启动局域网文件服务，并显示二维码；手机在同一个局域网内扫码即可浏览和传输文件。

```bash
cd windows
npm install
npm start
```

## 重要限制

普通第三方 Android App 在新版 Android 上不能静默开启或配置热点。当前实现提供“打开热点设置”入口，用户手动开启热点后再启动文件服务。

当前网页文件服务是 HTTP 浏览器方案，不是 iOS “文件”App 的 SMB 挂载。如果需要在 iOS 文件 App 的“连接服务器”里直接挂载安卓目录，后续需要新增 SMB/WebDAV 服务。

## 工程结构

```text
android/
  app/src/main/java/cn/local/bridgeshare/MainActivity.java
  app/src/main/java/cn/local/bridgeshare/FileShareService.java
  app/src/main/java/cn/local/bridgeshare/FileShareServer.java
```

## 构建

Debug 构建：

```bash
cd android
gradle assembleDebug
```

Release 构建需要本地签名文件，不要提交到 Git。创建：

```text
android/app/keystore.properties
android/app/bridge-share-release.jks
```

`keystore.properties` 示例：

```properties
storeFile=bridge-share-release.jks
storePassword=your-store-password
keyAlias=bridge-share
keyPassword=your-key-password
```

然后执行：

```bash
cd android
gradle assembleRelease
```

## 使用

1. 安装 APK 到安卓手机。
2. 点击“添加文件夹”，添加一个或多个共享目录。
3. 点击“热点设置”，手动开启安卓热点。
4. 回到 App，点击“启动服务”。
5. iPhone 连接安卓热点。
6. iPhone Safari 打开 App 显示的地址或扫描二维码。

## 当前边界

- 大文件上传目前仍使用内存型 multipart 解析，正式产品应改为流式解析。
- 传输未做访问密码或端到端加密，建议只在自己的热点/可信局域网内使用。
- 在线客户端基于浏览器 EventSource，网页关闭或系统休眠后会断开。
