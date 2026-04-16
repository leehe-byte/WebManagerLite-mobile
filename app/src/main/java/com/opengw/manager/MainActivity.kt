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
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
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
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).build()
    
    private val CHANNEL_ID = "BatteryStatus"
    private var isBatteryFullNotified = false
    private var isBatteryLowNotified = false
    private var lastBackTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        loadingLayout = findViewById(R.id.loadingLayout)
        infoText = findViewById(R.id.infoText)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnSettings = findViewById(R.id.btnSettings)
        
        btnRefresh.setOnClickListener { webView.reload() }
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
        
        startBatteryMonitor()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "设备电源状态"
            val descriptionText = "用于提醒受管理设备的电量充满或过低"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun startBatteryMonitor() {
        scope.launch {
            while (isActive) {
                checkBatteryStatus()
                delay(300000) // 5 分钟检查一次
            }
        }
    }

    private suspend fun checkBatteryStatus() {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val ip = prefs.getString("gateway_ip", null) ?: return
        val port = prefs.getInt("port_opengw", 8000)
        
        try {
            val request = Request.Builder().url("http://$ip:$port/api/status").build()
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            val body = response.body?.string() ?: return
            val json = JSONObject(body)
            val level = json.optInt("battery_level", -1)
            
            if (level != -1) {
                if (level >= 100 && !isBatteryFullNotified) {
                    sendNotification("电量已满", "受管理设备电量已达到100%")
                    isBatteryFullNotified = true
                } else if (level < 95) {
                    isBatteryFullNotified = false
                }

                if (level <= 20 && !isBatteryLowNotified) {
                    sendNotification("低电量警告", "受管理设备电量低于20% ($level%)")
                    isBatteryLowNotified = true
                } else if (level > 25) {
                    isBatteryLowNotified = false
                }
            }
        } catch (e: Exception) {}
    }

    private fun sendNotification(title: String, content: String) {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 40)
        }
        
        val ipEdit = EditText(this).apply { setText(prefs.getString("gateway_ip", "192.168.0.1")) }
        val pwdEdit = EditText(this).apply {
            setText(prefs.getString("gateway_pwd", ""))
            hint = "网关管理密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val opengwPortEdit = EditText(this).apply { setText(prefs.getInt("port_opengw", 8000).toString()) }
        val officialPortEdit = EditText(this).apply { setText(prefs.getInt("port_official", 8080).toString()) }
        val defaultCheck = CheckBox(this).apply {
            text = "默认进入 OpenGW 版本"
            isChecked = prefs.getInt("default_port", 8000) == prefs.getInt("port_opengw", 8000)
        }
        
        val btnTest = Button(this).apply {
            text = "发送测试通知"
            setOnClickListener { sendNotification("测试成功", "OpenGW Mobile 电源监控通知功能正常。") }
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
        layout.addView(defaultCheck)
        
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 40) })
        layout.addView(btnTest)
        layout.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 20) })
        layout.addView(btnExit)
        
        scrollView.addView(layout)

        AlertDialog.Builder(this)
            .setTitle("高级设置")
            .setView(scrollView)
            .setPositiveButton("保存并重启") { _, _ ->
                val ip = ipEdit.text.toString().trim()
                val pwd = pwdEdit.text.toString().trim()
                val p1 = opengwPortEdit.text.toString().toIntOrNull() ?: 8000
                val p2 = officialPortEdit.text.toString().toIntOrNull() ?: 8080
                prefs.edit()
                    .putString("gateway_ip", ip)
                    .putString("gateway_pwd", pwd)
                    .putInt("port_opengw", p1)
                    .putInt("port_official", p2)
                    .putInt("default_port", if (defaultCheck.isChecked) p1 else p2)
                    .apply()
                recreate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSetupDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 40, 60, 10)
        }
        val ipEdit = EditText(this).apply { setText("192.168.0.1") }
        val pwdEdit = EditText(this).apply { 
            hint = "网关管理密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(TextView(this).apply { text = "网关 IP" })
        layout.addView(ipEdit)
        layout.addView(TextView(this).apply { text = "管理密码"; setPadding(0, 20, 0, 0) })
        layout.addView(pwdEdit)

        AlertDialog.Builder(this)
            .setTitle("初始化设置")
            .setView(layout)
            .setCancelable(false)
            .setPositiveButton("保存并登录") { _, _ ->
                val ip = ipEdit.text.toString().trim()
                val pwd = pwdEdit.text.toString().trim()
                getSharedPreferences("config", Context.MODE_PRIVATE).edit()
                    .putString("gateway_ip", ip).putString("gateway_pwd", pwd)
                    .putInt("port_opengw", 8000).putInt("port_official", 8080).putInt("default_port", 8000).apply()
                currentPort = 8000
                startAppFlow()
            }
            .show()
    }

    private fun switchWebVersion() {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val ip = prefs.getString("gateway_ip", "192.168.0.1")!!
        val p1 = prefs.getInt("port_opengw", 8000)
        val p2 = prefs.getInt("port_official", 8080)
        currentPort = if (currentPort == p1) p2 else p1
        Toast.makeText(this, "正在切换版本...", Toast.LENGTH_SHORT).show()
        webView.loadUrl("http://$ip:$currentPort/index.html")
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
                webView.evaluateJavascript("sessionStorage.setItem('isLoggedIn', 'true');", null)
                // 关键：页面加载完成后清除历史，防止返回到加载页
                view?.clearHistory()
            }
        }
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun logout() {
                getSharedPreferences("config", Context.MODE_PRIVATE).edit().clear().apply()
                runOnUiThread { recreate() }
            }
            @JavascriptInterface
            fun exitApp() {
                finishAffinity()
            }
        }, "AndroidBridge")
    }

    private fun performAutoLogin() {
        val prefs = getSharedPreferences("config", Context.MODE_PRIVATE)
        val ip = prefs.getString("gateway_ip", "192.168.0.1")!!
        val pwd = prefs.getString("gateway_pwd", "")!!
        val p1 = prefs.getInt("port_opengw", 8000)
        val p2 = prefs.getInt("port_official", 8080)

        scope.launch {
            infoText.text = "正在同步网关登录状态..."
            val cookie = withContext(Dispatchers.IO) { doGatewayLogin(ip, pwd) }
            if (cookie != null) {
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setCookie("http://$ip:$p1", cookie)
                    setCookie("http://$ip:$p2", cookie)
                    flush()
                }
                webView.loadUrl("http://$ip:$currentPort/index.html")
            } else {
                infoText.text = "自动登录失败，点击重试"
                loadingLayout.setOnClickListener { recreate() }
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
        } catch (e: Exception) { return null }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.uppercase()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastBackTime < 2000) {
                finishAffinity() // 快速双击直接关闭所有 Activity 退出
            } else {
                Toast.makeText(this, "再按一次退出应用", Toast.LENGTH_SHORT).show()
                lastBackTime = currentTime
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}