package com.kpi.salesapp

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.media.AudioAttributes
import android.os.Build
import com.google.firebase.messaging.FirebaseMessaging
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

// Đoạn JS này được chèn vào index.html để thay thế google.script.run (chỉ chạy được trong Apps Script)
private const val SHIM_JS = """
(function () {
  var pending = {}, seq = 0;
  window.__nativeResult = function (id, json) {
    var cb = pending[id]; if (!cb) return; delete pending[id];
    cb(JSON.parse(json));
  };
  function nativeCall(action, params) {
    return new Promise(function (resolve) {
      var id = ++seq; pending[id] = resolve;
      AndroidBridge.call(id, action, JSON.stringify(params || {}));
    });
  }
  var D = 'direct', V = 'data';
  var API = {
    getInitData:        ['getInitData', V, function (a) { return { date: a[0] }; }],
    getSalesUsed:       ['getSalesUsed', V, function (a) { return { date: a[0] }; }],
    getStockBundle:     ['getStockBundle', V, function (a) { return { date: a[0] }; }],
    getHistoryBundle:   ['getHistoryBundle', V, function (a) { return {}; }],
    getStockHistory:    ['getStockHistory', V, function (a) { return {}; }],
    getPresence:        ['getPresence', V, function (a) { return {}; }],
    getStoreInfo:       ['getStoreInfo', V, function (a) { return {}; }],
    addNewStore:        ['addNewStore', V, function (a) { return { name: a[0] }; }],
    addNewProduct:      ['addNewProduct', V, function (a) { return { name: a[0] }; }],
    updateSales:        ['updateSales', V, function (a) { return { rowIndex: a[0], rec: a[1] }; }],
    deleteSales:        ['deleteSales', V, function (a) { return { rowIndex: a[0] }; }],
    updateStock:        ['updateStock', V, function (a) { return { rowIndex: a[0], rec: a[1] }; }],
    deleteStock:        ['deleteStock', V, function (a) { return { rowIndex: a[0] }; }],
    submitSalesBatch:   ['submitSalesBatch', D, function (a) { return a[0]; }],
    submitStockBatch:   ['submitStockBatch', D, function (a) { return a[0]; }],
    adminSetDelay:      ['adminSetDelay', D, function (a) { return { minutes: a[0] }; }],
    adminSetSalesEnabled: ['adminSetSalesEnabled', D, function (a) { return { on: a[0] }; }],
    adminSetSchedule:   ['adminSetSchedule', D, function (a) { return { sched: a[0] }; }],
    adminRenameCatalog: ['adminRenameCatalog', D, function (a) { return { type: a[0], oldName: a[1], newName: a[2] }; }],
    adminDeleteCatalog: ['adminDeleteCatalog', D, function (a) { return { type: a[0], name: a[1] }; }]
  };
  function run(name, args, ok, fail) {
    ok = ok || function () {};
    fail = fail || function (e) { console.error(e); };
    if (name === 'adminLogout') { ok('OK'); return; }
    if (name === 'requestAdminOtp' || name === 'verifyAdminOtp') {
      ok({ ok: false, error: 'Trong app, quyền admin được xác định theo email Google đã đăng nhập.' });
      return;
    }
    var spec = API[name];
    if (!spec) { fail(new Error('Chưa hỗ trợ trong app: ' + name)); return; }
    nativeCall(spec[0], spec[2](args)).then(function (res) {
      if (spec[1] === 'direct') ok(res);
      else if (res && res.ok) ok(res.data);
      else fail(new Error((res && res.error) || 'Lỗi không xác định'));
    });
  }
  function makeRunner(ok, fail) {
    return new Proxy({}, {
      get: function (t, name) {
        if (name === 'withSuccessHandler') return function (f) { return makeRunner(f, fail); };
        if (name === 'withFailureHandler') return function (f) { return makeRunner(ok, f); };
        return function () { run(name, Array.prototype.slice.call(arguments), ok, fail); };
      }
    });
  }
  window.google = { script: { run: makeRunner(null, null) } };
  window.addEventListener('load', function () {
    nativeCall('isAdmin', {}).then(function (r) {
      if (r && r.ok && r.isAdmin && typeof setAdmin === 'function') setAdmin(true, 'native');
    });
  });
})();
"""

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var execUrl: String
    private lateinit var googleClient: GoogleSignInClient
    private var idToken: String = ""
    private var tokenTime: Long = 0L

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        execUrl = getString(R.string.exec_url)
        idToken = SessionManager.getIdToken(this) ?: run { goLogin(); return }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.web_client_id))
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, gso)

        webView = WebView(this)
        webView.fitsSystemWindows = true
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false   // cho phép phát tiếng không cần chạm
                webView.webViewClient = object : WebViewClient() {
            // Phục vụ file âm thanh nằm trong assets/sounds/ cho trang web trong app
            override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                val u = request?.url
                val path = u?.path
                if (u != null && u.host == "appassets.local" && path != null && path.startsWith("/sounds/")) {
                    // 1) tìm trong assets/sounds/  2) không có thì tìm trong res/raw (đổi "-" thành "_", vd. sup-ton-dau-ca.mp3 -> sup_ton_dau_ca)
                    try {
                        return android.webkit.WebResourceResponse("audio/mpeg", null, assets.open(path.trimStart('/')))
                    } catch (e: Exception) { }
                    try {
                        val resName = path.removePrefix("/sounds/").substringBeforeLast('.').replace('-', '_')
                        val id = resources.getIdentifier(resName, "raw", packageName)
                        if (id != 0) return android.webkit.WebResourceResponse("audio/mpeg", null, resources.openRawResource(id))
                    } catch (e: Exception) { }
                }
                return super.shouldInterceptRequest(view, request)
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                val url = request?.url ?: return false
                if (url.host == "appassets.local") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, url)) } catch (e: Exception) { }
                return true
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Huỷ") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
            override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean {
                val input = EditText(this@MainActivity)
                input.setText(defaultValue ?: "")
                AlertDialog.Builder(this@MainActivity).setMessage(message).setView(input)
                    .setPositiveButton("OK") { _, _ -> result.confirm(input.text.toString()) }
                    .setNegativeButton("Huỷ") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
        }
        webView.addJavascriptInterface(Bridge(), "AndroidBridge")

        val html = assets.open("index.html").bufferedReader().use { it.readText() }
            .replaceFirst("<head>", "<head><script>$SHIM_JS</script>")
        webView.loadDataWithBaseURL("https://appassets.local/", html, "text/html", "UTF-8", null)
                askLocationConsent()
    }

    // ===== THÔNG BÁO ĐẨY (FCM) =====
    private val notifPermLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun setupPush() {
        PushChannels.ensure(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun registerPushToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { t ->
            lifecycleScope.launch {
                try { ApiClient.call(execUrl, "registerPush", freshToken(), JSONObject().put("token", t)) } catch (e: Exception) { }
            }
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun call(id: Int, action: String, paramsJson: String) {
            runOnUiThread {
                lifecycleScope.launch {
                    val token = freshToken()
                    val res: JSONObject = try {
                        ApiClient.call(execUrl, action, token, JSONObject(paramsJson))
                    } catch (e: Exception) {
                        JSONObject().put("ok", false).put("error", "Không kết nối được server: ${e.message}")
                    }
                    if (res.optString("error").startsWith("Phiên đăng nhập")) {
                        Toast.makeText(this@MainActivity, "Phiên đăng nhập hết hạn, vui lòng đăng nhập lại.", Toast.LENGTH_LONG).show()
                        SessionManager.clear(this@MainActivity)
                        goLogin()
                        return@launch
                    }
                    val quoted = JSONObject.quote(res.toString())
                        .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
                    webView.evaluateJavascript("window.__nativeResult($id, $quoted);", null)
                }
            }
        }
    }

    // ID token Google chỉ sống ~1 giờ nên tự làm mới ngầm khi đã cũ
    private suspend fun freshToken(): String {
        val now = System.currentTimeMillis()
        if (idToken.isNotEmpty() && now - tokenTime < 40 * 60 * 1000L) return idToken
        val t = suspendCancellableCoroutine<String?> { cont ->
            googleClient.silentSignIn()
                .addOnSuccessListener { acc -> cont.resume(acc.idToken) }
                .addOnFailureListener { cont.resume(null) }
        }
        if (t != null) {
            idToken = t
            tokenTime = now
            SessionManager.saveLogin(this, t, SessionManager.getEmail(this) ?: "")
        }
        return idToken
    }

        // ===== ONLINE + VỊ TRÍ =====
    private var hbJob: Job? = null

    private val locPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { ensureGpsOn() }

    private val gpsResolutionLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { }

    // Nếu GPS đang tắt thì hiện hộp "Bật vị trí" của hệ thống (bấm 1 lần là bật)
    private fun ensureGpsOn() {
        if (!hasLocPerm()) return
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 60_000L).build()
        val settingsReq = LocationSettingsRequest.Builder().addLocationRequest(req).build()
        LocationServices.getSettingsClient(this).checkLocationSettings(settingsReq)
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try {
                        gpsResolutionLauncher.launch(IntentSenderRequest.Builder(e.resolution).build())
                    } catch (ex: Exception) { }
                }
            }
    }

    private fun hasLocPerm(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askLocationConsent() {
        if (hasLocPerm()) return
        val prefs = getSharedPreferences("session", MODE_PRIVATE)
        if (prefs.getBoolean("locAsked", false)) return
        AlertDialog.Builder(this)
            .setTitle("Chia sẻ hoạt động & vị trí")
            .setMessage("Khi bạn đang mở ứng dụng, quản lý sẽ thấy bạn đang online, vị trí gần nhất và thao tác cuối cùng của bạn. Khi tắt ứng dụng sẽ không ghi vị trí.")
            .setPositiveButton("Đồng ý") { _, _ ->
                prefs.edit().putBoolean("locAsked", true).apply()
                locPermLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            .setNegativeButton("Không") { _, _ -> prefs.edit().putBoolean("locAsked", true).apply() }
            .setCancelable(false)
            .show()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!hasLocPerm()) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true).mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (e: Exception) { null }
        }.maxByOrNull { it.time }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        if (!hasLocPerm()) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val provider = when {
            fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return lastKnownLocation()
        }
        val loc = withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cs = CancellationSignal()
                cont.invokeOnCancellation { cs.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, cs, ContextCompat.getMainExecutor(this@MainActivity)) { l ->
                    if (cont.isActive) cont.resume(l)
                }
            }
        }
        return loc ?: lastKnownLocation()
    }

    private fun startHeartbeat() {
        if (!::webView.isInitialized) return
        hbJob?.cancel()
        hbJob = lifecycleScope.launch {
            while (true) {
                val p = JSONObject()
                val loc = try { currentLocation() } catch (e: Exception) { null }
                if (loc != null) {
                    p.put("loc", JSONObject()
                        .put("lat", loc.latitude).put("lng", loc.longitude).put("acc", loc.accuracy.toDouble()))
                }
                try { ApiClient.call(execUrl, "heartbeat", freshToken(), p) } catch (e: Exception) { }
                delay(60_000L)
            }
        }
    }

        override fun onStart() {
        super.onStart()
        setupPush()
        registerPushToken()
        startHeartbeat()
        ensureGpsOn()
    }

    override fun onStop() {
        super.onStop()
        hbJob?.cancel()
    }

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
