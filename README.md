# MapsBridge

MapsBridge kết nối điện thoại **Chính** với thiết bị **Phụ** qua Bluetooth Classic RFCOMM. Máy Chính chia sẻ địa điểm, thông báo và các thao tác gốc của thông báo sang Máy Phụ.

## Tính năng

- **Chia sẻ đường đi một chạm:** Trong Google Maps hoặc ứng dụng bản đồ, chọn Chia sẻ → **Dẫn đường trên Máy Phụ**. Máy Phụ giải mã liên kết, mở Google Maps ở chế độ dẫn đường xe máy (`mode=l`) và đánh thức màn hình nếu đang ngủ.
- **Thông báo trên Máy Phụ:** Chuyển tiếp thông báo từ ứng dụng người dùng, kèm tên ứng dụng, biểu tượng và các nút hành động gốc mà thông báo cung cấp, như xác nhận hoặc đánh dấu đã đọc. Nút trả lời bằng văn bản tự do (`RemoteInput`) hiện không được chuyển tiếp.
- **Kết nối tự phục hồi:** RFCOMM tự nghe lại khi mất kết nối; heartbeat giúp phát hiện đường truyền bị treo. Dịch vụ Máy Phụ tự khởi chạy khi mở launcher và sau khi khởi động lại thiết bị.
- **Launcher Máy Phụ:** Có thể đặt MapsBridge làm màn hình chính; launcher có dock ứng dụng tùy chỉnh, ngăn ứng dụng, hình nền chọn riêng, đồng hồ, ngày dương và lịch âm Việt Nam.
- **Hạn chế sáng màn hình chờ:** Launcher không khóa màn hình luôn bật. Màn hình có thể tắt theo thời gian chờ; CPU vẫn được giữ hoạt động cho Bluetooth. Thông báo không tự đánh thức màn hình, còn tuyến đường mới có thể đánh thức để mở Maps.

Hai ứng dụng dùng chung UUID RFCOMM `fa87c0d0-afac-11de-8a39-0800200c9a66`, trao đổi JSON UTF-8 theo từng dòng. Ứng dụng không cần Android Auto hay Google Play Services để truyền dữ liệu.

## Khả năng tương thích

| APK | Module | minSdk | Android tối thiểu | targetSdk | compileSdk | versionName | ABI |
|---|---|---:|---|---:|---:|---|---|
| MapsBridge Chính | `:companion` | 26 | Android 8.0 Oreo | 34 | 35 | `1.0` | Universal: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` |
| MapsBridge Phụ | `:headunit` | 26 | Android 8.0 Oreo | 34 | 35 | `1.0` | Universal: `armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64` |

Cả hai APK hiện không chứa thư viện native theo ABI; Android chọn runtime phù hợp với thiết bị. `targetSdk` là mức API ứng dụng khai báo mục tiêu, còn `compileSdk` là phiên bản SDK dùng khi biên dịch, không phải mức Android tối thiểu.

## Cài đặt nhanh

### Máy Chính

1. Tải và cài `MapsBridge-Chinh.apk`.
2. Bật Bluetooth và ghép đôi với Máy Phụ trong Cài đặt Android.
3. Mở MapsBridge Chính, chọn thiết bị Phụ đã ghép đôi và cấp quyền **Thiết bị ở gần** nếu Android yêu cầu.
4. Nếu muốn nhận thông báo trên Máy Phụ, mở **Quyền truy cập thông báo** trong ứng dụng và bật MapsBridge.
5. Trong Maps hoặc ứng dụng bản đồ, nhấn **Chia sẻ** rồi chọn **Dẫn đường trên Máy Phụ**.

### Máy Phụ

1. Tải và cài `MapsBridge-Phu.apk`, sau đó bật Bluetooth và ghép đôi với Máy Chính.
2. Mở MapsBridge Phụ, cấp quyền **Thiết bị ở gần** và **Thông báo** nếu hệ thống yêu cầu.
3. Cấp quyền **Hiển thị trên ứng dụng khác** để hiện banner thông báo. Quyền này không làm sáng màn hình khi thông báo đến.
4. Muốn dùng giao diện launcher, chọn MapsBridge làm ứng dụng **Màn hình chính**. Nhấn giữ trạng thái ở góc trái để mở tùy chọn; nhấn giữ một biểu tượng dock để đổi ứng dụng.

Cho phép cài đặt ứng dụng từ trình duyệt hoặc trình quản lý tệp nếu Android chặn APK tải ngoài Play Store. Sau khi thiết lập, giữ Bluetooth bật trên cả hai thiết bị.

## Tải APK và build

Các APK mới nhất được đính kèm trong [GitHub Releases](https://github.com/tuskudo2375/MapsBridge/releases). GitHub Actions cũng build hai APK debug khi có commit lên `main`; artifact có tên `MapsBridge-APKs`.

Build thủ công với JDK 17, Android SDK platform 35, build tools 35.0.0 và Gradle 8.7:

```sh
gradle :companion:assembleDebug :headunit:assembleDebug
```
