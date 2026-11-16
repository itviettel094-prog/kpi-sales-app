// ============================================================
//  APPS SCRIPT — QUẢN LÝ KPI BÁN HÀNG (file Code.gs)
//  BẢN ĐÃ GỘP SẴN: code gốc + API cho app Android native
//  Cách dùng: MỞ Code.gs hiện tại trên Apps Script, CHỌN HẾT (Ctrl+A),
//  XOÁ, rồi DÁN TOÀN BỘ nội dung file này vào. Lưu (Ctrl+S). Deploy lại.
// ============================================================

var SHEET_SALES = "Bán hàng";
var SHEET_STOCK  = "Tồn kho";
var SHEET_DANHMUC = "Danh mục";

// Admin đăng nhập bằng mã OTP 6 số, gửi tự động tới các email dưới đây (dùng cho giao diện web cũ)
var ADMIN_EMAILS = ["itviettel098@gmail.com", "itviettel094@gmail.com", "nguyenngoctaiaice@gmail.com"];
var OTP_TTL_SEC = 300;                 // mã OTP có hiệu lực 5 phút
var OTP_MAX_TRIES = 5;                 // nhập sai tối đa 5 lần
var ADMIN_SESSION_SEC = 6 * 60 * 60;   // phiên admin 6 tiếng (tối đa của CacheService)

var SALES_HEADERS = ["STT", "Ngày", "Cửa hàng", "Sản phẩm", "Số lượng bán", "Người lưu", "isDeleted", "editLog"];
var STOCK_HEADERS  = ["STT", "Ngày", "Cửa hàng", "Sản phẩm", "Tồn đầu", "Tồn cuối", "Người lưu đầu", "Người lưu cuối", "isDeleted", "editLog", "Giờ nhập tồn đầu"];

// Sau khi nhập Tồn đầu phải đợi bao nhiêu phút mới được nhập Tồn cuối
var _delayProp = PropertiesService.getScriptProperties().getProperty('CLOSE_DELAY_MIN');   // admin có thể đổi trong giao diện
// Lưu ý: chưa cài đặt thì getProperty trả về null -> phải dùng mặc định 120, không được ép thành 0
var CLOSE_DELAY_MIN = (_delayProp === null || _delayProp === '' || isNaN(Number(_delayProp)) || Number(_delayProp) < 0) ? 120 : Number(_delayProp);
var CLOSE_DELAY_MS = CLOSE_DELAY_MIN * 60 * 1000;

function _getSheet(name, headers) {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sh = ss.getSheetByName(name);
  if (!sh) {
    sh = ss.insertSheet(name);
    sh.getRange(1, 1, 1, headers.length).setValues([headers])
      .setFontWeight('bold').setBackground('#c8e6c9').setHorizontalAlignment('center');
    sh.setFrozenRows(1);
  }
  // Sheet cũ chưa có đủ cột (vd. cột "Giờ nhập tồn đầu") thì tự bổ sung
  if (sh.getMaxColumns() < headers.length) sh.insertColumnsAfter(sh.getMaxColumns(), headers.length - sh.getMaxColumns());
  var lastHeader = sh.getRange(1, headers.length);
  if (lastHeader.getValue() === '') {
    lastHeader.setValue(headers[headers.length - 1]).setFontWeight('bold').setBackground('#c8e6c9').setHorizontalAlignment('center');
  }
  return sh;
}

function _getDanhMucSheet() {
  var ss = SpreadsheetApp.getActiveSpreadsheet();
  var sh = ss.getSheetByName(SHEET_DANHMUC);
  if (!sh) {
    sh = ss.insertSheet(SHEET_DANHMUC);
    sh.getRange(1, 1, 1, 2).setValues([["Cửa hàng", "Sản phẩm"]])
      .setFontWeight('bold').setBackground('#c8e6c9').setHorizontalAlignment('center');
    sh.setFrozenRows(1);
    sh.getRange(2, 1, 2, 1).setValues([["Cửa hàng A"], ["Cửa hàng B"]]);
    sh.getRange(2, 2, 2, 1).setValues([["Sản phẩm 1"], ["Sản phẩm 2"]]);
  }
  return sh;
}

function _fmtDate(v) {
  return v instanceof Date ? Utilities.formatDate(v, "GMT+7", "yyyy-MM-dd") : String(v);
}

function _fmtTime(ms) {
  return Utilities.formatDate(new Date(ms), "GMT+7", "HH:mm");
}

// ------------------------------------------------------------
// DROPDOWN (cửa hàng + sản phẩm)
// ------------------------------------------------------------
function getFormData() {
  var sh = _getDanhMucSheet();
  var lastRow = sh.getLastRow();
  var stores = [], products = [];
  if (lastRow >= 2) {
    var vals = sh.getRange(2, 1, lastRow - 1, 2).getValues();
    vals.forEach(function(r) {
      if (r[0]) stores.push(String(r[0]).trim());
      if (r[1]) products.push(String(r[1]).trim());
    });
  }
  return { stores: stores, products: products };
}

function addNewStore(name) {
  try {
    name = String(name || '').trim();
    if (!name) return "ERR|Tên cửa hàng trống";
    var sh = _getDanhMucSheet();
    var lastRow = sh.getLastRow();
    var existing = lastRow >= 2 ? sh.getRange(2, 1, lastRow - 1, 1).getValues().flat().filter(String) : [];
    var upper = name.toUpperCase();
    var dup = existing.some(function(v) { return String(v).trim().toUpperCase() === upper; });
    if (dup) return "OK";
    var col = sh.getRange(2, 1, Math.max(lastRow - 1, 0), 1).getValues().flat();
    var firstEmpty = col.indexOf('');
    var row = firstEmpty !== -1 ? firstEmpty + 2 : lastRow + 1;
    sh.getRange(row, 1).setValue(name);
    return "OK";
  } catch (e) {
    return "ERR|" + e.toString();
  }
}

function addNewProduct(name) {
  try {
    name = String(name || '').trim();
    if (!name) return "ERR|Tên sản phẩm trống";
    var sh = _getDanhMucSheet();
    var lastRow = sh.getLastRow();
    var existing = lastRow >= 2 ? sh.getRange(2, 2, lastRow - 1, 1).getValues().flat().filter(String) : [];
    var upper = name.toUpperCase();
    var dup = existing.some(function(v) { return String(v).trim().toUpperCase() === upper; });
    if (dup) return "OK";
    var col = sh.getRange(2, 2, Math.max(lastRow - 1, 0), 1).getValues().flat();
    var firstEmpty = col.indexOf('');
    var row = firstEmpty !== -1 ? firstEmpty + 2 : lastRow + 1;
    sh.getRange(row, 2).setValue(name);
    return "OK";
  } catch (e) {
    return "ERR|" + e.toString();
  }
}

// ------------------------------------------------------------
// ADMIN: OTP QUA EMAIL + PHIÊN ĐĂNG NHẬP (giao diện web cũ)
// ------------------------------------------------------------
function _isAdmin(token) {
  return !!token && !!CacheService.getScriptCache().get('adm_' + token);
}
function _requireAdmin(token) {
  if (!_isAdmin(token)) throw new Error('Phiên admin không hợp lệ hoặc đã hết hạn. Hãy đăng nhập lại.');
}
function _parseLog(v) {
  try { var a = JSON.parse(v); return Array.isArray(a) ? a : []; } catch (e) { return []; }
}

function requestAdminOtp() {
  try {
    var cache = CacheService.getScriptCache();
    if (cache.get('otp_cool')) return { ok: false, cooldown: true, error: 'Vừa gửi mã xong, vui lòng đợi 60 giây rồi gửi lại. Bạn vẫn có thể nhập mã đã nhận.' };
    var bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, Utilities.getUuid() + '|' + Date.now());
    var n = (((bytes[0] & 255) << 24) | ((bytes[1] & 255) << 16) | ((bytes[2] & 255) << 8) | (bytes[3] & 255)) >>> 0;
    var code = ('000000' + (n % 1000000)).slice(-6);
    cache.put('otp_code', code, OTP_TTL_SEC);
    cache.put('otp_tries', '0', OTP_TTL_SEC);
    cache.put('otp_cool', '1', 60);
    var who = _currentUser() || 'không rõ';
    MailApp.sendEmail({
      to: ADMIN_EMAILS.join(','),
      subject: 'Mã OTP đăng nhập Admin - Quản Lý KPI Bán Hàng',
      body: 'Mã OTP đăng nhập Admin của bạn là: ' + code + '\n\n'
          + 'Mã có hiệu lực ' + (OTP_TTL_SEC / 60) + ' phút.\n'
          + 'Người yêu cầu mã: ' + who + '\n'
          + 'Thời gian: ' + Utilities.formatDate(new Date(), "GMT+7", "HH:mm dd-MM-yyyy") + '\n\n'
          + 'Nếu không phải bạn hoặc nhân viên của bạn yêu cầu, hãy bỏ qua email này.'
    });
    return { ok: true, ttl: OTP_TTL_SEC };
  } catch (e) {
    return { ok: false, error: 'Không gửi được email OTP: ' + String(e.message || e) };
  }
}

function verifyAdminOtp(code) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(10000);
    var cache = CacheService.getScriptCache();
    var real = cache.get('otp_code');
    if (!real) return { ok: false, error: 'Mã OTP đã hết hạn hoặc chưa được gửi. Hãy bấm gửi mã mới.' };
    var tries = Number(cache.get('otp_tries') || 0) + 1;
    if (tries > OTP_MAX_TRIES) {
      cache.remove('otp_code');
      return { ok: false, error: 'Nhập sai quá ' + OTP_MAX_TRIES + ' lần. Hãy gửi mã OTP mới.' };
    }
    cache.put('otp_tries', String(tries), OTP_TTL_SEC);
    if (String(code || '').trim() !== real) {
      return { ok: false, error: 'Mã OTP không đúng. Còn ' + (OTP_MAX_TRIES - tries) + ' lần thử.' };
    }
    cache.remove('otp_code');
    var token = Utilities.getUuid() + Utilities.getUuid().replace(/-/g, '');
    cache.put('adm_' + token, _currentUser() || 'admin', ADMIN_SESSION_SEC);
    return { ok: true, token: token, ttl: ADMIN_SESSION_SEC };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  } finally {
    try { lock.releaseLock(); } catch (x) {}
  }
}

function adminLogout(token) {
  if (token) CacheService.getScriptCache().remove('adm_' + token);
  return "OK";
}

// ------------------------------------------------------------
// ADMIN: CÀI ĐẶT + QUẢN LÝ DANH MỤC
// ------------------------------------------------------------
function adminSetDelay(minutes, token) {
  try {
    _requireAdmin(token);
    var m = Number(minutes);
    if (isNaN(m) || m < 0 || m > 1440) return { ok: false, error: 'Số phút phải từ 0 đến 1440.' };
    PropertiesService.getScriptProperties().setProperty('CLOSE_DELAY_MIN', String(Math.round(m)));
    return { ok: true, minutes: Math.round(m) };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  }
}

var SCHEDULE_DEFAULT = {
  open:  { on: false, from: "06:00", to: "12:00" },
  close: { on: false, from: "18:00", to: "23:59" }
};

function _normTime(t) {
  var m = /^([01]?\d|2[0-3]):([0-5]\d)$/.exec(String(t || '').trim());
  return m ? (('0' + m[1]).slice(-2) + ':' + m[2]) : '';
}
function _timeToMin(t) {
  var m = /^(\d{2}):(\d{2})$/.exec(t || '');
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}
function _getSchedule() {
  var out = {
    open:  { on: SCHEDULE_DEFAULT.open.on,  from: SCHEDULE_DEFAULT.open.from,  to: SCHEDULE_DEFAULT.open.to },
    close: { on: SCHEDULE_DEFAULT.close.on, from: SCHEDULE_DEFAULT.close.from, to: SCHEDULE_DEFAULT.close.to }
  };
  try {
    var raw = PropertiesService.getScriptProperties().getProperty('SCHEDULE');
    var o = raw ? JSON.parse(raw) : null;
    ['open', 'close'].forEach(function(k) {
      if (o && o[k]) {
        out[k].on = !!o[k].on;
        out[k].from = _normTime(o[k].from) || out[k].from;
        out[k].to = _normTime(o[k].to) || out[k].to;
      }
    });
  } catch (e) {}
  return out;
}
function _inWindow(cfg) {
  if (!cfg || !cfg.on) return true;
  var f = _timeToMin(cfg.from), t = _timeToMin(cfg.to);
  if (f === null || t === null) return true;
  var n = _timeToMin(Utilities.formatDate(new Date(), "GMT+7", "HH:mm"));
  return f <= t ? (n >= f && n <= t) : (n >= f || n <= t);
}
function adminSetSchedule(sched, token) {
  try {
    _requireAdmin(token);
    var out = {};
    var names = { open: 'Tồn đầu', close: 'Tồn cuối' };
    var keys = ['open', 'close'];
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i], c = (sched && sched[k]) || {};
      var from = _normTime(c.from), to = _normTime(c.to);
      if (!from || !to) return { ok: false, error: 'Giờ ' + names[k] + ' không hợp lệ (định dạng HH:mm).' };
      out[k] = { on: !!c.on, from: from, to: to };
    }
    PropertiesService.getScriptProperties().setProperty('SCHEDULE', JSON.stringify(out));
    return { ok: true, schedule: _getSchedule() };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  }
}

function _renameInSheet(sh, col, oldN, newN) {
  var last = sh.getLastRow();
  if (last < 2) return;
  var rng = sh.getRange(2, col, last - 1, 1);
  var vals = rng.getValues();
  var changed = false;
  vals.forEach(function(r) {
    if (String(r[0]).trim() === oldN) { r[0] = newN; changed = true; }
  });
  if (changed) rng.setValues(vals);
}

function adminRenameCatalog(type, oldName, newName, token) {
  var lock = LockService.getScriptLock();
  try {
    _requireAdmin(token);
    lock.waitLock(30000);
    oldName = String(oldName || '').trim();
    newName = String(newName || '').trim();
    if (!oldName || !newName) return { ok: false, error: 'Tên không được để trống.' };
    var isStore = type === 'store';
    var dmCol = isStore ? 1 : 2;
    var dataCol = isStore ? 3 : 4;
    var dm = _getDanhMucSheet();
    var last = dm.getLastRow();
    var vals = last >= 2 ? dm.getRange(2, dmCol, last - 1, 1).getValues() : [];
    var idx = -1;
    for (var i = 0; i < vals.length; i++) {
      var v = String(vals[i][0]).trim();
      if (v === oldName) idx = i;
      else if (v.toUpperCase() === newName.toUpperCase()) return { ok: false, error: 'Tên "' + newName + '" đã tồn tại.' };
    }
    if (idx === -1) return { ok: false, error: 'Không tìm thấy "' + oldName + '" trong danh mục.' };
    dm.getRange(idx + 2, dmCol).setValue(newName);
    _renameInSheet(_getSheet(SHEET_SALES, SALES_HEADERS), dataCol, oldName, newName);
    _renameInSheet(_getSheet(SHEET_STOCK, STOCK_HEADERS), dataCol, oldName, newName);
    return { ok: true };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  } finally {
    try { lock.releaseLock(); } catch (x) {}
  }
}

function adminDeleteCatalog(type, name, token) {
  var lock = LockService.getScriptLock();
  try {
    _requireAdmin(token);
    lock.waitLock(30000);
    var col = type === 'store' ? 1 : 2;
    var sh = _getDanhMucSheet();
    var last = sh.getLastRow();
    if (last < 2) return { ok: true };
    var rng = sh.getRange(2, col, last - 1, 1);
    var keep = rng.getValues()
      .map(function(r) { return String(r[0]).trim(); })
      .filter(function(v) { return v && v !== String(name).trim(); });
    rng.clearContent();
    if (keep.length) sh.getRange(2, col, keep.length, 1).setValues(keep.map(function(v) { return [v]; }));
    return { ok: true };
  } catch (e) {
    return { ok: false, error: String(e.message || e) };
  } finally {
    try { lock.releaseLock(); } catch (x) {}
  }
}

// ------------------------------------------------------------
// BÁN HÀNG
// ------------------------------------------------------------
function submitSalesBatch(data) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(30000);
    var user = _currentUser();
    if (!user) return { ok: false, error: 'Không lấy được email người dùng. Hãy đăng nhập Google và triển khai web app ở chế độ "Execute as: User accessing the web app".' };
    var items = data.items || [];
    if (!items.length) return { ok: false, error: 'Chưa có dòng nào để lưu.' };

    var sh = _getSheet(SHEET_SALES, SALES_HEADERS);
    var lastRow = sh.getLastRow();

    var used = {};
    if (lastRow >= 2) {
      var vals = sh.getRange(2, 1, lastRow - 1, 8).getValues();
      vals.forEach(function(r) {
        if (_fmtDate(r[1]) === data.date && String(r[2]).trim() === String(data.store).trim()
            && String(r[6]).toUpperCase() !== 'TRUE') {
          used[String(r[3]).trim()] = true;
        }
      });
    }

    var seen = {};
    for (var i = 0; i < items.length; i++) {
      var p = String(items[i].product).trim();
      if (used[p] || seen[p]) {
        return { ok: false, error: 'Sản phẩm "' + p + '" của cửa hàng "' + data.store + '" đã được nhập cho ngày ' + data.date + ' rồi. Chưa có dòng nào được lưu.' };
      }
      seen[p] = true;
    }

    var rows = items.map(function(it, idx) {
      return [lastRow + idx, data.date, data.store, String(it.product).trim(), Number(it.qty), user, "", ""];
    });
    sh.getRange(lastRow + 1, 1, rows.length, 8).setValues(rows);
    return { ok: true, count: rows.length, used: getSalesUsed(data.date) };
  } catch (e) {
    return { ok: false, error: String(e) };
  } finally {
    try { lock.releaseLock(); } catch (x) {}
  }
}

function getUsedProducts(date, store) {
  var sh = _getSheet(SHEET_SALES, SALES_HEADERS);
  var lastRow = sh.getLastRow();
  if (lastRow < 2) return [];
  var vals = sh.getRange(2, 1, lastRow - 1, 8).getValues();
  var used = [];
  vals.forEach(function(r) {
    if (_fmtDate(r[1]) === date && String(r[2]).trim() === String(store).trim() && String(r[6]).toUpperCase() !== 'TRUE') {
      used.push(String(r[3]).trim());
    }
  });
  return used;
}

function getSalesHistory() {
  var sh = _getSheet(SHEET_SALES, SALES_HEADERS);
  var lastRow = sh.getLastRow();
  if (lastRow < 2) return [];
  var vals = sh.getRange(2, 1, lastRow - 1, 8).getValues();
  var out = [];
  vals.forEach(function(r, i) {
    var editLog = [];
    try { if (r[7]) editLog = JSON.parse(r[7]); } catch (e) {}
    out.push({
      rowIndex: i + 2,
      date: _fmtDate(r[1]),
      store: r[2], product: r[3], qty: r[4], cashier: r[5],
      isDeleted: String(r[6]).toUpperCase() === 'TRUE',
      editLog: editLog
    });
  });
  return out.reverse();
}

function updateSales(rowIndex, rec, token) {
  _requireAdmin(token);
  var sh = _getSheet(SHEET_SALES, SALES_HEADERS);
  rowIndex = Number(rowIndex);
  if (rowIndex < 2 || rowIndex > sh.getLastRow()) throw new Error('Dòng dữ liệu không tồn tại.');
  var old = sh.getRange(rowIndex, 1, 1, 8).getValues()[0];
  var log = _parseLog(old[7]);
  log.push({ time: new Date().toISOString(), by: _currentUser(), action: 'Sửa',
             before: { date: _fmtDate(old[1]), store: old[2], product: old[3], qty: old[4], cashier: old[5] } });
  sh.getRange(rowIndex, 2, 1, 5).setValues([[rec.date, String(rec.store).trim(), String(rec.product).trim(), Number(rec.qty), String(rec.cashier || '').trim()]]);
  sh.getRange(rowIndex, 8).setValue(JSON.stringify(log));
  return "OK";
}

function deleteSales(rowIndex, token) {
  _requireAdmin(token);
  var sh = _getSheet(SHEET_SALES, SALES_HEADERS);
  rowIndex = Number(rowIndex);
  if (rowIndex < 2 || rowIndex > sh.getLastRow()) throw new Error('Dòng dữ liệu không tồn tại.');
  var log = _parseLog(sh.getRange(rowIndex, 8).getValue());
  log.push({ time: new Date().toISOString(), by: _currentUser(), action: 'Xoá' });
  sh.getRange(rowIndex, 7).setValue('TRUE');
  sh.getRange(rowIndex, 8).setValue(JSON.stringify(log));
  return "OK";
}

// ------------------------------------------------------------
// TỒN KHO
// ------------------------------------------------------------
function _findStockRow(sh, date, store, product) {
  var lastRow = sh.getLastRow();
  if (lastRow < 2) return -1;
  var vals = sh.getRange(2, 1, lastRow - 1, 10).getValues();
  for (var i = 0; i < vals.length; i++) {
    var r = vals[i];
    if (_fmtDate(r[1]) === date && String(r[2]).trim() === String(store).trim()
        && String(r[3]).trim() === String(product).trim()
        && String(r[8]).toUpperCase() !== 'TRUE') {
      return i + 2;
    }
  }
  return -1;
}

function submitStock(data) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(30000);
    var user = _currentUser();
    if (!user) return { ok: false, error: 'Không lấy được email người dùng. Hãy đăng nhập Google và triển khai web app ở chế độ "Execute as: User accessing the web app".' };
    var sh = _getSheet(SHEET_STOCK, STOCK_HEADERS);
    var row = _findStockRow(sh, data.date, data.store, data.product);

    if (row === -1) {
      var lastRow = sh.getLastRow();
      sh.appendRow([lastRow, data.date, data.store, data.product, Number(data.qty), "", user, "", "", "", Date.now()]);
      return { ok: true, mode: 'open' };
    }

    var tonCuoiVal = sh.getRange(row, 6).getValue();
    if (tonCuoiVal === "" || tonCuoiVal === null || tonCuoiVal === undefined) {
      var oa0 = Number(sh.getRange(row, 11).getValue());
      if (oa0 && oa0 + CLOSE_DELAY_MS > Date.now()) {
        return { ok: false, error: 'Chưa đến giờ nhập Tồn cuối. Mở lúc ' + _fmtTime(oa0 + CLOSE_DELAY_MS) + '.' };
      }
      sh.getRange(row, 6).setValue(Number(data.qty));
      sh.getRange(row, 8).setValue(user);
      return { ok: true, mode: 'close' };
    }

    return { ok: false, error: 'Ngày này đã nhập đủ Tồn đầu & Tồn cuối cho cửa hàng/sản phẩm này rồi.' };
  } catch (e) {
    return { ok: false, error: String(e) };
  } finally {
    try { lock.releaseLock(); } catch (x) {}
  }
}

function submitStockBatch(data) {
  var lock = LockService.getScriptLock();
  try {
    lock.waitLock(30000);
    var user = _currentUser();
    if (!user) return { ok: false, error: 'Không lấy được email người dùng. Hãy đăng nhập Google và triển khai web app ở chế độ "Execute as: User accessing the web app".' };
    var items = data.items || [];
    if (!items.length) return { ok: false, error: 'Chưa có dòng nào để lưu.' };
    var isAdm = _isAdmin(data.adminToken);

    var sh = _getSheet(SHEET_STOCK, STOCK_HEADERS);
    var lastRow = sh.getLastRow();
    var vals = _readRows(sh, 11);
    var store = String(data.store).trim();
    var me = user.toLowerCase();
    var today = Utilities.formatDate(new Date(), "GMT+7", "yyyy-MM-dd");

    var map = {}, startedCount = 0, myLockUntil = 0;
    vals.forEach(function(r, i) {
      if (String(r[8]).toUpperCase() === 'TRUE') return;
      var d = _fmtDate(r[1]);
      var closed = !(r[5] === "" || r[5] === null || r[5] === undefined);
      if (d === data.date && String(r[2]).trim() === store) {
        map[String(r[3]).trim()] = { row: i + 2, closed: closed, openAt: Number(r[10]) || 0 };
        startedCount++;
      }
      if (d === today && String(r[6]).trim().toLowerCase() === me && !closed && r[10]) {
        var u = Number(r[10]) + CLOSE_DELAY_MS;
        if (u > myLockUntil) myLockUntil = u;
      }
    });

    var now = Date.now();
    var plan = [], seen = {};
    var sch = _getSchedule();
    for (var i = 0; i < items.length; i++) {
      var p = String(items[i].product).trim();
      if (seen[p]) return { ok: false, error: 'Sản phẩm "' + p + '" bị trùng trong danh sách. Chưa có dòng nào được lưu.' };
      seen[p] = true;
      var ex = map[p];
      if (!ex) {
        if (items[i].mode === 'close') return { ok: false, error: 'Sản phẩm "' + p + '" chưa có Tồn đầu nên chưa thể nhập Tồn cuối. Chưa có dòng nào được lưu.' };
        if (!isAdm && myLockUntil > now) return { ok: false, error: 'Bạn đã nhập Tồn đầu hôm nay. Chức năng nhập sẽ mở lại lúc ' + _fmtTime(myLockUntil) + '. Chưa có dòng nào được lưu.' };
        if (!isAdm && startedCount > 0) return { ok: false, error: 'Cửa hàng "' + data.store + '" đã nhập Tồn đầu ngày ' + data.date + ' rồi, mỗi ngày chỉ nhập Tồn đầu một lần. Chưa có dòng nào được lưu.' };
        if (!isAdm && !_inWindow(sch.open)) return { ok: false, error: 'Ngoài giờ nhập Tồn đầu (mở từ ' + sch.open.from + ' đến ' + sch.open.to + '). Chưa có dòng nào được lưu.' };
        plan.push({ type: 'open', product: p, qty: Number(items[i].qty) });
      } else {
        if (items[i].mode === 'open') return { ok: false, error: 'Sản phẩm "' + p + '" đã có Tồn đầu ngày ' + data.date + ' rồi. Chưa có dòng nào được lưu.' };
        if (ex.closed) return { ok: false, error: 'Sản phẩm "' + p + '" đã nhập đủ Tồn đầu & Tồn cuối ngày ' + data.date + ' rồi. Chưa có dòng nào được lưu.' };
        if (!isAdm && ex.openAt && ex.openAt + CLOSE_DELAY_MS > now) {
          return { ok: false, error: 'Sản phẩm "' + p + '" chưa đến giờ nhập Tồn cuối (mở lúc ' + _fmtTime(ex.openAt + CLOSE_DELAY_MS) + '). Chưa có dòng nào được lưu.' };
        }
        if (!isAdm && !_inWindow(sch.close)) return { ok: false, error: 'Ngoài giờ nhập Tồn cuối (mở từ ' + sch.close.from + ' đến ' + sch.close.to + '). Chưa có dòng nào được lưu.' };
        plan.push({ type: 'close', row: ex.row, qty: Number(items[i].qty) });
      }
    }

    var newRows = [];
    plan.forEach(function(x) {
      if (x.type === 'open') {
        newRows.push([lastRow + newRows.length, data.date, data.store, x.product, x.qty, "", user, "", "", "", now]);
      } else {
        sh.getRange(x.row, 6).setValue(x.qty);
        sh.getRange(x.row, 8).setValue(user);
      }
    });
    if (newRows.length) sh.getRange(lastRow + 1, 1, newRows.length, 11).setValues(newRows);

    return { ok: true, count: plan.length, bundle: getStockBundle(data.date) };
  } catch (e) {
    return { ok: false, error: String(e) };
  } finally {
    try { lock.releaseLock(); } catch (x) {}
  }
}

function _readRows(sh, cols) {
  var lastRow = sh.getLastRow();
  return lastRow >= 2 ? sh.getRange(2, 1, lastRow - 1, cols).getValues() : [];
}

function getStockBundle(date) {
  var sh = _getSheet(SHEET_STOCK, STOCK_HEADERS);
  var vals = _readRows(sh, 11);
  var user = _currentUser();
  var me = user.toLowerCase();
  var today = Utilities.formatDate(new Date(), "GMT+7", "yyyy-MM-dd");
  var overview = {}, until = 0, now = Date.now();
  vals.forEach(function(r) {
    if (String(r[8]).toUpperCase() === 'TRUE') return;
    var d = _fmtDate(r[1]);
    var closed = !(r[5] === "" || r[5] === null || r[5] === undefined);
    if (d === date) {
      var st = String(r[2]).trim(), p = String(r[3]).trim();
      var o = overview[st] || (overview[st] = { started: [], full: [], openAt: {} });
      o.started.push(p);
      if (r[10]) o.openAt[p] = Number(r[10]);
      if (closed) o.full.push(p);
    }
    if (user && d === today && String(r[6]).trim().toLowerCase() === me && !closed && r[10]) {
      var u = Number(r[10]) + CLOSE_DELAY_MS;
      if (u > until) until = u;
    }
  });
  return { now: now, delayMs: CLOSE_DELAY_MS, user: user, until: until > now ? until : 0, overview: overview, schedule: _getSchedule() };
}

function getSalesUsed(date) {
  var sh = _getSheet(SHEET_SALES, SALES_HEADERS);
  var vals = _readRows(sh, 8);
  var map = {};
  vals.forEach(function(r) {
    if (String(r[6]).toUpperCase() === 'TRUE') return;
    if (_fmtDate(r[1]) !== date) return;
    var st = String(r[2]).trim();
    (map[st] || (map[st] = [])).push(String(r[3]).trim());
  });
  return map;
}

function getInitData(date) {
  return {
    formData: getFormData(),
    user: _currentUser(),
    stock: getStockBundle(date),
    salesUsed: getSalesUsed(date),
    adminEmails: ADMIN_EMAILS
  };
}

function getMyStockLock() {
  var user = _currentUser();
  var now = Date.now();
  var until = 0;
  if (user) {
    var sh = _getSheet(SHEET_STOCK, STOCK_HEADERS);
    var lastRow = sh.getLastRow();
    if (lastRow >= 2) {
      var today = Utilities.formatDate(new Date(), "GMT+7", "yyyy-MM-dd");
      var me = user.toLowerCase();
      var vals = sh.getRange(2, 1, lastRow - 1, 11).getValues();
      vals.forEach(function(r) {
        if (_fmtDate(r[1]) === today
            && String(r[6]).trim().toLowerCase() === me
            && String(r[8]).toUpperCase() !== 'TRUE'
            && (r[5] === "" || r[5] === null || r[5] === undefined)
            && r[10]) {
          var u = Number(r[10]) + CLOSE_DELAY_MS;
          if (u > until) until = u;
        }
      });
    }
  }
  return { user: user, now: now, until: until > now ? until : 0, delayMs: CLOSE_DELAY_MS };
}

function getStockStatus(date, store) {
  var sh = _getSheet(SHEET_STOCK, STOCK_HEADERS);
  var lastRow = sh.getLastRow();
  var started = [], full = [], openAt = {};
  if (lastRow >= 2) {
    var vals = sh.getRange(2, 1, lastRow - 1, 11).getValues();
    vals.forEach(function(r) {
      if (_fmtDate(r[1]) === date && String(r[2]).trim() === String(store).trim() && String(r[8]).toUpperCase() !== 'TRUE') {
        var p = String(r[3]).trim();
        started.push(p);
        if (r[10]) openAt[p] = Number(r[10]);
        if (!(r[5] === "" || r[5] === null || r[5] === undefined)) full.push(p);
      }
    });
  }
  return { started: started, full: full, openAt: openAt, now: Date.now(), delayMs: CLOSE_DELAY_MS };
}

function getStockMode(date, store, product) {
  var sh = _getSheet(SHEET_STOCK, STOCK_HEADERS);
  var row = _findStockRow(sh, date, store, product);
  if (row === -1) return { mode: 'open', tonDau: null };
  var tonCuoiVal = sh.getRange(row, 6).getValue();
  var tonDauVal = sh.getRange(row, 5).getValue();
  if (tonCuoiVal === "" || tonCuoiVal === null || tonCuoiVal === undefined) {
    return { mode: 'close', tonDau: tonDauVal };
  }
  return { mode: 'full', tonDau: tonDauVal, tonCuoi: tonCuoiVal };
}

function getStockHistory() {
  var sh = _getSheet(SHEET_STOCK, STOCK_HEADERS);
  var lastRow = sh.getLastRow();
  if (lastRow < 2) return [];
  var vals = sh.getRange(2, 1, lastRow - 1, 10).getValues();
  var out = [];
  vals.forEach(function(r, i) {
    var editLog = [];
    try { if (r[9]) editLog = JSON.parse(r[9]); } catch (e) {}
    out.push({
      rowIndex: i + 2,
      date: _fmtDate(r[1]),
      store: r[2], product: r[3],
      tonDau: r[4], tonCuoi: r[5],
      cashierDau: r[6], cashierCuoi: r[7],
      isDeleted: String(r[8]).toUpperCase() === 'TRUE',
      editLog: editLog
    });
  });
  return out.reverse();
}

function getHistoryBundle() {
  return { sales: getSalesHistory(), stock: getStockHistory() };
}

function updateStock(rowIndex, rec, token) {
  _requireAdmin(token);
  var sh = _getSheet(SHEET_STOCK, STOCK_HEADERS);
  rowIndex = Number(rowIndex);
  if (rowIndex < 2 || rowIndex > sh.getLastRow()) throw new Error('Dòng dữ liệu không tồn tại.');
  var old = sh.getRange(rowIndex, 1, 1, 10).getValues()[0];
  var log = _parseLog(old[9]);
  log.push({ time: new Date().toISOString(), by: _currentUser(), action: 'Sửa',
             before: { date: _fmtDate(old[1]), store: old[2], product: old[3], tonDau: old[4], tonCuoi: old[5], cashierDau: old[6], cashierCuoi: old[7] } });
  var num = function(v) { return (v === '' || v === null || v === undefined) ? '' : Number(v); };
  sh.getRange(rowIndex, 2, 1, 7).setValues([[rec.date, String(rec.store).trim(), String(rec.product).trim(),
    num(rec.tonDau), num(rec.tonCuoi), String(rec.cashierDau || '').trim(), String(rec.cashierCuoi || '').trim()]]);
  sh.getRange(rowIndex, 10).setValue(JSON.stringify(log));
  return "OK";
}

function deleteStock(rowIndex, token) {
  _requireAdmin(token);
  var sh = _getSheet(SHEET_STOCK, STOCK_HEADERS);
  rowIndex = Number(rowIndex);
  if (rowIndex < 2 || rowIndex > sh.getLastRow()) throw new Error('Dòng dữ liệu không tồn tại.');
  var log = _parseLog(sh.getRange(rowIndex, 10).getValue());
  log.push({ time: new Date().toISOString(), by: _currentUser(), action: 'Xoá' });
  sh.getRange(rowIndex, 9).setValue('TRUE');
  sh.getRange(rowIndex, 10).setValue(JSON.stringify(log));
  return "OK";
}


// ============================================================
//  PHẦN THÊM: API JSON cho app Android native (Google Sign-In)
// ============================================================

// Web Client ID lấy từ Google Cloud Console (đã điền sẵn theo thông tin bạn cung cấp)
var WEB_CLIENT_ID = "136393256935-ccl6rjnubbvo2e2gouf1j5pc4jnvg5sr.apps.googleusercontent.com";

// Danh sách email được phép dùng app (để trống [] = cho phép mọi Gmail có token hợp lệ đăng nhập được).
// Khuyến nghị: nếu muốn giới hạn chặt hơn nữa, điền danh sách email nhân viên vào đây.
var ALLOWED_EMAILS = []; // ví dụ: ["nv1@gmail.com", "nv2@gmail.com"]

// Email được quyền ADMIN trong app native (mặc định lấy theo đúng danh sách ADMIN_EMAILS ở trên).
// Muốn đổi/thêm người, sửa trực tiếp dòng này.
var ALLOWED_ADMIN_EMAILS = ["itviettel098@gmail.com", "itviettel094@gmail.com", "nguyenngoctaiaice@gmail.com"];

// Biến tạm giữ email người gọi API trong 1 lần thực thi (không lưu lại giữa các lần gọi)
var _apiUserEmail = "";

// Ghi đè _currentUser(): nếu có email từ API (đã xác minh token) thì ưu tiên dùng,
// nếu không có (trường hợp gọi từ giao diện HTML cũ qua google.script.run) thì dùng Session như cũ.
function _currentUser() {
  if (_apiUserEmail) return _apiUserEmail;
  try { return String(Session.getActiveUser().getEmail() || '').trim(); } catch (e) { return ''; }
}
function getCurrentUser() {
  return _currentUser();
}

// Xác minh Google ID Token do app Android gửi lên. Trả về email nếu hợp lệ, '' nếu không.
function _verifyIdToken(idToken) {
  if (!idToken) return '';
  try {
    var res = UrlFetchApp.fetch(
      'https://oauth2.googleapis.com/tokeninfo?id_token=' + encodeURIComponent(idToken),
      { muteHttpExceptions: true }
    );
    if (res.getResponseCode() !== 200) return '';
    var data = JSON.parse(res.getContentText());
    if (String(data.aud) !== String(WEB_CLIENT_ID)) return ''; // token không phải cấp cho app của mình
    if (Number(data.exp) < Math.floor(Date.now() / 1000)) return ''; // hết hạn
    var email = String(data.email || '').trim().toLowerCase();
    if (ALLOWED_EMAILS.length && ALLOWED_EMAILS.indexOf(email) === -1) return ''; // không nằm trong whitelist
    return email;
  } catch (e) {
    return '';
  }
}

function _jsonOut(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}

function _isAdminEmail(email) {
  return !!email && ALLOWED_ADMIN_EMAILS.indexOf(String(email).toLowerCase()) !== -1;
}

function _requireNativeAdmin(email) {
  if (!_isAdminEmail(email)) throw new Error("Bạn không có quyền admin.");
  return _grantNativeAdminToken(email);
}
function _grantNativeAdminToken(email) {
  var token = Utilities.getUuid() + Utilities.getUuid().replace(/-/g, '');
  CacheService.getScriptCache().put('adm_' + token, email, 60);
  return token;
}

// Các action KHÔNG cần đăng nhập Google
var PUBLIC_ACTIONS = ["ping"];

// doPost: API chính cho app Android. Body JSON dạng:
// { action: "getInitData", idToken: "....", params: { date: "2026-11-16" } }
function doPost(e) {
  var result;
  try {
    var body = JSON.parse(e.postData.contents || "{}");
    var action = body.action;
    var params = body.params || {};
    var idToken = body.idToken || "";

    var email = "";
    if (PUBLIC_ACTIONS.indexOf(action) === -1) {
      email = _verifyIdToken(idToken);
      if (!email) {
        return _jsonOut({ ok: false, error: "Phiên đăng nhập không hợp lệ hoặc đã hết hạn. Vui lòng đăng nhập lại." });
      }
    }
    _apiUserEmail = email;

    switch (action) {
      case "ping":
        result = { ok: true, pong: true, time: Date.now() };
        break;

      case "getInitData":
        result = { ok: true, data: getInitData(params.date) };
        break;

      case "submitSalesBatch":
        result = submitSalesBatch(params);
        break;

      case "getSalesUsed":
        result = { ok: true, data: getSalesUsed(params.date) };
        break;

      case "submitStockBatch":
        params.adminToken = _isAdminEmail(email) ? _grantNativeAdminToken(email) : "";
        result = submitStockBatch(params);
        break;

      case "getStockBundle":
        result = { ok: true, data: getStockBundle(params.date) };
        break;

      case "getHistoryBundle":
        result = { ok: true, data: getHistoryBundle() };
        break;

      case "getStockHistory":
        result = { ok: true, data: getStockHistory() };
        break;

      case "addNewStore":
        result = { ok: true, data: addNewStore(params.name) };
        break;

      case "addNewProduct":
        result = { ok: true, data: addNewProduct(params.name) };
        break;

      case "updateSales": {
        var t1 = _requireNativeAdmin(email);
        result = { ok: true, data: updateSales(params.rowIndex, params.rec, t1) };
        break;
      }
      case "deleteSales": {
        var t2 = _requireNativeAdmin(email);
        result = { ok: true, data: deleteSales(params.rowIndex, t2) };
        break;
      }
      case "updateStock": {
        var t3 = _requireNativeAdmin(email);
        result = { ok: true, data: updateStock(params.rowIndex, params.rec, t3) };
        break;
      }
      case "deleteStock": {
        var t4 = _requireNativeAdmin(email);
        result = { ok: true, data: deleteStock(params.rowIndex, t4) };
        break;
      }
      case "adminSetDelay": {
        var t5 = _requireNativeAdmin(email);
        result = adminSetDelay(params.minutes, t5);
        break;
      }
      case "adminSetSchedule": {
        var t6 = _requireNativeAdmin(email);
        result = adminSetSchedule(params.sched, t6);
        break;
      }
      case "adminRenameCatalog": {
        var t7 = _requireNativeAdmin(email);
        result = adminRenameCatalog(params.type, params.oldName, params.newName, t7);
        break;
      }
      case "adminDeleteCatalog": {
        var t8 = _requireNativeAdmin(email);
        result = adminDeleteCatalog(params.type, params.name, t8);
        break;
      }
      case "isAdmin":
        result = { ok: true, isAdmin: _isAdminEmail(email), email: email };
        break;

      default:
        result = { ok: false, error: "Action không tồn tại: " + action };
    }
  } catch (err) {
    result = { ok: false, error: String(err && err.message || err) };
  } finally {
    _apiUserEmail = "";
  }
  return _jsonOut(result);
}

function doGet(e) {
  // Giữ nguyên hành vi cũ cho người vẫn mở bằng trình duyệt (giao diện HTML cũ)
  if (e && e.parameter && e.parameter.api === "1") {
    return _jsonOut({ ok: true, msg: "API đang chạy. Dùng POST với {action, idToken, params}." });
  }
  return HtmlService.createHtmlOutputFromFile('index')
      .setTitle('Quản Lý KPI Bán Hàng')
      .setXFrameOptionsMode(HtmlService.XFrameOptionsMode.ALLOWALL)
      .addMetaTag('viewport', 'width=device-width, initial-scale=1');
}
