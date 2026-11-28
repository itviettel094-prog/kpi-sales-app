package com.kpi.salesapp

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.os.CancellationSignal
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume

class LocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private lateinit var googleClient: GoogleSignInClient
    private var token: String = ""
    private var tokenTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.web_client_id))
            .requestEmail()
            .build()
        googleClient = GoogleSignIn.getClient(this, gso)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val chId = "loc_service"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(chId, "Định vị nền", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = NotificationCompat.Builder(this, chId)
            .setContentTitle("KPI Bán Hàng")
            .setContentText("Đang chia sẻ vị trí để hỗ trợ khẩn cấp")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(7001, n)
        } catch (e: Exception) { stopSelf(); return START_NOT_STICKY }

        if (job?.isActive != true) job = scope.launch { loop() }
        return START_STICKY
    }

    private suspend fun loop() {
        val execUrl = getString(R.string.exec_url)
        while (true) {
            if (SessionManager.getIdToken(this) == null) { stopSelf(); return }   // đã đăng xuất
            val p = JSONObject()
            val loc = try { currentLocation() } catch (e: Exception) { null }
            if (loc != null) {
                p.put("loc", JSONObject().put("lat", loc.latitude).put("lng", loc.longitude).put("acc", loc.accuracy.toDouble()))
            }
            try { ApiClient.call(execUrl, "heartbeat", freshToken(), p) } catch (e: Exception) { }
            delay(60_000L)
        }
    }

    private suspend fun freshToken(): String? {
        val now = System.currentTimeMillis()
        if (token.isNotEmpty() && now - tokenTime < 40 * 60 * 1000L) return token
        val r = suspendCancellableCoroutine<Result<String>> { cont ->
            googleClient.silentSignIn()
                .addOnSuccessListener { acc ->
                    val t = acc.idToken
                    cont.resume(if (t != null) Result.success(t) else Result.failure(Exception("null")))
                }
                .addOnFailureListener { e -> cont.resume(Result.failure(e)) }
        }
        r.onSuccess { token = it; tokenTime = now; return it }
        return SessionManager.getIdToken(this)
    }

    private fun hasLocPerm() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? {
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true).mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (e: Exception) { null }
        }.maxByOrNull { it.time }
    }

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location? {
        if (!hasLocPerm()) return null
        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        val fine = ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val provider = when {
            fine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return lastKnown()
        }
        val loc = withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val cs = CancellationSignal()
                cont.invokeOnCancellation { cs.cancel() }
                LocationManagerCompat.getCurrentLocation(lm, provider, cs, ContextCompat.getMainExecutor(this@LocationService)) { l ->
                    if (cont.isActive) cont.resume(l)
                }
            }
        }
        return loc ?: lastKnown()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
