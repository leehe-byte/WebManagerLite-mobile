# OpenGW Mobile

OpenGW Mobile 是一款专为随身 WiFi 和定制网关设计的 Android 管理客户端。它采用极简的“移动浏览器壳”架构，旨在为用户提供最快、最无感的网关管理体验。

## ✨ 核心特性

- **🚀 极速自动登录**：应用启动后自动与网关进行握手认证，并将登录凭证（Cookie）同步注入到 WebView 中，彻底告别手动输入密码。
- **🔄 双版本一键切换**：内置悬浮按钮，支持在 **OpenGW 定制界面** 与 **官方管理界面** 之间无缝切换。
- **⚙️ 高级自定义设置**：支持手动修改网关 IP、自定义 OpenGW/官方 Web 端口，并可设置默认进入的版本。
- **🔋 电源监控通知**：自动定时检测受管理设备的电量，并在**低电量（<=20%）**或**满电（100%）**时发送系统通知提醒。
- **📦 极致精简**：移除了沉重的后台服务及 Root 管理逻辑，仅保留核心浏览与同步功能，APK 极其小巧。
- **📱 专用 User-Agent**：提供标识符 `OpenWrtLiteManager/1.0`，方便远程 Web 端识别。
- **🔃 快捷刷新**：内置物理悬浮刷新按钮，随时获取网关最新状态。

## 🚀 快速开始

### 编译环境
- Android Studio Hedgehog 或更高版本
- JDK 11+
- Gradle 8.0+

### 构建步骤
1. 使用 Android Studio 打开项目。
2. 运行或执行 `./gradlew assembleRelease` 生成 APK。
3. 首次启动请在弹窗中设置网关 IP 和管理密码。

## 🌐 Web 端适配建议

为了让您的远程网页能够完美识别并跳过 App 内部的登录，建议在您的 `index.html` 入口处添加以下逻辑：

```javascript
// 检测是否在 OpenGW Mobile App 中运行
const isApp = navigator.userAgent.indexOf('OpenWrtLiteManager') > -1 || !!window.AndroidBridge;

if (isApp) {
    // 如果是 App 访问，自动设置登录状态，跳过 login.html
    sessionStorage.setItem('isLoggedIn', 'true');
} else if (sessionStorage.getItem('isLoggedIn') !== 'true') {
    // 只有普通浏览器访问且未登录时，才跳转到登录页
    window.location.href = 'login.html';
}
```

## 🛠️ 技术架构

- **语言**：Kotlin
- **版本**：v1.1 (Build 2)
- **核心库**：WebView, Coroutines, OkHttp3
- **权限占用**：联网权限及通知权限（Android 13+）

## 📄 许可证

本项目基于 MIT 许可证开源。
