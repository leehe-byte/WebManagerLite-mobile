package com.opengw.manager

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var loadingLayout: LinearLayout
    private lateinit var infoText: TextView
    private lateinit var btnRefresh: FloatingActionButton
    private lateinit var btnSwitch: FloatingActionButton
    private lateinit var btnSettings: FloatingActionButton

    private var currentPort = 8000
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()

    private val CHANNEL_ID = "BatteryStatus"
    private var isBatteryFullNotified = false
    private var isBatteryLowNotified = false
    private var lastBackTime: Long = 0
    private val tunnelJobs = mutableListOf<Job>()
    private var notificationId = 0
    private var isAutoLoginDone = false
    private var openGwToken: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingLayout = findViewById(R.id.loadingLayout)
        infoText = findViewById(R.id.infoText)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnSettings = findViewById(R.id.btnSettings)

        btnRefresh.setOnClickListener { reloadCurrentWeb() }
        btnSwitch.setOnClickListener { switchWebVersion() }
        btnSettings.setOnClickListener { showSettingsDialog() }

        createNotificationChannel()

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        if (prefs.getString("gateway_ip", null) == null) {
            showSetupDialog()
        } else {
            currentPort = prefs.getInt("default_port", 8000)
            startAppFlow()
        }

        val permissions = mutableListOf(Manifest.permission.INTERNET, Manifest.permission.ACCESS_NETWORK_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}.launch(permissions.toTypedArray())

        if (prefs.getString("gateway_ip", null) != null) {
            startBatteryMonitor()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val ct = System.currentTimeMillis()
                // 双击任意页面直接退出，解决 SPA 历史栈过深退不出去的问题
                if (ct - lastBackTime < 2000) {
                    finishAffinity()
                    return
                }
                if (webView.canGoBack()) {
                    webView.goBack()
                    lastBackTime = ct
                } else {
                    Toast.makeText(this@MainActivity, "再按一次退出应用", Toast.LENGTH_SHORT).show()
                    lastBackTime = ct
                }
            }
        })
    }

    private fun startMultiTunnel(targetIp: String) {
        tunnelJobs.forEach { it.cancel() }
        tunnelJobs.clear()

        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val p1 = prefs.getInt("port_opengw", 8000)
        val p2 = prefs.getInt("port_official", 8080)
        // 固定端口 + 用户配置的额外映射端口（供插件自定义端口，如代理面板 9090）
        val extraPorts = prefs.getString("extra_tunnel_ports", "")
            ?.split(",", "，", " ")
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?: emptyList()
        val ports = (setOf(p1, p2, 7681, 8080, 8000, 7788) + extraPorts).toSet()

        ports.forEach { port ->
            val job = lifecycleScope.launch(Dispatchers.IO) {
                var serverSocket: ServerSocket? = null
                try {
                    serverSocket = ServerSocket()
                    serverSocket.reuseAddress = true
                    serverSocket.bind(InetSocketAddress("127.0.0.1", port))
                    Log.i("Tunnel", "Port $port mapped to $targetIp:$port")

                    while (isActive) {
                        val clientSocket = try {
                            serverSocket.accept()
                        } catch (e: Exception) {
                            break
                        }
                        launch(Dispatchers.IO) {
                            var targetSocket: Socket? = null
                            try {
                                targetSocket = Socket(targetIp, port)
                                val j1 = launch {
                                    try {
                                        clientSocket.getInputStream().copyTo(targetSocket.getOutputStream())
                                    } catch (e: Exception) {
                                        Log.d("Tunnel", "Forward $port closed: ${e.message}")
                                    }
                                }
                                val j2 = launch {
                                    try {
                                        targetSocket.getInputStream().copyTo(clientSocket.getOutputStream())
                                    } catch (e: Exception) {
                                        Log.d("Tunnel", "Reverse $port closed: ${e.message}")
                                    }
                                }
                                joinAll(j1, j2)
                            } catch (e: Exception) {
                                Log.d("Tunnel", "Connect $targetIp:$port failed: ${e.message}")
                            } finally {
                                try { clientSocket.close() } catch (e: Exception) {}
                                try { targetSocket?.close() } catch (e: Exception) {}
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Tunnel", "Bind $port failed: ${e.message}")
                } finally {
                    try { serverSocket?.close() } catch (e: Exception) {}
                }
            }
            tunnelJobs.add(job)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "设备电源状态"
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH)
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startBatteryMonitor() {
        lifecycleScope.launch {
            while (isActive) {
                checkBatteryStatus()
                delay(300000)
            }
        }
    }

    private suspend fun checkBatteryStatus() {
        val token = openGwToken ?: return
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val ip = prefs.getString("gateway_ip", null) ?: return
        val port = prefs.getInt("port_opengw", 8000)
        try {
            val request = Request.Builder().url("http://$ip:$port/api/status")
                .header("X-Auth-Token", token)
                .build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            val body = response.body?.string() ?: return
            val json = JSONObject(body)
            val level = json.optInt("battery_level", -1)
            if (level != -1) {
                if (level >= 100 && !isBatteryFullNotified) {
                    sendNotification("电量已满", "受管理设备电量已达到100%")
                    isBatteryFullNotified = true
                } else if (level < 95) isBatteryFullNotified = false
                if (level <= 20 && !isBatteryLowNotified) {
                    sendNotification("低电量警告", "受管理设备电量低于20% ($level%)")
                    isBatteryLowNotified = true
                } else if (level > 25) isBatteryLowNotified = false
            }
        } catch (e: Exception) {
            Log.d("Battery", "Check failed: ${e.message}")
        }
    }

    private fun sendNotification(title: String, content: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(notificationId++, builder.build())
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        val ipEdit = EditText(this).apply { setText(prefs.getString("gateway_ip", "192.168.9.1")) }
        val pwdEdit = EditText(this).apply {
            hint = "网关管理密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            val encPwd = prefs.getString("gateway_pwd_enc", null)
            if (encPwd != null) {
                setText(CryptoHelper.decrypt(encPwd) ?: "")
            }
        }
        val opengwPortEdit = EditText(this).apply { setText(prefs.getInt("port_opengw", 8000).toString()) }
        val officialPortEdit = EditText(this).apply { setText(prefs.getInt("port_official", 8080).toString()) }
        val extraPortsEdit = EditText(this).apply {
            setText(prefs.getString("extra_tunnel_ports", "") ?: "")
            hint = "如 9090, 9091（逗号分隔）"
        }
        val defaultCheck = CheckBox(this).apply {
            text = "默认进入 OpenGW 版本"
            isChecked = prefs.getInt("default_port", 8000) == prefs.getInt("port_opengw", 8000)
        }

        val btnTest = Button(this).apply {
            text = "发送测试通知"
            setOnClickListener { sendNotification("测试成功", "OpenGW Mobile 通知功能正常。") }
        }

        val btnExit = Button(this).apply {
            text = "退出程序"
            setTextColor(android.graphics.Color.RED)
            setOnClickListener { finishAffinity() }
        }

        layout.addView(TextView(this).apply { text = "网关 IP"; textSize = 12f })
        layout.addView(ipEdit)
        layout.addView(TextView(this).apply { text = "管理密码"; setPadding(0, 20, 0, 0); textSize = 12f })
        layout.addView(pwdEdit)
        layout.addView(TextView(this).apply { text = "OpenGW 端口"; setPadding(0, 20, 0, 0); textSize = 12f })
        layout.addView(opengwPortEdit)
        layout.addView(TextView(this).apply { text = "官方 Web 端口"; setPadding(0, 20, 0, 0); textSize = 12f })
        layout.addView(officialPortEdit)
        layout.addView(TextView(this).apply { text = "额外映射端口"; setPadding(0, 20, 0, 0); textSize = 12f })
        layout.addView(extraPortsEdit)
        layout.addView(TextView(this).apply {
            text = "新插件如需访问设备自定义端口（如代理面板 9090），在此添加，逗号分隔"
            setPadding(0, 0, 0, 0)
            textSize = 10f
            setTextColor(android.graphics.Color.GRAY)
        })
        layout.addView(defaultCheck)
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })
        layout.addView(btnTest)
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        layout.addView(btnExit)

        scrollView.addView(layout)

        AlertDialog.Builder(this).setTitle("高级设置").setView(scrollView)
            .setPositiveButton("保存并重启") { _, _ ->
                val ip = ipEdit.text.toString().trim()
                val pwd = pwdEdit.text.toString().trim()
                val p1 = opengwPortEdit.text.toString().toIntOrNull() ?: 8000
                val p2 = officialPortEdit.text.toString().toIntOrNull() ?: 8080
                val encPwd = CryptoHelper.encrypt(pwd)
                prefs.edit()
                    .putString("gateway_ip", ip)
                    .putString("gateway_pwd_enc", encPwd)
                    .remove("gateway_pwd")
                    .putInt("port_opengw", p1)
                    .putInt("port_official", p2)
                    .putString("extra_tunnel_ports", extraPortsEdit.text.toString().trim())
                    .putInt("default_port", if (defaultCheck.isChecked) p1 else p2)
                    .apply()
                recreate()
            }
            .setNegativeButton("取消", null).show()
    }

    private fun showSetupDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val ipEdit = EditText(this).apply { setText("192.168.9.1") }
        val pwdEdit = EditText(this).apply {
            hint = "网关管理密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(TextView(this).apply { text = "网关 IP" })
        layout.addView(ipEdit)
        layout.addView(TextView(this).apply { text = "管理密码"; setPadding(0, 20, 0, 0) })
        layout.addView(pwdEdit)
        AlertDialog.Builder(this).setTitle("初始化设置").setView(layout).setCancelable(false)
            .setPositiveButton("保存并登录") { _, _ ->
                val ip = ipEdit.text.toString().trim()
                val pwd = pwdEdit.text.toString().trim()
                val encPwd = CryptoHelper.encrypt(pwd)
                getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                    .putString("gateway_ip", ip)
                    .putString("gateway_pwd_enc", encPwd)
                    .putInt("port_opengw", 8000)
                    .putInt("port_official", 8080)
                    .putInt("default_port", 8000)
                    .apply()
                currentPort = 8000
                startAppFlow()
                startBatteryMonitor()
            }.show()
    }

    private fun switchWebVersion() {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val p1 = prefs.getInt("port_opengw", 8000)
        val p2 = prefs.getInt("port_official", 8080)

        currentPort = if (currentPort == p1) p2 else p1
        val targetName = if (currentPort == p1) "OpenGW Web" else "官方 Web"

        Toast.makeText(this, "正在切换到: $targetName", Toast.LENGTH_SHORT).show()
        webView.loadUrl("http://${webHost(currentPort)}:$currentPort/index.html")
    }

    /** OpenGW 走本机隧道（WebSocket/远程控制需经隧道转发，暂保留）；官方端口直连设备 IP */
    private fun webHost(port: Int): String {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val p1 = prefs.getInt("port_opengw", 8000)
        return if (port == p1) {
            "127.0.0.1"
        } else {
            prefs.getString("gateway_ip", "192.168.9.1") ?: "192.168.9.1"
        }
    }

    /** 刷新/重试：重新加载当前目标（OpenGW 直连设备 IP），比 reload 更彻底地绕过错误态 */
    private fun reloadCurrentWeb() {
        webView.loadUrl("http://${webHost(currentPort)}:$currentPort/index.html")
    }

    private fun startAppFlow() {
        initWebView()
        performAutoLogin()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = "$userAgentString OpenWrtLiteManager/1.0"
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                loadingLayout.visibility = View.VISIBLE
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                loadingLayout.visibility = View.GONE
                webView.visibility = View.VISIBLE
                btnRefresh.visibility = View.VISIBLE
                btnSwitch.visibility = View.VISIBLE
                btnSettings.visibility = View.VISIBLE
                if (isAutoLoginDone) {
                    val token = openGwToken
                    val js = if (token != null) {
                        "sessionStorage.setItem('isLoggedIn', 'true'); sessionStorage.setItem('authToken', '$token');"
                    } else {
                        "sessionStorage.setItem('isLoggedIn', 'true');"
                    }
                    webView.evaluateJavascript(js, null)
                }
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                // 仅主页面加载失败才进入错误页；iframe/子资源错误一律忽略，避免整页误判崩溃
                if (request?.isForMainFrame() != true) return
                showLoadError()
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                // iframe/子资源 HTTP 错误（404/5xx）忽略；仅主页面 HTTP 错误进入错误页
                if (request?.isForMainFrame() != true) return
                showLoadError()
            }

            private fun showLoadError() {
                loadingLayout.visibility = View.VISIBLE
                webView.visibility = View.GONE
                infoText.text = "页面加载失败，点击重试"
                loadingLayout.setOnClickListener { reloadCurrentWeb() }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage?.let {
                    Log.d("WebConsole", "[${it.messageLevel()}] ${it.message()}")
                }
                return true
            }
        }

        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun logout() {
                getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().apply()
                runOnUiThread { recreate() }
            }
            @JavascriptInterface
            fun exitApp() { finishAffinity() }
            @JavascriptInterface
            fun getToken(): String = openGwToken ?: ""
        }, "AndroidBridge")
    }

    private fun performAutoLogin() {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val ip = prefs.getString("gateway_ip", "192.168.9.1") ?: return
        val encPwd = prefs.getString("gateway_pwd_enc", null)
        val legacyPwd = prefs.getString("gateway_pwd", null)
        val pwd = when {
            encPwd != null -> CryptoHelper.decrypt(encPwd) ?: ""
            legacyPwd != null -> {
                // 迁移旧版明文密码
                prefs.edit().putString("gateway_pwd_enc", CryptoHelper.encrypt(legacyPwd)).remove("gateway_pwd").apply()
                legacyPwd
            }
            else -> ""
        }
        val p1 = prefs.getInt("port_opengw", 8000)
        val p2 = prefs.getInt("port_official", 8080)

        lifecycleScope.launch {
            infoText.text = "正在同步网关登录状态..."
            startMultiTunnel(ip)

            val cookie = withContext(Dispatchers.IO) { doGatewayLogin(ip, pwd) }
            val token = withContext(Dispatchers.IO) { doOpenGWLogin(pwd) }
            if (cookie != null) {
                openGwToken = token
                isAutoLoginDone = true
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    // OpenGW 走本机隧道，cookie 绑 127.0.0.1（OpenGW 自身用 token，原厂 cookie 供隧道内资源）
                    setCookie("http://127.0.0.1:8000", cookie)
                    setCookie("http://127.0.0.1:7681", cookie)
                    if (p1 != 8000) setCookie("http://127.0.0.1:$p1", cookie)
                    // 官方 Web 直连设备 IP，cookie 需绑定到设备 IP 才生效
                    setCookie("http://$ip:8080", cookie)
                    if (p2 != 8080) setCookie("http://$ip:$p2", cookie)
                    flush()
                }
                webView.loadUrl("http://${webHost(currentPort)}:$currentPort/index.html")
            } else {
                infoText.text = "自动登录失败，点击重试"
                loadingLayout.setOnClickListener { performAutoLogin() }
            }
        }
    }

    private fun doGatewayLogin(ip: String, pwd: String): String? {
        try {
            val ts = System.currentTimeMillis()
            val ldReq = Request.Builder().url("http://$ip:8080/goform/goform_get_cmd_process?isTest=false&cmd=LD&_=$ts").build()
            val ldRes = client.newCall(ldReq).execute()
            val ld = JSONObject(ldRes.body?.string() ?: "").optString("LD")
            val hash = sha256(sha256(pwd) + ld)
            val body = "isTest=false&goformId=LOGIN&user=admin&password=$hash"
            val loginReq = Request.Builder().url("http://$ip:8080/goform/goform_set_cmd_process").post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType())).build()
            val res = client.newCall(loginReq).execute()
            return res.header("Set-Cookie")?.split(";")?.get(0)
        } catch (e: Exception) {
            Log.e("Login", "Gateway login failed", e)
            return null
        }
    }

    /** OpenGW Web 登录：用官方密码换取会话 token（直连设备 IP，不走隧道） */
    private fun doOpenGWLogin(pwd: String): String? {
        return try {
            val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
            val ip = prefs.getString("gateway_ip", "192.168.9.1") ?: "192.168.9.1"
            val p1 = prefs.getInt("port_opengw", 8000)
            val json = JSONObject().put("password", pwd).toString()
            val body = "postData=" + URLEncoder.encode(json, "UTF-8")
            val request = Request.Builder()
                .url("http://$ip:$p1/api/auth/login")
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            val res = client.newCall(request).execute()
            val data = JSONObject(res.body?.string() ?: "")
            if (data.optInt("result") == 0) data.optString("token").ifEmpty { null } else null
        } catch (e: Exception) {
            Log.e("Login", "OpenGW login failed", e)
            null
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.uppercase()
    }

    override fun onDestroy() {
        super.onDestroy()
        tunnelJobs.forEach { it.cancel() }
    }
}
