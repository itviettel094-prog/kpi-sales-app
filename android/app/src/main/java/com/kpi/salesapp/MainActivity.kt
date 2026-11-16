package com.kpi.salesapp

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
    addNewStore:        ['addNewStore', V, function (a) { return { name: a[0] }; }],
    addNewProduct:      ['addNewProduct', V, function (a) { return { name: a[0] }; }],
    updateSales:        ['updateSales', V, function (a) { return { rowIndex: a[0], rec: a[1] }; }],
    deleteSales:        ['deleteSales', V, function (a) { return { rowIndex: a[0] }; }],
    updateStock:        ['updateStock', V, function (a) { return { rowIndex: a[0], rec: a[1] }; }],
    deleteStock:        ['deleteStock', V, function (a) { return { rowIndex: a[0] }; }],
    submitSalesBatch:   ['submitSalesBatch', D, function (a) { return a[0]; }],
    submitStockBatch:   ['submitStockBatch', D, function (a) { return a[0]; }],
    adminSetDelay:      ['adminSetDelay', D, function (a) { return { minutes: a[0] }; }],
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
        webView.webViewClient = WebViewClient()
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

    private fun goLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
