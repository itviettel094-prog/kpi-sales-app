# Hướng dẫn setup — App KPI Bán Hàng (Android native)

## Bước 1 — Google Cloud Console (bắt buộc, làm trước tiên)

1. Vào https://console.cloud.google.com/ → tạo project mới (hoặc dùng project có sẵn
   chứa Apps Script của bạn).
2. Vào **APIs & Services → OAuth consent screen**: cấu hình app (External hoặc Internal
   nếu bạn dùng Google Workspace), điền tên app, email hỗ trợ.
3. Vào **APIs & Services → Credentials → Create Credentials → OAuth client ID**:
   - Tạo **1 client loại "Web application"** → đặt tên gì cũng được → **COPY Client ID**
     (dạng `xxxx.apps.googleusercontent.com`). Đây là `WEB_CLIENT_ID`.
   - Tạo **1 client loại "Android"**:
     - Package name: `com.kpi.salesapp`
     - SHA-1 certificate fingerprint: lấy bằng lệnh (cần cài JDK):
       ```
       keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
       ```
       (copy dòng SHA1 ra, dán vào đây). Nếu build APK qua GitHub Actions bằng keystore
       debug mặc định của Gradle thì vẫn dùng SHA1 debug như trên — chỉ cần làm 1 lần.

## Bước 2 — Cập nhật Code.gs (ĐÃ GỘP SẴN — chỉ cần dán đè 1 lần)

1. Mở file Apps Script hiện tại của bạn → mở file **Code.gs**.
2. Bấm vào vùng code, Ctrl+A (chọn hết), bấm Delete (xoá hết nội dung cũ).
3. Mở file **`Code.gs.HOAN_CHINH_DAN_DE.gs`** (trong zip này) bằng Notepad → Copy toàn bộ →
   Dán vào Code.gs (đang trống).
4. File này đã điền sẵn:
   - `WEB_CLIENT_ID` = Web Client ID của bạn
   - `ALLOWED_ADMIN_EMAILS` = 3 email admin có sẵn (itviettel098@gmail.com, itviettel094@gmail.com,
     nguyenngoctaiaice@gmail.com). Muốn đổi thì sửa trực tiếp dòng này trong Code.gs.
5. Bấm **Save** (💾 hoặc Ctrl+S).
6. **Deploy lại**: Deploy → Manage deployments → Edit (bút chì) → chọn **New version** → Deploy.
   Giữ nguyên cấu hình deploy cũ (Execute as / Who has access) — không cần đổi gì,
   vì xác thực giờ nằm trong token Google, không phụ thuộc cấu hình deploy.
7. Copy link `.../exec` (nếu deploy version mới thì link `/exec` thường giữ nguyên,
   chỉ link `/dev` mới đổi).

## Bước 3 — Điền thông tin vào project Android

Mở file `android/app/src/main/res/values/strings.xml`, điền:
- `web_client_id` → Web Client ID giống hệt Bước 1 / Bước 2.
- `exec_url` → link `.../exec` của Apps Script.

## Bước 4 — Đẩy lên GitHub

```
cd android-app-repo   # thư mục chứa toàn bộ những gì mình vừa tạo
git init
git add .
git commit -m "Khung app Android KPI Bán Hàng"
git branch -M main
git remote add origin https://github.com/<tai-khoan>/<ten-repo>.git
git push -u origin main
```

GitHub Actions (`.github/workflows/android-build.yml`) sẽ tự chạy, build ra file
`app-debug.apk`. Vào tab **Actions** trên GitHub → chọn lần chạy mới nhất → mục
**Artifacts** → tải `app-debug-apk.zip` về, giải nén lấy file `.apk`, cài vào điện thoại
Android (nhớ bật "Cho phép cài từ nguồn không xác định").

## Hiện trạng code

✅ Đã làm xong và chạy được:
- Đăng nhập Google, xác thực token qua server
- Nhập Tồn kho (nhiều dòng, chọn Tồn đầu/Tồn cuối)
- Nhập Bán hàng (nhiều dòng)
- Gọi đúng các hàm `submitStockBatch`, `submitSalesBatch`, `getInitData` có sẵn trong Code.gs gốc

🚧 Chưa làm (khung đã sẵn, báo mình làm tiếp nếu cần):
- Màn Lịch sử: mới hiển thị số dòng thô, chưa gộp theo Ngày+Cửa hàng như giao diện web
- Màn Báo cáo checkin (copy báo cáo)
- Giao diện quản trị (Sửa/Xoá, đổi tên danh mục, hẹn giờ...) cho admin trong app native
- Khoá giờ nhập Tồn đầu/cuối hiển thị đếm ngược trên app (hiện tại server vẫn chặn đúng,
  chỉ là app chưa hiển thị đẹp thông báo "chưa đến giờ")
- App icon, giao diện đẹp hơn (hiện đang là layout tối giản để chạy được trước)
