package com.kpi.salesapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.launch
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private lateinit var googleClient: GoogleSignInClient
    private lateinit var progress: ProgressBar
    private lateinit var errorText: TextView

    private val signInLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(com.google.android.gms.common.api.ApiException::class.java)
            val idToken = account.idToken
            val email = account.email
            if (idToken != null && email != null) {
                verifyWithServer(idToken, email)
            } else {
                showError("Không lấy được idToken. Kiểm tra lại Web Client ID.")
            }
        } catch (e: com.google.android.gms.common.api.ApiException) {
            showError("Đăng nhập thất bại (mã lỗi ${e.statusCode}).")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        progress = findViewById(R.id.progress)
        errorText = findViewById(R.id.errorText)

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.web_client_id))
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, gso)

        // Nếu đã có phiên đăng nhập lưu sẵn, vào thẳng MainActivity
        if (SessionManager.getIdToken(this) != null) {
            goToMain()
            return
        }

        findViewById<Button>(R.id.btnSignIn).setOnClickListener {
            errorText.text = ""
            signInLauncher.launch(googleClient.signInIntent)
        }
    }

    private fun verifyWithServer(idToken: String, email: String) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val execUrl = getString(R.string.exec_url)
            val res: JSONObject = try {
                ApiClient.call(execUrl, "isAdmin", idToken)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "Không kết nối được server: ${e.message}")
            }
            progress.visibility = View.GONE
            if (res.optBoolean("ok", false)) {
                SessionManager.saveLogin(this@LoginActivity, idToken, email)
                goToMain()
            } else {
                showError(res.optString("error", "Đăng nhập bị từ chối bởi server."))
            }
        }
    }

    private fun showError(msg: String) {
        errorText.text = msg
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
