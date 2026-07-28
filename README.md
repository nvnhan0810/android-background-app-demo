# TG Ledger Demo (Android)

App **học Kotlin / Android**: nhận tin Telegram bot bằng **long polling** trong *Foreground Service*, bóc tách mã + khối lượng, ghi sổ cái local (Room), hỗ trợ **sửa tin**, tất toán nhanh, xuất/nhập backup.

> Đây là demo học — không phải app kế toán production.

## Tính năng chính

| Hạng mục | Mô tả |
|----------|--------|
| Telegram long-poll | `getUpdates` trong Foreground Service (`dataSync`) — **không cần webhook** |
| Auto-reply | Sau khi ghi sổ, bot trả `OK \| …` / `EDIT \| …` |
| Parser tin | Cú pháp demo: `A12 5`, `A12=5`, `A12:3,5` (nhiều dòng = nhiều mã) |
| Sửa tin | `edited_message` → trừ số liệu cũ theo `message_id`, cộng số mới |
| Hàng đợi ghi | Single-writer (`LedgerQueue`) tránh xung đột khi tin dồn |
| UI sổ cái | Tổng theo mã, tỷ lệ giá ×1.0/1.2/1.5, tất toán vị 0–4 |
| Learning log | Trang riêng (`LearningLogActivity`) — scroll dễ, giữ lâu để copy |
| Keep-alive | Boot restore + bỏ tối ưu pin (OEM) |
| Backup | Xuất / nhập JSON local (SAF) |

Viber **chưa** làm (Bot Viber bắt buộc webhook HTTPS).

## Yêu cầu

- Android Studio Ladybug+ (hoặc tương thích)
- JDK 17
- Android SDK 35
- Máy/emulator API 26+
- Bot token từ [@BotFather](https://t.me/BotFather)

## Cài & chạy

1. Mở thư mục này trong Android Studio → Gradle sync  
2. Run lên máy/emulator  
3. Hoặc tải APK debug từ agent builds (nếu đã publish):  
   `https://agent.nvnhan0810.com/builds/android-background-app-demo/`

## Cách dùng nhanh

1. Paste **bot token** → **Lưu + kiểm tra** (phải thấy `Token OK — bot @…`)  
   - **HTTP 401** = token sai / hết hạn / dính khoảng trắng khi paste  
2. **Bật nghe** (cho phép notification) — nên bấm **Bỏ tối ưu pin**  
3. Nhắn bot, ví dụ:
   ```text
   A1 1
   A5 2
   ```
4. Bot reply xác nhận; màn sổ cái cập nhật tổng + nhật ký tin  
5. Sửa tin trên Telegram → app đảo số cũ / cộng số mới  
6. Chọn tỷ lệ giá → xem ước giá; bấm **0–4** để tất toán “vị” cho khách vừa nhắn  
7. **Learning log** (nút góc trên) xem trace kỹ thuật  

### Cú pháp tin (demo)

```text
CODE QTY
CODE=QTY
CODE:QTY
```

- `CODE`: chữ/số/`_`/`-`  
- `QTY`: số, thập phân dùng `.` hoặc `,`  
- Dòng không khớp → lưu nhật ký nhưng **không** cộng sổ (`SKIP`)

### Tất toán / cân bảng (nghiệp vụ demo)

- Mỗi tin hợp lệ cộng `qty` theo mã và vào dư khách  
- **Tỷ lệ giá** chỉ nhân để *ước giá* hiển thị  
- Nút **0–4** = tất toán N “vị” (ghi `SETTLEMENT` `qtyDelta = -N`) cho **khách đang chọn**  
- **Chênh lệch cân bảng** = `SUM(qtyDelta)` toàn sổ (nhập − đảo edit − tất toán)

## Kiến trúc ngắn

```text
Telegram → FGS long-poll (getUpdates)
        → LedgerQueue (1 thread)
        → Room (inbound_messages + ledger_entries)
        → Broadcast → MainActivity refresh
        → sendMessage auto-reply
```

| Thành phần | Vai trò |
|------------|---------|
| `DemoForegroundService` | Long-poll + notification + WakeLock |
| `TelegramApi` / `TelegramConfig` | HTTP Bot API, token, offset, ratio |
| `MessageParser` | Bóc `CODE` + `qty` |
| `LedgerProcessor` / `LedgerQueue` | Nghiệp vụ sổ + hàng đợi |
| `MainActivity` | UI sổ cái |
| `LearningLogActivity` | Log học / debug on-screen |
| `BootReceiver` + `KeepAliveRestorer` | Bật lại sau reboot |

## Project

| Item | Value |
|------|--------|
| Package | `com.nvnhan0810.backgrounddemo` |
| ApplicationId | `com.nvnhan0810.backgrounddemo` |
| Version | 1.2 (versionCode 3) |
| Min SDK | 26 |
| Target / Compile SDK | 35 |
| AGP | 8.7.2 |
| Kotlin | 2.0.21 |
| Gradle | 8.9 |

## Lưu ý khi share bot với hệ thống khác

Chỉ **một** consumer nên dùng `getUpdates` (hoặc webhook) cho cùng một bot.  
Nếu agent/server cũng poll cùng bot → hai bên tranh update. Demo: tạm tắt poll/webhook bên kia, hoặc dùng bot riêng cho ledger.

## Tài liệu

- [Telegram Bot API — getUpdates](https://core.telegram.org/bots/api#getupdates)  
- [Foreground services](https://developer.android.com/develop/background-work/services/fgs)  
- [Room](https://developer.android.com/training/data-storage/room)  
- [WorkManager](https://developer.android.com/topic/libraries/architecture/workmanager) (vẫn có trong project, UI demo đã ẩn)

## License / mục đích

Learning demo by nvnhan0810.
