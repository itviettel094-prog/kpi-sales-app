package com.kpi.salesapp

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var execUrl: String
    private lateinit var idToken: String

    private var stores: List<String> = emptyList()
    private var products: List<String> = emptyList()

    // Giỏ hàng tạm (chưa lưu) — mỗi phần tử: Triple(product, qty, mode?) dùng Map cho gọn
    private val stockCart = mutableListOf<MutableMap<String, Any>>()
    private val salesCart = mutableListOf<MutableMap<String, Any>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        execUrl = getString(R.string.exec_url)
        idToken = SessionManager.getIdToken(this) ?: run { goLogin(); return }

        findViewById<TextView>(R.id.userEmail).text = "👤 " + (SessionManager.getEmail(this) ?: "")
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            SessionManager.clear(this)
            goLogin()
        }

        setupNav()
        setupDefaults()
        loadInitData()

        findViewById<Button>(R.id.kAddLine).setOnClickListener { addStockLine() }
        findViewById<Button>(R.id.kSave).setOnClickListener { saveStock() }
        findViewById<Button>(R.id.sAddLine).setOnClickListener { addSalesLine() }
        findViewById<Button>(R.id.sSave).setOnClickListener { saveSales() }
    }

    private fun goLogin() {
        startActivity(android.content.Intent(this, LoginActivity::class.java))
        finish()
    }

    private fun setupNav() {
        val views = mapOf(
            R.id.navStock to R.id.viewStock,
            R.id.navSales to R.id.viewSales,
            R.id.navHistory to R.id.viewHistory
        )
        views.forEach { (btnId, viewId) ->
            findViewById<Button>(btnId).setOnClickListener {
                views.forEach { (_, vId) ->
                    findViewById<LinearLayout>(vId).visibility =
                        if (vId == viewId) android.view.View.VISIBLE else android.view.View.GONE
                }
                if (viewId == R.id.viewHistory) loadHistory()
            }
        }
    }

    private fun setupDefaults() {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        findViewById<EditText>(R.id.kDate).setText(today)
        findViewById<EditText>(R.id.sDate).setText(today)

        val modeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Tồn đầu (open)", "Tồn cuối (close)"))
        findViewById<Spinner>(R.id.kMode).adapter = modeAdapter
    }

    private fun loadInitData() {
        lifecycleScope.launch {
            val today = findViewById<EditText>(R.id.kDate).text.toString()
            val res = safeCall("getInitData", JSONObject().put("date", today))
            if (!res.optBoolean("ok", false)) {
                toast("Lỗi tải dữ liệu: " + res.optString("error"))
                return@launch
            }
            val data = res.getJSONObject("data")
            val formData = data.optJSONObject("formData")
            stores = jsonArrToList(formData?.optJSONArray("stores"))
            products = jsonArrToList(formData?.optJSONArray("products"))

            val storeAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, stores)
            findViewById<Spinner>(R.id.kStore).adapter = storeAdapter
            findViewById<Spinner>(R.id.sStore).adapter = storeAdapter

            val productAdapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, products)
            findViewById<Spinner>(R.id.kProduct).adapter = productAdapter
            findViewById<Spinner>(R.id.sProduct).adapter = productAdapter
        }
    }

    // ================= TỒN KHO =================
    private fun addStockLine() {
        val product = (findViewById<Spinner>(R.id.kProduct).selectedItem as? String) ?: return
        val qtyStr = findViewById<EditText>(R.id.kQty).text.toString()
        val qty = qtyStr.toDoubleOrNull()
        if (qty == null) { toast("Số lượng không hợp lệ"); return }
        val mode = if (findViewById<Spinner>(R.id.kMode).selectedItemPosition == 0) "open" else "close"

        stockCart.add(mutableMapOf("product" to product, "qty" to qty, "mode" to mode))
        findViewById<EditText>(R.id.kQty).setText("")
        renderStockCart()
    }

    private fun renderStockCart() {
        val box = findViewById<LinearLayout>(R.id.kCartList)
        box.removeAllViews()
        stockCart.forEachIndexed { i, item ->
            val tv = TextView(this)
            tv.text = "${i + 1}. ${item["product"]} — SL: ${item["qty"]} (${item["mode"]})"
            tv.setPadding(0, 8, 0, 8)
            box.addView(tv)
        }
    }

    private fun saveStock() {
        if (stockCart.isEmpty()) { toast("Chưa có dòng nào để lưu"); return }
        val date = findViewById<EditText>(R.id.kDate).text.toString()
        val store = findViewById<Spinner>(R.id.kStore).selectedItem as? String
        if (date.isBlank() || store == null) { toast("Chọn ngày và cửa hàng"); return }

        val items = JSONArray()
        stockCart.forEach {
            val o = JSONObject()
            o.put("product", it["product"])
            o.put("qty", it["qty"])
            o.put("mode", it["mode"])
            items.put(o)
        }
        val params = JSONObject().put("date", date).put("store", store).put("items", items)

        lifecycleScope.launch {
            val res = safeCall("submitStockBatch", params)
            if (res.optBoolean("ok", false)) {
                toast("Đã lưu ${res.optInt("count")} dòng tồn kho")
                stockCart.clear()
                renderStockCart()
            } else {
                setMsg(R.id.kMsg, res.optString("error", "Lỗi không xác định"))
            }
        }
    }

    // ================= BÁN HÀNG =================
    private fun addSalesLine() {
        val product = (findViewById<Spinner>(R.id.sProduct).selectedItem as? String) ?: return
        val qtyStr = findViewById<EditText>(R.id.sQty).text.toString()
        val qty = qtyStr.toDoubleOrNull()
        if (qty == null) { toast("Số lượng không hợp lệ"); return }

        salesCart.add(mutableMapOf("product" to product, "qty" to qty))
        findViewById<EditText>(R.id.sQty).setText("")
        renderSalesCart()
    }

    private fun renderSalesCart() {
        val box = findViewById<LinearLayout>(R.id.sCartList)
        box.removeAllViews()
        salesCart.forEachIndexed { i, item ->
            val tv = TextView(this)
            tv.text = "${i + 1}. ${item["product"]} — SL: ${item["qty"]}"
            tv.setPadding(0, 8, 0, 8)
            box.addView(tv)
        }
    }

    private fun saveSales() {
        if (salesCart.isEmpty()) { toast("Chưa có dòng nào để lưu"); return }
        val date = findViewById<EditText>(R.id.sDate).text.toString()
        val store = findViewById<Spinner>(R.id.sStore).selectedItem as? String
        if (date.isBlank() || store == null) { toast("Chọn ngày và cửa hàng"); return }

        val items = JSONArray()
        salesCart.forEach {
            val o = JSONObject()
            o.put("product", it["product"])
            o.put("qty", it["qty"])
            items.put(o)
        }
        val params = JSONObject().put("date", date).put("store", store).put("items", items)

        lifecycleScope.launch {
            val res = safeCall("submitSalesBatch", params)
            if (res.optBoolean("ok", false)) {
                toast("Đã lưu ${res.optInt("count")} dòng bán hàng")
                salesCart.clear()
                renderSalesCart()
            } else {
                setMsg(R.id.sMsg, res.optString("error", "Lỗi không xác định"))
            }
        }
    }

    // ================= LỊCH SỬ (khung cơ bản — TODO: làm giao diện gộp như bản web) =================
    private fun loadHistory() {
        val tv = findViewById<TextView>(R.id.historyText)
        tv.text = "Đang tải..."
        lifecycleScope.launch {
            val res = safeCall("getHistoryBundle", JSONObject())
            if (!res.optBoolean("ok", false)) {
                tv.text = "Lỗi: " + res.optString("error")
                return@launch
            }
            val data = res.getJSONObject("data")
            val sales = data.optJSONArray("sales") ?: JSONArray()
            val stock = data.optJSONArray("stock") ?: JSONArray()
            tv.text = "Bán hàng: ${sales.length()} dòng\nTồn kho: ${stock.length()} dòng\n\n(TODO: hiển thị chi tiết gộp theo ngày/cửa hàng)"
        }
    }

    // ================= HELPERS =================
    private suspend fun safeCall(action: String, params: JSONObject): JSONObject {
        return try {
            ApiClient.call(execUrl, action, idToken, params)
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("error", "Không kết nối được server: ${e.message}")
        }
    }

    private fun jsonArrToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun setMsg(id: Int, msg: String) {
        findViewById<TextView>(id).text = msg
    }
}
