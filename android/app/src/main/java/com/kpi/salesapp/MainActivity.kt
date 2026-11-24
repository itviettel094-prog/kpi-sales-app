/*************************************************************
 * FORM NHẬP DỮ LIỆU NGHIỆM THU NHÂN SỰ - MINIMART/TẠP HÓA
 *************************************************************/

// (Tuỳ chọn) ID file ảnh trên Google Drive. Để '' thì script tự vẽ nền.
// ID là đoạn giữa /d/ và /view trong link Drive của ảnh.
const COVER_FILE_ID = '';
const BG_FILE_ID = '';

const SHEET_ID = '1ImZFyapmikG-V438kvuqcBSRkSyzoXhQkVz9_LG0oiM';

const FONT = 'Times New Roman';
const RED = '#E80205';
const BLACK = '#000000';
const BG_COLOR = '#EDEDED';
const DATES_PER_SLIDE = 6;
let ZOOM_QUEUE = [];

// ====== WEB APP ======
function doGet() {
  return HtmlService.createHtmlOutputFromFile('index')
    .setTitle('Nhập dữ liệu nghiệm thu')
    .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}

function getShopList() {
  kiemTraMoCua_();
  const sh = SpreadsheetApp.openById(SHEET_ID).getSheets()[0];
  const n = sh.getLastRow();
  if (n < 2) return [];
  return sh.getRange(2, 1, n - 1, 2).getValues()
    .filter(function (r) { return r[0] !== ''; })
    .map(function (r) { return { name: String(r[0]).trim(), address: String(r[1]).trim() }; });
}

function submitShop(payload) {
  const lock = LockService.getScriptLock();
  try {
    lock.waitLock(30000);
    kiemTraMoCua_();
    const pres = getOrCreateMasterPresentation_();
    
    cleanDataSlides_(pres);
    ZOOM_QUEUE = [];
    const geo = computeGeometry_(pres);

    const allDates = payload.dates || [];
    const totalSlides = Math.max(1, Math.ceil(allDates.length / DATES_PER_SLIDE));
    const slideUrls = [];

    for (let chunk = 0; chunk < totalSlides; chunk++) {
      const start = chunk * DATES_PER_SLIDE;
      const chunkDates = allDates.slice(start, start + DATES_PER_SLIDE);
      const chunkImages = (payload.images || [])
        .filter(function (im) { return im.dateIndex >= start && im.dateIndex < start + DATES_PER_SLIDE; })
        .map(function (im) { return Object.assign({}, im, { dateIndex: im.dateIndex - start }); });

      const part = totalSlides > 1 ? '  (phần ' + (chunk + 1) + '/' + totalSlides + ')' : '';
      const slide = buildDataSlide_(pres, geo, payload.shopName, payload.address + part, chunkDates, chunkImages);
      slideUrls.push(pres.getUrl() + '#slide=id.' + slide.getObjectId());
    }

    buildZoomSlides_(pres, geo);
    pres.saveAndClose();
    const exp = exportPresentationAsPptx_(pres.getId());
    return { ok: true, presentationUrl: pres.getUrl(), slideUrls: slideUrls, downloadUrl: exp.url, fileId: exp.id };
  } catch (err) {
    return { ok: false, error: err.message };
  } finally {
    lock.releaseLock();
  }
}

// ====== NỀN ======
function applyBackground_(slide, pres, fileId) {
  if (fileId) {
    const img = slide.insertImage(DriveApp.getFileById(fileId).getBlob());
    img.setLeft(0).setTop(0).setWidth(pres.getPageWidth()).setHeight(pres.getPageHeight());
    img.sendToBack();
  } else {
    slide.getBackground().setSolidFill(BG_COLOR);
  }
}

function addRect_(slide, x, y, w, h, color) {
  const s = slide.insertShape(SlidesApp.ShapeType.RECTANGLE, x, y, w, h);
  s.getFill().setSolidFill(color);
  s.getBorder().setTransparent();
  return s;
}

function addText_(slide, text, x, y, w, h, size, color, bold, italic) {
  const t = slide.insertTextBox(text, x, y, w, h);
  t.setContentAlignment(SlidesApp.ContentAlignment.MIDDLE);
  const tx = t.getText();
  tx.getParagraphStyle().setParagraphAlignment(SlidesApp.ParagraphAlignment.CENTER);
  tx.getTextStyle().setFontFamily(FONT).setFontSize(size)
    .setBold(!!bold).setItalic(!!italic).setForegroundColor(color);
  return t;
}

// ====== DỌN DỮ LIỆU CŨ ======
const PROTECTED_SLIDES = 2;

function cleanDataSlides_(pres) {
  const slides = pres.getSlides();
  for (let i = slides.length - 1; i >= PROTECTED_SLIDES; i--) {
    slides[i].remove();
  }
}

// ====== FILE MASTER ======
function getOrCreateMasterPresentation_() {
  const props = PropertiesService.getScriptProperties();
  const savedId = props.getProperty('MASTER_PRESENTATION_ID');
  if (savedId) {
    try { return SlidesApp.openById(savedId); } catch (e) { /* mất file -> tạo mới */ }
  }

  const pres = SlidesApp.create('Nghiệm thu nhân sự - Minimart Tạp hóa');
  const W = pres.getPageWidth();
  const H = pres.getPageHeight();
  const fs = W / 960;

  // Trang 1: bìa
  const s1 = pres.getSlides()[0];
  s1.getPageElements().forEach(function (el) { el.remove(); });
  if (COVER_FILE_ID) {
    applyBackground_(s1, pres, COVER_FILE_ID);
  } else {
    s1.getBackground().setSolidFill('#FFFFFF');
    addRect_(s1, 0, 0, W, H * 0.06, RED);
    addRect_(s1, 0, H * 0.94, W, H * 0.06, RED);
    addText_(s1, 'HANOI', 0, H * 0.14, W, H * 0.12, Math.round(36 * fs), RED, true, false);
    addText_(s1, 'TIẾP NỐI TINH HOA\nKHÔNG NGỪNG TIẾN BƯỚC',
      0, H * 0.32, W, H * 0.36, Math.round(40 * fs), RED, true, false);
    addRect_(s1, W * 0.3, H * 0.72, W * 0.4, 3, RED);
    addText_(s1, 'Nghiệm thu nhân sự - Minimart / Tạp hóa',
      0, H * 0.76, W, H * 0.1, Math.round(18 * fs), BLACK, false, true);
  }

  // Trang 2: Khu vực Hà Nội 2
  const s2 = pres.appendSlide(SlidesApp.PredefinedLayout.BLANK);
  applyBackground_(s2, pres, BG_FILE_ID);
  addText_(s2, 'Khu vực Hà Nội 2', 0, H * 0.38, W, H * 0.18, Math.round(32 * fs), RED, true, false);

  props.setProperty('MASTER_PRESENTATION_ID', pres.getId());
  return pres;
}

// ====== BỐ CỤC ======
function computeGeometry_(pres) {
  const W = pres.getPageWidth();
  const H = pres.getPageHeight();
  const left = W * 0.045;
  const width = W * 0.91;
  const top = H * 0.225;
  const bottom = H * 0.03;
  const avail = H - top - bottom;
  const hdrH = Math.round(H * 0.062);
  const imgH = (avail - 2 * hdrH) / 2;
  return {
    W: W, H: H, fs: W / 960,
    left: left, width: width, top: top,
    colW: width / 3, hdrH: hdrH, imgH: imgH,
    pad: Math.max(4, W * 0.008)
  };
}

function cellRect_(g, dateIndex) {
  const col = dateIndex % 3;
  const group = dateIndex < 3 ? 0 : 1;
  const headerTop = g.top + group * (g.hdrH + g.imgH);
  return { left: g.left + col * g.colW, headerTop: headerTop, imgTop: headerTop + g.hdrH };
}

// ====== TRANG DỮ LIỆU ======
function buildDataSlide_(pres, g, shopName, address, dates, images) {
  const slide = pres.appendSlide(SlidesApp.PredefinedLayout.BLANK);
  applyBackground_(slide, pres, BG_FILE_ID);

  const title = slide.insertTextBox(
    shopName + '\n' + 'Địa chỉ : ' + address, g.left, g.H * 0.03, g.width, g.H * 0.18);
  title.setContentAlignment(SlidesApp.ContentAlignment.MIDDLE);
  const tt = title.getText();
  tt.getParagraphStyle().setParagraphAlignment(SlidesApp.ParagraphAlignment.CENTER);
  tt.getRange(0, shopName.length).getTextStyle()
    .setFontFamily(FONT).setFontSize(Math.round(30 * g.fs)).setBold(true).setForegroundColor(RED);
  tt.getRange(shopName.length, tt.asString().length).getTextStyle()
    .setFontFamily(FONT).setFontSize(Math.round(17 * g.fs)).setBold(true).setItalic(true).setForegroundColor(RED);

  for (let i = 0; i < DATES_PER_SLIDE; i++) {
    const r = cellRect_(g, i);

    const header = slide.insertShape(SlidesApp.ShapeType.RECTANGLE, r.left, r.headerTop, g.colW, g.hdrH);
    styleCell_(header);
    header.setContentAlignment(SlidesApp.ContentAlignment.MIDDLE);
    const ht = header.getText();
    ht.setText('Ngày ' + (dates[i] || ''));
    ht.getParagraphStyle().setParagraphAlignment(SlidesApp.ParagraphAlignment.CENTER);
    ht.getTextStyle().setFontFamily(FONT).setFontSize(Math.round(14 * g.fs))
      .setItalic(true).setBold(false).setUnderline(false).setForegroundColor(BLACK);

    const body = slide.insertShape(SlidesApp.ShapeType.RECTANGLE, r.left, r.imgTop, g.colW, g.imgH);
    styleCell_(body);
  }

  const byDate = {};
  images.forEach(function (im) {
    if (!byDate[im.dateIndex]) byDate[im.dateIndex] = [];
    byDate[im.dateIndex][im.slot] = im;
  });
  Object.keys(byDate).forEach(function (k) {
    const idx = parseInt(k, 10);
    if (idx < DATES_PER_SLIDE) placeImagesInCell_(slide, g, idx, byDate[idx]);
  });

  return slide;
}

function styleCell_(shape) {
  shape.getFill().setSolidFill('#FFFFFF');
  shape.getBorder().getLineFill().setSolidFill(BLACK);
  shape.getBorder().setWeight(0.75);
  shape.getBorder().setDashStyle(SlidesApp.DashStyle.SOLID);
}

function placeImagesInCell_(slide, g, dateIndex, list) {
  const items = [];
  [0, 1].forEach(function (s) { if (list[s] && list[s].base64) items.push(list[s]); });
  if (!items.length) return;

  const r = cellRect_(g, dateIndex);
  const gap = g.pad;
  const innerW = g.colW - 2 * g.pad;
  const innerH = g.imgH - 2 * g.pad;
  const slotW = (innerW - gap) / 2;

  items.forEach(function (data, i) {
    const blob = Utilities.newBlob(
      Utilities.base64Decode(data.base64),
      data.mimeType || 'image/jpeg',
      data.fileName || ('img_' + dateIndex + '_' + i + '.jpg'));
    const img = slide.insertImage(blob);

    const nw = data.w || img.getWidth();
    const nh = data.h || img.getHeight();
    const k = Math.min(slotW / nw, innerH / nh);
    const w = nw * k;
    const h = nh * k;

    const slotLeft = r.left + g.pad + (data.slot || 0) * (slotW + gap);
    img.setWidth(w).setHeight(h)
      .setLeft(slotLeft + (slotW - w) / 2)
      .setTop(r.imgTop + g.pad + (innerH - h) / 2);
    ZOOM_QUEUE.push({ img: img, blob: blob, slide: slide, nw: nw, nh: nh });
  });
}

// ====== TRANG PHÓNG TO ẢNH ======
function buildZoomSlides_(pres, g) {
  ZOOM_QUEUE.forEach(function (z) {
    const zs = pres.appendSlide(SlidesApp.PredefinedLayout.BLANK);
    zs.getBackground().setSolidFill('#000000');
    const big = zs.insertImage(z.blob);
    const k = Math.min(g.W / z.nw, g.H / z.nh);
    const w = z.nw * k, h = z.nh * k;
    big.setWidth(w).setHeight(h).setLeft((g.W - w) / 2).setTop((g.H - h) / 2);
    big.setLinkSlide(z.slide);   // bấm ảnh lớn -> về trang dữ liệu
    const cover = zs.insertShape(SlidesApp.ShapeType.RECTANGLE, 0, 0, g.W, g.H);
    cover.getFill().setTransparent();
    cover.getBorder().setTransparent();
    cover.setLinkSlide(z.slide); // bấm bất kỳ đâu trên trang -> về trang dữ liệu
    z.img.setLinkSlide(zs);      // bấm ảnh nhỏ -> sang ảnh lớn
    try { zs.setSkipped(true); } catch (e) {}  // ẩn khỏi trình chiếu tuần tự
  });
}

// ====== LƯU VÀO DRIVE ======
const SAVE_FOLDER_NAME = 'Nghiệm thu nhân sự - Đã lưu';
const SAVE_PASSWORD = 'Tuyet1986@';
const SAVE_FOLDER_ID = ''; // tuỳ chọn: dán ID thư mục có sẵn, để '' thì tự tạo theo tên trên

function getSaveFolder_() {
  if (SAVE_FOLDER_ID) return DriveApp.getFolderById(SAVE_FOLDER_ID);
  const it = DriveApp.getFoldersByName(SAVE_FOLDER_NAME);
  return it.hasNext() ? it.next() : DriveApp.createFolder(SAVE_FOLDER_NAME);
}

function saveShopToDrive(shopName, fileId, fileName, password) {
  try {
    kiemTraMoCua_();
    if (password !== SAVE_PASSWORD) return { ok: false, error: 'Sai mật khẩu.' };
    if (!fileId) return { ok: false, error: 'Chưa có file. Hãy bấm Gửi dữ liệu trước.' };
    const folder = getSaveFolder_();
    const stamp = Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyy-MM-dd HH-mm');
    const safe = String(shopName || 'shop').replace(/[\\\/:*?"<>|]/g, '_');
    const copy = DriveApp.getFileById(fileId).makeCopy(fileName || (safe + ' - ' + stamp + '.pptx'), folder);
    return { ok: true, url: copy.getUrl(), folderUrl: folder.getUrl() };
  } catch (err) {
    return { ok: false, error: err.message };
  }
}

// ====== TẢI & XÓA FILE XUẤT ======
function getExportBase64(fileId) {
  try {
    kiemTraMoCua_();
    const bytes = DriveApp.getFileById(fileId).getBlob().getBytes();
    return { ok: true, base64: Utilities.base64Encode(bytes) };
  } catch (err) {
    return { ok: false, error: err.message };
  }
}

function deleteExport(fileId) {
  try {
    DriveApp.getFileById(fileId).setTrashed(true);
    PropertiesService.getScriptProperties().deleteProperty('LAST_EXPORT_FILE_ID');
  } catch (e) {}
}

// ====== XUẤT .PPTX ======

function exportPresentationAsPptx_(presId) {
  const props = PropertiesService.getScriptProperties();
  const oldId = props.getProperty('LAST_EXPORT_FILE_ID');
  if (oldId) {
    try { DriveApp.getFileById(oldId).setTrashed(true); } catch (e) {}
  }
  const src = DriveApp.getFileById(presId);
  const res = UrlFetchApp.fetch(
    'https://docs.google.com/presentation/d/' + presId + '/export/pptx',
    {
      headers: { Authorization: 'Bearer ' + ScriptApp.getOAuthToken() },
      muteHttpExceptions: true
    });
  if (res.getResponseCode() !== 200) {
    throw new Error('Xuất .pptx thất bại (mã ' + res.getResponseCode() + ')');
  }
  const f = DriveApp.createFile(res.getBlob().setName(src.getName() + '.pptx'));
  try {
    f.setSharing(DriveApp.Access.ANYONE_WITH_LINK, DriveApp.Permission.VIEW);
  } catch (e) { /* tài khoản công ty có thể chặn chia sẻ, vẫn tiếp tục */ }
  props.setProperty('LAST_EXPORT_FILE_ID', f.getId());
  return { id: f.getId(), url: 'https://drive.google.com/uc?export=download&id=' + f.getId() };
}


// ============================================
// ADMIN: ĐĂNG NHẬP BẰNG MÃ OTP GỬI QUA EMAIL
// ============================================
const ADMIN_EMAIL = 'itviettel098@gmail.com';

function adminSendOtp() {
  const cache = CacheService.getScriptCache();
  if (cache.get('otp_cooldown')) return { ok: false, error: 'Vui lòng đợi 60 giây rồi bấm gửi lại mã.' };

  const code = String(parseInt(Utilities.getUuid().replace(/-/g, '').slice(0, 8), 16) % 900000 + 100000);
  cache.put('otp_code', code, 300);
  cache.put('otp_tries', '0', 300);
  cache.put('otp_cooldown', '1', 60);

  MailApp.sendEmail({
    to: ADMIN_EMAIL,
    subject: 'Mã xác thực admin: ' + code,
    body: 'Mã OTP đăng nhập quản trị của bạn là: ' + code +
          '\n\nMã có hiệu lực trong 5 phút. Nếu không phải bạn yêu cầu, hãy bỏ qua email này.'
  });
  return { ok: true };
}

function adminLogin(code) {
  const cache = CacheService.getScriptCache();
  const real = cache.get('otp_code');
  if (!real) return { ok: false, error: 'Mã đã hết hạn hoặc chưa được gửi, hãy bấm gửi mã mới.' };

  const tries = Number(cache.get('otp_tries') || 0) + 1;
  if (tries > 5) {
    cache.remove('otp_code');
    return { ok: false, error: 'Nhập sai quá 5 lần, hãy gửi mã mới.' };
  }
  cache.put('otp_tries', String(tries), 300);

  if (String(code || '').trim() !== real) return { ok: false, error: 'Sai mã OTP.' };

  cache.remove('otp_code');
  const token = Utilities.getUuid();
  cache.put('admin_' + token, ADMIN_EMAIL, 21600); // phiên 6 giờ
  return { ok: true, token: token, email: ADMIN_EMAIL };
}

function adminCheck(token) {
  const email = token ? CacheService.getScriptCache().get('admin_' + token) : null;
  return email ? { ok: true, email: email } : { ok: false };
}

function adminLogout(token) {
  if (token) CacheService.getScriptCache().remove('admin_' + token);
  return { ok: true };
}
// ============================================
// ADMIN: KHÓA WEB + THỐNG KÊ ONLINE / TRUY CẬP
// ============================================
function isSiteEnabled_() {
  return PropertiesService.getScriptProperties().getProperty('SITE_ENABLED') !== 'false';
}

function kiemTraMoCua_() {
  if (!isSiteEnabled_()) throw new Error('SITE_DISABLED');
}

function homNay_() {
  return Utilities.formatDate(new Date(), Session.getScriptTimeZone(), 'yyyy-MM-dd');
}

function layOnline_() {
  const raw = CacheService.getScriptCache().get('online');
  const map = raw ? JSON.parse(raw) : {};
  const now = Date.now();
  Object.keys(map).forEach(function (k) { if (now - map[k].t > 150000) delete map[k]; });
  return map;
}

function layLuot_() {
  const raw = PropertiesService.getScriptProperties().getProperty('VISITS');
  return raw ? JSON.parse(raw) : { total: 0, days: {} };
}

// Trình duyệt gọi mỗi 60 giây: báo "đang online" + đếm lượt truy cập mới
function ping(clientId, newVisit, shopName) {
  if (clientId) {
    const lock = LockService.getScriptLock();
    try {
      lock.waitLock(5000);
      const map = layOnline_();
      map[String(clientId).slice(0, 40)] = { t: Date.now(), shop: String(shopName || '').slice(0, 100) };
      CacheService.getScriptCache().put('online', JSON.stringify(map), 600);

      if (newVisit) {
        const v = layLuot_();
        const d = homNay_();
        v.total++;
        v.days[d] = (v.days[d] || 0) + 1;
        Object.keys(v.days).sort().slice(0, -30).forEach(function (k) { delete v.days[k]; });
        PropertiesService.getScriptProperties().setProperty('VISITS', JSON.stringify(v));
      }
    } catch (e) {
    } finally {
      try { lock.releaseLock(); } catch (e2) {}
    }
  }
  return { enabled: isSiteEnabled_() };
}

function thongKe_() {
  const now = Date.now();
  const map = layOnline_();
  const v = layLuot_();
  return {
    ok: true,
    enabled: isSiteEnabled_(),
    visitsTotal: v.total,
    visitsToday: v.days[homNay_()] || 0,
    online: Object.keys(map).map(function (k) {
      return { shop: map[k].shop, giay: Math.round((now - map[k].t) / 1000) };
    }).sort(function (a, b) { return a.giay - b.giay; })
  };
}

function adminStats(token) {
  if (!adminCheck(token).ok) return { ok: false, error: 'Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.' };
  return thongKe_();
}

function adminSetEnabled(token, enabled) {
  if (!adminCheck(token).ok) return { ok: false, error: 'Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại.' };
  PropertiesService.getScriptProperties().setProperty('SITE_ENABLED', enabled ? 'true' : 'false');
  return thongKe_();
}

// =====================================================================
//  THÊM VÀO CUỐI FILE Code.gs
//  Cho phép GitHub Pages gọi Apps Script qua fetch (không cần đăng nhập Google)
// =====================================================================

var WEB_ALLOWED_NGHIEMTHU = [
  'getShopList', 'submitShop', 'getExportBase64', 'deleteExport',
  'saveShopToDrive', 'ping', 'adminSendOtp', 'adminLogin',
  'adminCheck', 'adminLogout', 'adminStats', 'adminSetEnabled'
];

function doPost(e) {
  var body;
  try {
    body = JSON.parse((e && e.postData && e.postData.contents) || '{}');
  } catch (err) {
    return _ntJsonOut({ error: 'Dữ liệu không hợp lệ.' });
  }

  var fn   = body.fn;
  var args = body.args || [];

  if (!fn || WEB_ALLOWED_NGHIEMTHU.indexOf(fn) < 0) {
    return _ntJsonOut({ error: 'Hàm không hợp lệ: ' + fn });
  }

  var result;
  try {
    result = globalThis[fn].apply(null, args);
  } catch (err) {
    return _ntJsonOut({ error: String(err && err.message || err) });
  }

  return _ntJsonOut({ result: result });
}

function _ntJsonOut(obj) {
  return ContentService
    .createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
