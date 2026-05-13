package com.fixcraft.yume.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import com.fixcraft.yume.data.AppLanguage
import com.fixcraft.yume.data.ConnectionTechnique
import com.fixcraft.yume.data.LogLanguage
import com.fixcraft.yume.data.PerformanceProfile
import com.fixcraft.yume.data.ResolvedAppLanguage
import com.fixcraft.yume.data.SecureMaterialType
import com.fixcraft.yume.data.ThemeColorMode
import com.fixcraft.yume.data.canonicalLabel
import com.fixcraft.yume.vpn.VpnPhase
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

private typealias TranslationTable = Map<String, String>

private fun translationTableOf(vararg entries: Pair<String, String>): TranslationTable {
    val table = LinkedHashMap<String, String>(entries.size)
    for ((key, value) in entries) {
        check(table.put(key, value) == null) { "Duplicate translation key: $key" }
    }
    return table
}

private val ukrainianTranslations = translationTableOf(
    "Back" to "Назад",
    "Home" to "Головна",
    "Servers" to "Сервери",
    "Security" to "Безпека",
    "Tools" to "Інструменти",
    "Settings" to "Налаштування",
    "Diagnostics" to "Діагностика",
    "Language" to "Мова",
    "Log language" to "Мова журналу",
    "Maintained" to "Підтримувані",
    "Auto-translated" to "Автопереклад",
    "Close" to "Закрити",
    "App language" to "Мова застосунку",
    "Log (Beta)" to "Журнал (бета)",
    "Unavailable in this build." to "Недоступно в цій збірці.",
    "Stable Android path through tun2socks with TCP and UDP support." to "Стабільний Android-шлях через tun2socks із підтримкою TCP та UDP.",
    "Direct Yume TCP and UDP path. Fixed tuning. IPv6 stays fail-closed." to "Прямий Yume TCP- та UDP-шлях. Фіксоване налаштування. IPv6 залишається у режимі fail-closed.",
    "Original" to "Оригінал",
    "Follows your phone" to "Як у телефоні",
    "Follow app" to "Як у застосунку",
    "On-device translation" to "Переклад на пристрої",
    "Auto" to "Авто",
    "Native" to "Рідна",
    "Downloaded" to "Завантажено",
    "Remove download" to "Видалити завантаження",
    "Preparing language" to "Підготовка мови",
    "Downloading language data" to "Завантаження мовних даних",
    "Translating the app" to "Переклад застосунку",
    "Working" to "Виконується",
    "Connection" to "Підключення",
    "Profile" to "Профіль",
    "Fixed native tuning" to "Фіксоване нативне налаштування",
    "Native direct uses fixed tuning. Performance profiles apply only to Tun2Socks stable." to "Native direct використовує фіксоване налаштування. Профілі продуктивності застосовуються лише до Tun2Socks stable.",
    "Theme" to "Тема",
    "Advanced" to "Додатково",
    "App data" to "Дані застосунку",
    "Advanced mode" to "Розширений режим",
    "Show developer and power-user tools in the app." to "Показувати в застосунку інструменти для розробників і досвідчених користувачів.",
    "Advanced mode on" to "Розширений режим увімкнено",
    "Advanced mode off" to "Розширений режим вимкнено",
    "AMOLED black" to "AMOLED чорний",
    "Use true black in dark mode." to "Використовувати справжній чорний у темній темі.",
    "Protection" to "Захист",
    "Android secure" to "Захищено Android",
    "Password required" to "Потрібен пароль",
    "Set password" to "Встановити пароль",
    "Change password" to "Змінити пароль",
    "Remove password" to "Прибрати пароль",
    "Unlock app data" to "Розблокувати дані застосунку",
    "Unlock auth key" to "Розблокувати ключ авторизації",
    "Password" to "Пароль",
    "Current password" to "Поточний пароль",
    "New password" to "Новий пароль",
    "Confirm password" to "Підтвердьте пароль",
    "Unlock" to "Розблокувати",
    "Current password required" to "Потрібен поточний пароль",
    "New password required" to "Потрібен новий пароль",
    "Use at least 6 characters" to "Використовуйте щонайменше 6 символів",
    "Passwords do not match" to "Паролі не збігаються",
    "App data password enabled" to "Пароль для даних застосунку увімкнено",
    "App data password changed" to "Пароль для даних застосунку змінено",
    "App data password removed" to "Пароль для даних застосунку видалено",
    "Couldn't enable the app data password." to "Не вдалося увімкнути пароль для даних застосунку.",
    "Couldn't change the app data password." to "Не вдалося змінити пароль для даних застосунку.",
    "Couldn't remove the app data password." to "Не вдалося прибрати пароль для даних застосунку.",
    "Couldn't unlock this auth key. Check the password." to "Не вдалося розблокувати цей ключ авторизації. Перевірте пароль.",
    "Device" to "Пристрій",
    "About" to "Про застосунок",
    "Credits" to "Подяки",
    "Text tools" to "Текстові інструменти",
    "Input" to "Ввід",
    "Output" to "Вивід",
    "Input required" to "Потрібен ввід",
    "Operation failed" to "Операція не вдалася",
    "Encrypt" to "Зашифрувати",
    "Decrypt" to "Розшифрувати",
    "Clear" to "Очистити",
    "System" to "Система",
    "Author" to "Автор",
    "Company" to "Компанія",
    "Credits written by" to "Текст подяк",
    "Recognition" to "Про визнання",
    "From F1xGOD" to "Від F1xGOD",
    "Battery settings" to "Налаштування батареї",
    "Notification settings" to "Налаштування сповіщень",
    "Notifications" to "Сповіщення",
    "Battery optimization" to "Оптимізація батареї",
    "Dynamic colors" to "Динамічні кольори",
    "Allowed" to "Дозволено",
    "Permission required" to "Потрібен дозвіл",
    "Optimized" to "Оптимізується",
    "Unrestricted" to "Без обмежень",
    "Enabled" to "Увімкнено",
    "Disabled" to "Вимкнено",
    "Available" to "Доступно",
    "Unavailable on this Android version" to "Недоступно на цій версії Android",
    "Pick, edit, or remove a saved gateway." to "Виберіть, змініть або видаліть збережений шлюз.",
    "Add server" to "Додати сервер",
    "Your Yume tunnel" to "Ваш тунель Yume",
    "Set up Yume VPN" to "Налаштувати Yume VPN",
    "No servers configured" to "Сервери не налаштовано",
    "Add your first gateway" to "Додайте перший шлюз",
    "Trust & auth" to "Довіра й авторизація",
    "CA and auth keys." to "CA та ключі авторизації.",
    "Selected server" to "Вибраний сервер",
    "Anonym CA" to "Анонімний CA",
    "Auth key" to "Ключ авторизації",
    "Missing" to "Відсутній",
    "Not assigned" to "Не призначено",
    "Paste Anonym CA" to "Вставити анонімний CA",
    "Imported CA" to "Імпортований CA",
    "Paste auth key" to "Вставити ключ авторизації",
    "Import auth key" to "Імпортувати ключ авторизації",
    "Imported auth key" to "Імпортований ключ авторизації",
    "Embedded Anonym CA" to "Вбудований анонімний CA",
    "Imported" to "Імпортовано",
    "Ready" to "Готово",
    "Needs key" to "Потрібен ключ",
    "Auth key needed" to "Потрібен ключ авторизації",
    "Tap Connect." to "Натисніть «Підключити».",
    "Add an auth key." to "Додайте ключ авторизації.",
    "No server" to "Немає сервера",
    "Pick a server" to "Виберіть сервер",
    "Add one to connect." to "Додайте сервер для підключення.",
    "Choose one to connect." to "Виберіть сервер для підключення.",
    "Required" to "Обов’язково",
    "Optional" to "Необов’язково",
    "Path" to "Маршрут",
    "Exit IP" to "Вихідний IP",
    "Upstream" to "Верхня мережа",
    "Unknown" to "Невідомо",
    "Unavailable" to "Недоступно",
    "Connect" to "Підключити",
    "Disconnect" to "Відключити",
    "Continue" to "Продовжити",
    "Try again" to "Спробувати ще раз",
    "Status" to "Стан",
    "Edit server" to "Змінити сервер",
    "Assign auth key" to "Призначити ключ",
    "Waiting" to "Очікування",
    "Interface up" to "Інтерфейс піднято",
    "Interface idle" to "Інтерфейс простоює",
    "Latest log" to "Останній журнал",
    "Relay" to "Релей",
    "Routing" to "Маршрутизація",
    "Tunnel active" to "Тунель активний",
    "Session log and build info." to "Журнал сесії та інформація про збірку.",
    "Current phase" to "Поточна фаза",
    "Technique" to "Техніка",
    "Copy diagnostics" to "Копіювати діагностику",
    "Share diagnostics" to "Поділитися діагностикою",
    "Report" to "Звіт",
    "Yume status" to "Стан Yume",
    "Brief" to "Коротко",
    "Client" to "Клієнт",
    "Lifecycle" to "Життєвий цикл",
    "Lifecycle msg" to "Повідомлення циклу",
    "Server mode" to "Режим сервера",
    "Peer cert" to "Сертифікат вузла",
    "Interface" to "Інтерфейс",
    "Background" to "Фон",
    "No server selected" to "Сервер не вибрано",
    "Not set" to "Не задано",
    "Established" to "Піднято",
    "Battery optimized" to "Оптимізація батареї",
    "Server version" to "Версія сервера",
    "Allow background" to "Дозволити у фоні",
    "Session log" to "Журнал сесії",
    "No session events yet." to "Подій сесії ще немає.",
    "Systemwide Android VPN routing is active." to "Системна Android VPN-маршрутизація активна.",
    "Anonym CA certificates" to "Сертифікати анонімного CA",
    "Auth keys" to "Ключі авторизації",
    "Regular" to "Звичайний",
    "Encrypted" to "Зашифрований",
    "Import file" to "Імпортувати файл",
    "Paste text" to "Вставити текст",
    "Built in" to "Вбудований",
    "Saved servers" to "Збережені сервери",
    "Select, edit, or remove saved servers." to "Виберіть, змініть або видаліть збережені сервери.",
    "Live" to "Активний",
    "Selected" to "Вибрано",
    "Edit" to "Змінити",
    "Delete" to "Видалити",
    "Profile name" to "Назва профілю",
    "Server host" to "Хост сервера",
    "Port" to "Порт",
    "TLS server name" to "TLS-ім’я сервера",
    "DNS servers" to "DNS-сервери",
    "Require anonym proof" to "Вимагати anonym proof",
    "Notes" to "Нотатки",
    "Save" to "Зберегти",
    "Update" to "Оновити",
    "Cancel" to "Скасувати",
    "Saved name" to "Збережена назва",
    "PEM text" to "PEM-текст",
    "Yume Android Diagnostics" to "Діагностика Yume Android",
    "App" to "Застосунок",
    "Performance profile" to "Профіль продуктивності",
    "Theme colors" to "Кольори теми",
    "AMOLED dark theme" to "AMOLED темна тема",
    "On" to "Увімк",
    "Off" to "Вимк",
    "Phase" to "Фаза",
    "Headline" to "Заголовок",
    "Detail" to "Опис",
    "Endpoint" to "Адреса",
    "TLS name" to "TLS-ім’я",
    "Anonym policy" to "Політика anonym",
    "Upstream network" to "Верхня мережа",
    "Control socket protected" to "Керувальний сокет захищено",
    "VPN interface established" to "VPN-інтерфейс піднято",
    "Traffic routed through Yume" to "Трафік іде через Yume",
    "OS" to "ОС",
    "Android version" to "Версія Android",
    "API level" to "Рівень API",
    "Security patch" to "Патч безпеки",
    "Manufacturer" to "Виробник",
    "Brand" to "Бренд",
    "Model" to "Модель",
    "Device code" to "Код пристрою",
    "Product" to "Продукт",
    "Hardware" to "Апаратна платформа",
    "Supported ABIs" to "Підтримувані ABI",
    "Build fingerprint" to "Відбиток збірки",
    "Runtime" to "Параметри",
    "Event log" to "Журнал подій",
    "No session events recorded." to "Події сесії ще не записані.",
    "None" to "Немає",
    "Yes" to "Так",
    "No" to "Ні",
    "Permission denied" to "Дозвіл відхилено",
    "VPN prepare failed" to "Не вдалося підготувати VPN",
    "Upstream lost" to "Верхню мережу втрачено",
    "Waiting for network" to "Очікування мережі",
    "Connecting" to "Підключення",
    "Disconnecting" to "Відключення",
    "Disconnected" to "Відключено",
    "Connection failed" to "Помилка підключення",
    "Verifying route" to "Перевірка маршруту",
    "Protected" to "Захищено",
    "Protected via Yume" to "Захищено через Yume",
    "Verifying" to "Перевірка",
    "Cellular" to "Мобільна мережа",
    "Other" to "Інша",
    "Use Android protection by default, or add a password before the app opens." to "Типово використовуйте захист Android або додайте пароль перед відкриттям застосунку.",
    "Enter the password to open Yume and load encrypted data." to "Введіть пароль, щоб відкрити Yume і завантажити зашифровані дані.",
    "Yume will ask for this password before the app opens. Stored data stays encrypted at rest." to "Yume запитуватиме цей пароль перед відкриттям застосунку. Дані зберігатимуться зашифрованими.",
    "Enter the current password, then set a new one for encrypted app data." to "Введіть поточний пароль, а потім встановіть новий для зашифрованих даних застосунку.",
    "Enter the current password to return app data protection to Android-only mode." to "Введіть поточний пароль, щоб повернути захист даних застосунку до режиму лише Android.",
    "Encrypt or decrypt text with BaseFWX-compatible modes." to "Шифруйте або розшифровуйте текст у режимах, сумісних з BaseFWX.",
    "Password is required for all BaseFWX text modes here." to "Тут пароль потрібен для всіх текстових режимів BaseFWX.",
    "Pick a gateway, keep trust material on-device, and route Android traffic through Yume." to "Виберіть шлюз, зберігайте матеріали довіри на пристрої та спрямовуйте Android-трафік через Yume.",
    "Import trust material and an auth key, then add your first server." to "Імпортуйте матеріали довіри та ключ авторизації, а потім додайте перший сервер.",
    "A server profile stores host, TLS name, DNS, MTU, Anonym CA, and auth key. Your first saved profile becomes active." to "Профіль сервера зберігає хост, TLS-ім’я, DNS, MTU, Anonym CA та ключ авторизації. Перший збережений профіль стає активним.",
    "Import a PEM auth key below, then edit this server to assign it before connecting." to "Спочатку імпортуйте нижче PEM-ключ авторизації, потім відкрийте цей сервер і призначте його перед підключенням.",
    "Edit this server and assign one of the imported auth keys before connecting." to "Відкрийте цей сервер і призначте один з імпортованих ключів авторизації перед підключенням.",
    "Yume Android diagnostics" to "Діагностика Yume Android",
    "Applied tunnel tuning" to "Застосоване налаштування тунелю",
    "legacy bridge fallback" to "резервний старий bridge",
    "Native queue" to "Нативна черга",
    "Native TCP flow window" to "Нативне вікно TCP-потоку",
    "Native UDP flow buffer" to "Нативний буфер UDP-потоку",
    "IPv6 policy" to "Політика IPv6",
    "fail-closed" to "fail-closed",
    "TCP relays" to "TCP-релеї",
    "Relay acquire timeout" to "Таймаут отримання релея",
    "Relay acquire slice" to "Крок очікування релея",
    "Idle TCP eviction" to "Видалення простаючих TCP",
    "TCP eviction grace" to "Пільговий період TCP eviction",
    "TCP eviction batch" to "Пакет TCP eviction",
    "Bridge chunk" to "Розмір bridge chunk",
    "TCP stream buffer" to "Буфер TCP-потоку",
    "UDP stream buffer" to "Буфер UDP-потоку",
    "Outbound frame queue" to "Черга вихідних фреймів",
    "tun2socks connect timeout" to "Таймаут підключення tun2socks",
    "tun2socks TCP read/write timeout" to "Таймаут читання/запису TCP tun2socks",
    "tun2socks UDP read/write timeout" to "Таймаут читання/запису UDP tun2socks",
    "Max DNS UDP associations" to "Макс. DNS UDP-асоціацій",
    "Max UDP associations" to "Макс. UDP-асоціацій",
    "DNS relay concurrency" to "Паралелізм DNS-релея",
    "Pre-routing stability delay" to "Затримка стабілізації до маршрутизації",
    "Post-routing traffic ramp" to "Плавний запуск трафіку після маршрутизації",
    "UDP 443 policy" to "Політика UDP 443",
    "forced TCP fallback" to "примусовий перехід на TCP",
    "bridge-backed beta path with technique-specific tuning" to "beta-маршрут через bridge з налаштуванням під техніку",
    "direct Yume stream tunnel backend" to "прямий тунельний backend Yume stream",
    "Authenticating to Yume over 443" to "Автентифікація в Yume через 443",
    "Stable Android path through tun2socks." to "Стабільний Android-маршрут через tun2socks.",
    "Reserved for a future packet-native Android path." to "Зарезервовано для майбутнього packet-native шляху на Android.",
    "Latency" to "Затримка",
    "Balanced" to "Баланс",
    "Throughput" to "Пропускна здатність",
    "Lower queueing for interactive traffic." to "Менше черг для інтерактивного трафіку.",
    "Default mixed-use profile." to "Типовий профіль для змішаного використання.",
    "Larger buffers for sustained transfers." to "Більші буфери для тривалих передач.",
    "Muted built-in colors." to "Приглушені вбудовані кольори.",
    "Material You on Android 12+." to "Material You на Android 12+.",
    "System default" to "Системний",
    "AMOLED on" to "AMOLED увімкнено",
    "AMOLED off" to "AMOLED вимкнено",
    "Preparing" to "Підготовка",
    "Connected" to "Підключено",
    "Error" to "Помилка",
    "Permission needed" to "Потрібен дозвіл",
    "Anonym CA missing" to "Анонімний CA відсутній",
    "Auth key required" to "Потрібен ключ авторизації",
    "Auth key unavailable" to "Ключ авторизації недоступний",
    "Credential load failed" to "Не вдалося завантажити облікові дані",
    "Traffic is flowing through Yume." to "Трафік проходить через Yume.",
    "Android needs permission to start the VPN." to "Android потрібен дозвіл, щоб запустити VPN.",
    "Profile name is required" to "Назва профілю обов’язкова",
    "Server host is required" to "Хост сервера обов’язковий",
    "Port must be between 1 and 65535" to "Порт має бути в діапазоні від 1 до 65535",
    "MTU must be between 1280 and 1500" to "MTU має бути в діапазоні від 1280 до 1500",
    "DNS servers must be numeric IPv4 or IPv6 addresses" to "DNS-сервери мають бути числовими IPv4- або IPv6-адресами",
    "Selected Anonym CA is unavailable" to "Вибраний Anonym CA недоступний",
    "Selected auth key is unavailable" to "Вибраний ключ авторизації недоступний",
    "Server saved" to "Сервер збережено",
    "The embedded Anonym CA cannot be removed" to "Вбудований Anonym CA не можна видалити",
    "Native direct beta is disabled in this build until the packet-native engine is ready." to "Native direct beta вимкнено в цій збірці, доки packet-native рушій не буде готовий.",
    "Android VPN permission was denied." to "У дозволі Android VPN відмовлено.",
    "Android VPN permission was denied. Yume cannot create a VPN interface without it." to "У дозволі Android VPN відмовлено. Без нього Yume не може створити VPN-інтерфейс.",
    "Android VPN permission granted." to "Дозвіл Android VPN надано.",
    "Android VPN permission is already granted." to "Дозвіл Android VPN уже надано.",
    "Android needs VPN permission before Yume can create the interface." to "Перш ніж Yume зможе створити інтерфейс, Android потрібен дозвіл на VPN.",
    "Requesting Android notification permission for VPN status updates." to "Запитується дозвіл Android на сповіщення для оновлень стану VPN.",
    "Opening the Android VPN permission dialog." to "Відкривається діалог дозволу Android VPN.",
    "The selected anonym CA is unavailable." to "Вибраний anonym CA недоступний.",
    "This server does not have an auth key assigned." to "Цьому серверу не призначено ключ авторизації.",
    "The selected auth key is unavailable." to "Вибраний ключ авторизації недоступний.",
    "Local CA and auth key are ready. Starting the Android VPN service." to "Локальний CA і ключ авторизації готові. Запускається служба Android VPN.",
    "Protected the control socket from the VPN path." to "Керувальний сокет виведено з VPN-маршруту.",
    "Received Yume AUTH challenge." to "Отримано AUTH challenge від Yume.",
    "Authenticated to the Yume server." to "Автентифікацію на сервері Yume завершено.",
    "Running an in-app routed Android traffic probe through Yume." to "Запускається внутрішня перевірка маршрутизованого Android-трафіку через Yume.",
    "TLS handshake failed on this Android TLS provider" to "TLS-узгодження не вдалося в цьому TLS-провайдері Android",
    "Handshake failed" to "Рукостискання не вдалося",
    "No upstream network is available. Waiting for Wi-Fi or cellular." to "Немає доступної верхньої мережі. Очікування Wi-Fi або мобільної мережі.",
    "Upstream network lost. Waiting for Wi-Fi or cellular." to "Верхню мережу втрачено. Очікування Wi-Fi або мобільної мережі.",
    "Wi-Fi or cellular is unavailable, so Yume cannot rebuild the protected control path yet." to "Wi-Fi або мобільна мережа недоступні, тому Yume поки не може заново побудувати захищений керувальний шлях.",
    "Wi-Fi or cellular disappeared. Yume is waiting to rebuild the authenticated control path." to "Wi-Fi або мобільна мережа зникли. Yume чекає, щоб заново побудувати автентифікований керувальний шлях.",
    "Ethernet" to "Ethernet",
    "Hop" to "Хоп",
    "Idle" to "Простій",
    "Unknown location" to "Невідоме місце",
    "Local SOCKS5" to "Локальний SOCKS5",
    "Inner" to "Внутрішній",
    "Proxy ready" to "Проксі готовий",
    "Android VPNs are most stable between 1280 and 1500." to "VPN на Android найстабільніші в діапазоні від 1280 до 1500.",
    "Imported PEM certificates and keys stay encrypted in the app and out of cloud backup." to "Імпортовані PEM-сертифікати та ключі залишаються зашифрованими в застосунку й поза хмарними резервними копіями.",
    "Nothing imported yet." to "Ще нічого не імпортовано.",
    "No custom CAs imported." to "Користувацькі CA ще не імпортовано.",
    "The embedded Anonym CA is always available. Import your own PEM CA if you need a different trust anchor." to "Вбудований Anonym CA завжди доступний. Імпортуйте власний PEM CA, якщо потрібен інший кореневий сертифікат довіри.",
    "Choose the trust anchor for anonym verification." to "Виберіть кореневий сертифікат довіри для перевірки anonym.",
    "No auth keys imported yet." to "Ключі авторизації ще не імпортовано.",
    "Yume auth uses a PEM private key. Import it from a file or paste the PEM text and then assign it to a server." to "Yume auth використовує PEM-закритий ключ. Імпортуйте його з файла або вставте PEM-текст, а потім призначте серверу.",
    "Choose the PEM private key used for client auth. Leave empty only if you are not ready to connect yet." to "Виберіть PEM-закритий ключ для авторизації клієнта. Залишайте порожнім лише якщо ще не готові підключатися.",
    "Import a PEM private key later if you are only saving the server for now." to "Якщо зараз ви лише зберігаєте сервер, PEM-закритий ключ можна імпортувати пізніше.",
    "This is the input format. Plain PEM is accepted as-is. If the key was encrypted with BaseFWX, switch to Encrypted and enter its password." to "Це формат введення. Звичайний PEM приймається як є. Якщо ключ було зашифровано BaseFWX, перейдіть у режим «Зашифрований» і введіть його пароль.",
    "Paste the full PEM including BEGIN/END lines." to "Вставте повний PEM разом із рядками BEGIN/END.",
    "Paste the PEM private key, or switch to Encrypted and enter the BaseFWX password." to "Вставте PEM-закритий ключ або перейдіть у режим «Зашифрований» і введіть пароль BaseFWX.",
    "Comma or newline separated numeric IPv4 or IPv6 addresses." to "Числові адреси IPv4 або IPv6, розділені комами чи новими рядками.",
    "Optional. Leave blank to use the server host." to "Необов’язково. Залиште порожнім, щоб використовувати хост сервера.",
    "Only allow servers running trusted anonym proof mode." to "Дозволяти лише сервери, що працюють у довіреному режимі anonym proof.",
    "Allow normal-mode servers too. Verity will show FAIL and the server operator may monitor proxied traffic." to "Також дозволяти сервери у звичайному режимі. Verity покаже FAIL, а оператор сервера може відстежувати проксійований трафік.",
    "Import an Anonym CA or switch this server to the embedded CA." to "Імпортуйте Anonym CA або переключіть цей сервер на вбудований CA.",
    "Import an auth key and assign it to this server." to "Імпортуйте ключ авторизації та призначте його цьому серверу.",
    "The assigned auth key is missing. Edit the server and choose another one." to "Призначений ключ авторизації відсутній. Змініть сервер і виберіть інший.",
    "If the installed app does not match this UI, uninstall it and reinstall the fresh debug APK." to "Якщо встановлений застосунок не відповідає цьому інтерфейсу, видаліть його й перевстановіть свіжий debug APK.",
    "Native direct beta" to "Native direct beta",
    "packet-native data plane" to "packet-native data plane",
    "Loop-safe" to "Захист від циклів",
    "Verity" to "Verity",
    "fwxAES light" to "fwxAES light",
    "fwxAES heavy" to "fwxAES heavy",
    "Updating app data protection." to "Оновлення захисту даних застосунку.",
    "Route is up. Verifying traffic." to "Маршрут піднято. Перевірка трафіку.",
    "Choose the trust anchor for anonym verification when this server runs anonym mode. Normal-mode servers can connect without requiring it." to "Виберіть кореневий сертифікат довіри для перевірки anonym, коли цей сервер працює в anonym-режимі. Сервери у звичайному режимі можуть підключатися без нього.",
    "Lead developer and designer." to "Головний розробник і дизайнер.",
    "Founder & CEO, FixCraft, Inc." to "Засновник і CEO, FixCraft, Inc.",
    "Debug support and fast implementation help for the main developer." to "Допомога з налагодженням і швидкою реалізацією для основного розробника.",
    "#1 Best Employee of the Year" to "#1 найкращий працівник року",
    "\"We only got this far because my friend ChatGPT is here. I could not have handled all this app work without that help.\"" to "\"Ми зайшли так далеко лише тому, що поруч був мій друг ChatGPT. Я б не впорався з таким обсягом роботи над застосунком без цієї допомоги.\"",
    "\"Building on open source is one of the most empowering experiences in software development. You do not have to wait for someone else to fix an issue or add a feature — you can build the solution yourself.\"" to "\"Розробка на основі open source — один із найсильніших досвідів у програмуванні. Не потрібно чекати, поки хтось виправить проблему чи додасть функцію — ви можете створити рішення самі.\"",
    "Yume could not load the CA or auth key for this server." to "Yume не зміг завантажити CA або ключ авторизації для цього сервера.",
)

private val ukrainianTemplates = translationTableOf(
    "Getting %s ready." to "Готуємо %s.",
    "Downloading %s for offline use." to "Завантажуємо %s для офлайн-роботи.",
    "Translating %s: %d of %d" to "Перекладаємо %s: %d з %d",
    "Couldn't finish %s yet. Try again with a stable connection." to "Не вдалося завершити підготовку %s. Спробуйте ще раз зі стабільним з’єднанням.",
    "Removed %s download." to "Завантаження %s видалено.",
    "Couldn't remove %s download." to "Не вдалося видалити завантаження %s.",
    "%s saved: %s" to "%s збережено: %s",
    "%s imported: %s" to "%s імпортовано: %s",
    "Failed to read the selected %s" to "Не вдалося прочитати вибраний %s",
    "No text provided for %s" to "Для %s не вказано текст",
    "Failed to save %s" to "Не вдалося зберегти %s",
    "Failed to import %s" to "Не вдалося імпортувати %s",
    "Saving %s." to "Збереження: %s.",
    "Importing %s." to "Імпорт: %s.",
    "Reading selected %s file." to "Читання вибраного файла: %s.",
    "%s with %s." to "%s: %s.",
    "Selected server: %s (%s)." to "Вибраний сервер: %s (%s).",
    "Technique: %s." to "Техніка: %s.",
    "Starting Yume Android session for %s (%s)." to "Запуск Android-сеансу Yume для %s (%s).",
    "Requested protection policy: %s." to "Запитана політика захисту: %s.",
    "Loaded anonym CA: %s." to "Завантажено анонімний CA: %s.",
    "Loaded auth key: %s." to "Завантажено ключ авторизації: %s.",
    "Opening authenticated Yume control path on %s." to "Відкривається автентифікований керувальний шлях Yume через %s.",
    "Selected IPv4 control endpoint %s for %s." to "Вибрано IPv4 керувальний endpoint %s для %s.",
    "TLS handshake completed: %s." to "TLS-узгодження завершено: %s.",
    "Running a Yume relay canary via %s." to "Запускається relay canary Yume через %s.",
    "Verified app relay traffic via Yume. Exit IP: %s." to "Трафік застосунку через Yume підтверджено. Вихідний IP: %s.",
    "Verified system traffic via Yume. Exit IP: %s." to "Системний трафік через Yume підтверджено. Вихідний IP: %s.",
    "TLS handshake failed: %s" to "TLS-узгодження не вдалося: %s",
    "VpnService.prepare() failed: %s" to "VpnService.prepare() завершився помилкою: %s",
    "Unhandled VPN coroutine failure: %s" to "Необроблена помилка VPN coroutine: %s",
    "Failed to verify systemwide Yume routing: %s" to "Не вдалося підтвердити системну маршрутизацію Yume: %s",
    "%s removed" to "%s видалено",
    "Reconnect for %s." to "Перепідключіться для %s.",
    "Checking keys and VPN permission for %s." to "Перевірка ключів і VPN-дозволу для %s.",
    "Loading keys and opening a control path to %s." to "Завантаження ключів і відкриття керувального шляху до %s.",
    "Using %s" to "Використовується %s",
    "Selected server: %s, %s: %s, %s: %s" to "Вибраний сервер: %s, %s: %s, %s: %s",
    "Current phase: %s • Technique: %s • Profile: %s" to "Поточна фаза: %s • Техніка: %s • Профіль: %s",
    "App: %s" to "Застосунок: %s",
    "Path: %s • %s" to "Маршрут: %s • %s",
    "Exit IP: %s • Upstream: %s" to "Вихідний IP: %s • Верхня мережа: %s",
    "Auth key: %s" to "Ключ авторизації: %s",
    "Protected in %s" to "Захищено через %s",
    "DEBUG build v%s (%d)" to "DEBUG-збірка v%s (%d)",
    "Please unlock the key %s." to "Розблокуйте ключ %s.",
    "Unlocking %s." to "Розблокування %s.",
)

private fun staticTranslations(language: ResolvedAppLanguage): TranslationTable? = when (language) {
    ResolvedAppLanguage.Ukrainian -> ukrainianTranslations
    else -> null
}

private fun templateTranslations(language: ResolvedAppLanguage): TranslationTable? = when (language) {
    ResolvedAppLanguage.Ukrainian -> ukrainianTemplates
    else -> null
}

private fun languageLocale(language: ResolvedAppLanguage): Locale = when (language) {
    ResolvedAppLanguage.English -> Locale.ENGLISH
    ResolvedAppLanguage.Russian -> Locale.forLanguageTag("ru")
    ResolvedAppLanguage.Japanese -> Locale.JAPANESE
    ResolvedAppLanguage.Ukrainian -> Locale.forLanguageTag("uk")
    is ResolvedAppLanguage.AutoTranslated -> Locale.forLanguageTag(language.localeTag)
}

data class TranslationSeed(
    val english: String,
    val russian: String,
    val japanese: String,
)

class AppStrings private constructor(
    private val language: ResolvedAppLanguage,
    private val autoTranslate: ((TranslationSeed) -> String)? = null,
    private val seedCollector: MutableSet<TranslationSeed>? = null,
) {
    private fun t(
        english: String,
        russian: String,
        japanese: String = english,
    ): String {
        val seed = TranslationSeed(english, russian, japanese)
        seedCollector?.add(seed)
        return when (language) {
            ResolvedAppLanguage.English -> english
            ResolvedAppLanguage.Russian -> russian
            ResolvedAppLanguage.Japanese -> japanese
            ResolvedAppLanguage.Ukrainian -> staticTranslations(language)?.get(english) ?: english
            is ResolvedAppLanguage.AutoTranslated -> autoTranslate?.invoke(seed) ?: english
        }
    }

    private fun tf(
        english: String,
        russian: String,
        japanese: String = english,
        vararg args: Any,
    ): String {
        val template = when (language) {
            ResolvedAppLanguage.English -> english
            ResolvedAppLanguage.Russian -> russian
            ResolvedAppLanguage.Japanese -> japanese
            ResolvedAppLanguage.Ukrainian -> templateTranslations(language)?.get(english)
                ?: staticTranslations(language)?.get(english)
                ?: english
            is ResolvedAppLanguage.AutoTranslated -> autoTranslate?.invoke(TranslationSeed(english, russian, japanese)) ?: english
        }
        return if (args.isEmpty()) template else String.format(languageLocale(language), template, *args)
    }

    val back = t("Back", "Назад", "戻る")
    val homeTab = t("Home", "Главная", "ホーム")
    val serversTab = t("Servers", "Серверы", "サーバー")
    val securityTab = t("Security", "Безопасность", "セキュリティ")
    val toolsTab = t("Tools", "Инструменты", "ツール")
    val settingsTab = t("Settings", "Настройки", "設定")
    val diagnosticsTitle = t("Diagnostics", "Диагностика", "診断")

    val languageGroupTitle = t("Language", "Язык", "言語")
    val logLanguageDialogTitle = t("Log language", "Язык журнала", "ログの言語")
    val maintainedLanguagesTitle = t("Maintained", "Поддерживаемые", "メンテナンス済み")
    val autoTranslatedLanguagesTitle = t("Auto-translated", "Автоперевод", "自動翻訳")
    val close = t("Close", "Закрыть", "閉じる")
    val appLanguageLabel = t("App language", "Язык", "アプリの言語")
    val logLanguageLabel = t("Log (Beta)", "Лог (бета)", "ログ（ベータ）")
    val unavailableInBuild = t("Unavailable in this build.", "Недоступно в этой сборке.", "このビルドでは利用できません。")
    val originalLabel = t("Original", "Оригинал", "オリジナル")
    val followsSystemLabel = t("Follows your phone", "Как на телефоне", "端末に合わせる")
    val followAppLabel = t("Follow app", "Как в приложении", "アプリに合わせる")
    val onDeviceTranslationLabel = t("On-device translation", "Перевод на устройстве", "端末上翻訳")
    val autoShortLabel = t("Auto", "Авто", "自動")
    val nativeLabel = t("Native", "Нативно", "ネイティブ")
    val downloadedLabel = t("Downloaded", "Загружено", "ダウンロード済み")
    val removeDownloadedLanguageLabel = t("Remove download", "Удалить загрузку", "ダウンロードを削除")
    val preparingLanguageTitle = t("Preparing language", "Подготовка языка", "言語を準備中")
    val downloadingLanguageTitle = t("Downloading language data", "Загрузка языковых данных", "言語データをダウンロード中")
    val translatingLanguageTitle = t("Translating the app", "Перевод приложения", "アプリを翻訳中")
    val workingTitle = t("Working", "Выполняется", "処理中")

    val connectionGroupTitle = t("Connection", "Подключение", "接続")
    val profileGroupTitle = t("Profile", "Профиль", "プロファイル")
    val themeGroupTitle = t("Theme", "Тема", "テーマ")
    val advancedGroupTitle = t("Advanced", "Дополнительно", "詳細")
    val appDataGroupTitle = t("App data", "Данные приложения", "アプリデータ")
    val advancedModeTitle = t("Advanced mode", "Продвинутый режим", "高度なモード")
    val advancedModeDescription = t(
        "Show developer and power-user tools in the app.",
        "Показывать инструменты для разработчиков и опытных пользователей.",
        "開発者や上級ユーザー向けツールを表示します。",
    )
    val advancedModeOn = t("Advanced mode on", "Продвинутый режим включен", "高度なモードを有効にしました")
    val advancedModeOff = t("Advanced mode off", "Продвинутый режим выключен", "高度なモードを無効にしました")
    val amoledTitle = t("AMOLED black", "AMOLED черный", "AMOLEDブラック")
    val amoledDescription = t("Use true black in dark mode.", "Использовать чисто черный цвет в темной теме.", "ダークモードで純粋な黒を使います。")
    val appDataSubtitle = t(
        "Use Android protection by default, or add a password before the app opens.",
        "По умолчанию используется защита Android, либо можно добавить пароль перед открытием приложения.",
        "標準では Android の保護を使い、必要ならアプリ起動前にパスワードを追加できます。",
    )
    val appDataProtectionLabel = t("Protection", "Защита", "保護")
    val androidProtected = t("Android secure", "Защищено Android", "Android 保護")
    val passwordProtected = t("Password required", "Требуется пароль", "パスワード必須")
    val setPasswordButton = t("Set password", "Установить пароль", "パスワードを設定")
    val changePasswordButton = t("Change password", "Сменить пароль", "パスワードを変更")
    val removePasswordButton = t("Remove password", "Убрать пароль", "パスワードを解除")
    val unlockAppDataTitle = t("Unlock app data", "Разблокировать данные приложения", "アプリデータを解除")
    val unlockAuthKeyTitle = t("Unlock auth key", "Разблокировать ключ авторизации", "認証キーを解除")
    val unlockAppDataDetail = t(
        "Enter the password to open Yume and load encrypted data.",
        "Введите пароль, чтобы открыть Yume и загрузить зашифрованные данные.",
        "Yume を開いて暗号化データを読み込むにはパスワードを入力してください。",
    )
    val passwordLabel = t("Password", "Пароль", "パスワード")
    val currentPasswordLabel = t("Current password", "Текущий пароль", "現在のパスワード")
    val newPasswordLabel = t("New password", "Новый пароль", "新しいパスワード")
    val confirmPasswordLabel = t("Confirm password", "Подтвердите пароль", "パスワードを確認")
    val unlockButton = t("Unlock", "Разблокировать", "解除")
    val enableAppDataPasswordDetail = t(
        "Yume will ask for this password before the app opens. Stored data stays encrypted at rest.",
        "Yume будет запрашивать этот пароль до открытия приложения. Данные останутся зашифрованными при хранении.",
        "Yume は起動前にこのパスワードを要求します。保存データは暗号化されたまま保持されます。",
    )
    val changeAppDataPasswordDetail = t(
        "Enter the current password, then set a new one for encrypted app data.",
        "Введите текущий пароль, затем задайте новый пароль для зашифрованных данных приложения.",
        "現在のパスワードを入力し、暗号化されたアプリデータ用の新しいパスワードを設定してください。",
    )
    val disableAppDataPasswordDetail = t(
        "Enter the current password to return app data protection to Android-only mode.",
        "Введите текущий пароль, чтобы вернуть защиту данных приложения только на уровень Android.",
        "現在のパスワードを入力すると、アプリデータ保護を Android のみのモードに戻します。",
    )
    val passwordRequired = t("Password required", "Нужен пароль", "パスワードが必要です")
    val currentPasswordRequired = t("Current password required", "Нужен текущий пароль", "現在のパスワードが必要です")
    val newPasswordRequired = t("New password required", "Нужен новый пароль", "新しいパスワードが必要です")
    val passwordTooShort = t("Use at least 6 characters", "Используйте не менее 6 символов", "6文字以上を使ってください")
    val passwordsDoNotMatch = t("Passwords do not match", "Пароли не совпадают", "パスワードが一致しません")
    val appDataPasswordEnabled = t("App data password enabled", "Пароль для данных приложения включен", "アプリデータのパスワードを有効にしました")
    val appDataPasswordChanged = t("App data password changed", "Пароль для данных приложения изменен", "アプリデータのパスワードを変更しました")
    val appDataPasswordDisabled = t("App data password removed", "Пароль для данных приложения удален", "アプリデータのパスワードを解除しました")
    val failedToEnableAppDataPassword = t(
        "Couldn't enable the app data password.",
        "Не удалось включить пароль для данных приложения.",
        "アプリデータのパスワードを有効にできませんでした。",
    )
    val failedToChangeAppDataPassword = t(
        "Couldn't change the app data password.",
        "Не удалось сменить пароль для данных приложения.",
        "アプリデータのパスワードを変更できませんでした。",
    )
    val failedToDisableAppDataPassword = t(
        "Couldn't remove the app data password.",
        "Не удалось убрать пароль для данных приложения.",
        "アプリデータのパスワードを解除できませんでした。",
    )
    val authKeyUnlockFailed = t(
        "Couldn't unlock this auth key. Check the password.",
        "Не удалось разблокировать этот ключ авторизации. Проверьте пароль.",
        "この認証キーを解除できませんでした。パスワードを確認してください。",
    )

    val deviceGroupTitle = t("Device", "Устройство", "デバイス")
    val aboutGroupTitle = t("About", "О приложении", "情報")
    val creditsTitle = t("Credits", "Благодарности", "クレジット")
    val toolsTitle = t("Tools", "Инструменты", "ツール")
    val textToolsTitle = t("Text tools", "Текстовые инструменты", "テキストツール")
    val textToolsDetail = t(
        "Encrypt or decrypt text with BaseFWX-compatible modes.",
        "Шифруйте и расшифровывайте текст в режимах, совместимых с BaseFWX.",
        "BaseFWX 互換モードでテキストを暗号化または復号します。",
    )
    val toolInputLabel = t("Input", "Ввод", "入力")
    val toolOutputLabel = t("Output", "Вывод", "出力")
    val toolInputRequired = t("Input required", "Нужен ввод", "入力が必要です")
    val toolOperationFailed = t("Operation failed", "Операция не удалась", "処理に失敗しました")
    val toolPasswordHint = t(
        "Password is required for all BaseFWX text modes here.",
        "Здесь пароль нужен для всех текстовых режимов BaseFWX.",
        "ここではすべての BaseFWX テキストモードでパスワードが必要です。",
    )
    val encryptTextButton = t("Encrypt", "Зашифровать", "暗号化")
    val decryptTextButton = t("Decrypt", "Расшифровать", "復号")
    val clearTextButton = t("Clear", "Очистить", "クリア")
    val toolsModeFwxAes = t("fwxAES light", "fwxAES light", "fwxAES light")
    val toolsModeFwxAesHeavy = t("fwxAES heavy", "fwxAES heavy", "fwxAES heavy")
    val toolsModeB512 = "b512"
    val toolsModePb512 = "pb512"
    val systemGroupTitle = t("System", "Система", "システム")
    val authorLabel = t("Author", "Автор", "作成者")
    val companyLabel = t("Company", "Компания", "会社")
    val creditsWrittenByLabel = t("Credits written by", "Текст благодарностей", "クレジット文")
    val developerQuoteLabel = t("Recognition", "О признании", "認識について")
    val founderNoteLabel = t("From F1xGOD", "От F1xGOD", "F1xGOD より")
    val yumeLabel = "Yume"
    val basefwxLabel = "BaseFWX"
    val diagnosticsButton = t("Diagnostics", "Диагностика", "診断")
    val batterySettingsButton = t("Battery settings", "Настройки батареи", "バッテリー設定")
    val notificationSettingsButton = t("Notification settings", "Настройки уведомлений", "通知設定")
    val notificationsLabel = t("Notifications", "Уведомления", "通知")
    val batteryOptimizationLabel = t("Battery optimization", "Оптимизация батареи", "バッテリー最適化")
    val nativeDirectBetaLabel = t("Native direct beta", "Native direct beta")
    val dynamicColorsLabel = t("Dynamic colors", "Динамические цвета", "ダイナミックカラー")
    val allowed = t("Allowed", "Разрешены", "許可済み")
    val permissionRequired = t("Permission required", "Нужно разрешение", "権限が必要")
    val optimized = t("Optimized", "Оптимизируется", "最適化中")
    val unrestricted = t("Unrestricted", "Без ограничений", "制限なし")
    val enabled = t("Enabled", "Включено", "有効")
    val disabled = t("Disabled", "Отключено", "無効")
    val available = t("Available", "Доступно", "利用可能")
    val unavailableOnAndroidVersion = t(
        "Unavailable on this Android version",
        "Недоступно на этой версии Android",
        "このAndroidバージョンでは利用できません。",
    )

    val serversTitle = t("Servers", "Серверы", "サーバー")
    val serversSubtitle = t("Pick, edit, or remove a saved gateway.", "Выберите, измените или удалите сохраненный шлюз.", "保存済みゲートウェイを選択、編集、削除します。")
    val addServer = t("Add server", "Добавить сервер", "サーバーを追加")
    val yourYumeTunnelTitle = t("Your Yume tunnel", "Ваш туннель Yume", "あなたの Yume トンネル")
    val setUpYumeVpnTitle = t("Set up Yume VPN", "Настройте Yume VPN", "Yume VPN を設定")
    val yourYumeTunnelSubtitle = t(
        "Pick a gateway, keep trust material on-device, and route Android traffic through Yume.",
        "Выберите шлюз, храните доверенные материалы на устройстве и направляйте Android-трафик через Yume.",
        "ゲートウェイを選び、信頼情報を端末に保持したまま、Android の通信を Yume 経由にします。",
    )
    val setUpYumeVpnSubtitle = t(
        "Import trust material and an auth key, then add your first server.",
        "Импортируйте доверенные материалы и ключ авторизации, затем добавьте первый сервер.",
        "信頼情報と認証キーを取り込み、最初のサーバーを追加してください。",
    )
    val noServersConfigured = t("No servers configured", "Серверы не настроены", "サーバー未設定")
    val addFirstGatewayTitle = t("Add your first gateway", "Добавьте первый шлюз", "最初のゲートウェイを追加")
    val addFirstGatewayDetail = t(
        "A server profile stores host, TLS name, DNS, MTU, Anonym CA, and auth key. Your first saved profile becomes active.",
        "Профиль сервера хранит хост, TLS-имя, DNS, MTU, Anonym CA и ключ авторизации. Первый сохраненный профиль станет активным.",
        "サーバープロファイルにはホスト、TLS 名、DNS、MTU、匿名CA、認証キーを保存します。最初に保存したプロファイルが有効になります。",
    )

    val securityTitle = t("Trust & auth", "Доверие и авторизация", "信頼と認証")
    val securitySubtitle = t("CA and auth keys.", "CA и ключи авторизации.", "CA と認証キー。")
    val selectedServerLabel = t("Selected server", "Выбранный сервер", "選択中のサーバー")
    val anonymCaShort = t("Anonym CA", "Анонимный CA", "匿名CA")
    val authKeyShort = t("Auth key", "Ключ авторизации", "認証キー")
    val missing = t("Missing", "Отсутствует", "見つかりません")
    val notAssigned = t("Not assigned", "Не назначен", "未割り当て")
    val pasteAnonymCaTitle = t("Paste Anonym CA", "Вставить анонимный CA", "匿名CAを貼り付け")
    val importedCaName = t("Imported CA", "Импортированный CA", "インポートしたCA")
    val pasteAuthKeyTitle = t("Paste auth key", "Вставить ключ авторизации", "認証キーを貼り付け")
    val importAuthKeyTitle = t("Import auth key", "Импортировать ключ авторизации", "認証キーをインポート")
    val importedAuthKeyName = t("Imported auth key", "Импортированный ключ авторизации", "インポートした認証キー")
    val embeddedAnonymCaName = t("Embedded Anonym CA", "Встроенный анонимный CA", "組み込み匿名CA")
    val importedLabel = t("Imported", "Импортировано", "インポート済み")

    val homeReady = t("Ready", "Готово", "準備完了")
    val homeNeedsKey = t("Needs key", "Нужен ключ", "キーが必要")
    val homeAuthKeyNeeded = t("Auth key needed", "Нужен ключ авторизации", "認証キーが必要")
    val homeTapConnect = t("Tap Connect.", "Нажмите «Подключить».", "接続をタップしてください。")
    val homeAddAuthKey = t("Add an auth key.", "Добавьте ключ авторизации.", "認証キーを追加してください。")
    val noServer = t("No server", "Нет сервера", "サーバーなし")
    val pickServer = t("Pick a server", "Выберите сервер", "サーバーを選択")
    val addOneToConnect = t("Add one to connect.", "Добавьте сервер для подключения.", "接続するには追加してください。")
    val chooseOneToConnect = t("Choose one to connect.", "Выберите сервер для подключения.", "接続するサーバーを選択してください。")
    val serversButton = t("Servers", "Серверы", "サーバー")
    val anonymRequired = t("Required", "Обязательно", "必須")
    val anonymOptional = t("Optional", "Необязательно", "任意")
    val anonymLabel = "Anonym"
    val pathLabel = t("Path", "Маршрут", "経路")
    val exitIpLabel = t("Exit IP", "Выходной IP", "出口IP")
    val upstreamLabel = t("Upstream", "Верхняя сеть", "上流")
    val unknown = t("Unknown", "Неизвестно", "不明")
    val unavailable = t("Unavailable", "Недоступно", "利用不可")
    val connect = t("Connect", "Подключить", "接続")
    val disconnect = t("Disconnect", "Отключить", "切断")
    val continueAction = t("Continue", "Продолжить", "続行")
    val tryAgain = t("Try again", "Повторить", "再試行")
    val statusButton = t("Status", "Статус", "状態")
    val editServerButton = t("Edit server", "Изменить сервер", "サーバーを編集")
    val assignAuthKeyButton = t("Assign auth key", "Назначить ключ", "認証キーを割り当て")
    val loopSafeShort = t("Loop-safe", "Loop-safe", "ループ保護")
    val waiting = t("Waiting", "Ожидание", "待機")
    val interfaceUp = t("Interface up", "Интерфейс поднят", "インターフェース稼働")
    val interfaceIdle = t("Interface idle", "Интерфейс простаивает", "インターフェース待機")
    val latestLogLabel = t("Latest log", "Последний лог", "最新ログ")
    val protectionShortLabel = t("Protection", "Защита", "保護")
    val relayShortLabel = t("Relay", "Релей", "リレー")
    val routingShortLabel = t("Routing", "Маршрутизация", "ルーティング")
    val tunnelActive = t("Tunnel active", "Туннель активен", "トンネル有効")
    val importPemAuthKeyHint = t(
        "Import a PEM auth key below, then edit this server to assign it before connecting.",
        "Сначала импортируйте PEM-ключ авторизации ниже, затем откройте сервер и назначьте его перед подключением.",
        "下の認証キーをインポートしてから、このサーバーを編集して接続前に割り当ててください。",
    )
    val assignImportedAuthKeyHint = t(
        "Edit this server and assign one of the imported auth keys before connecting.",
        "Откройте сервер и назначьте один из импортированных ключей авторизации перед подключением.",
        "接続前にこのサーバーを編集して、インポート済みの認証キーを割り当ててください。",
    )

    val diagnosticsSubtitle = t("Session log and build info.", "Журнал сессии и информация о сборке.", "セッションログとビルド情報。")
    val currentPhaseLabel = t("Current phase", "Текущая фаза", "現在のフェーズ")
    val techniqueLabel = t("Technique", "Техника", "方式")
    val profileLabel = t("Profile", "Профиль", "プロファイル")
    val copyDiagnostics = t("Copy diagnostics", "Копировать диагностику", "診断をコピー")
    val shareDiagnostics = t("Share diagnostics", "Поделиться диагностикой", "診断を共有")
    val shareDiagnosticsSubject = t("Yume Android diagnostics", "Диагностика Yume Android", "Yume Android 診断")
    val shareDiagnosticsChooser = t("Share diagnostics", "Поделиться диагностикой", "診断を共有")
    val reportTitle = t("Report", "Отчет", "レポート")
    val yumeStatusTitle = t("Yume status", "Статус Yume", "Yume の状態")
    val briefLabel = t("Brief", "Кратко", "概要")
    val clientLabel = t("Client", "Клиент", "クライアント")
    val lifecycleLabel = t("Lifecycle", "Жизненный цикл", "ライフサイクル")
    val lifecycleMessageLabel = t("Lifecycle msg", "Сообщение цикла", "ライフサイクルメッセージ")
    val connectionLabel = t("Connection", "Подключение", "接続")
    val serverModeLabel = t("Server mode", "Режим сервера", "サーバーモード")
    val innerLabel = t("Inner", "Inner", "Inner")
    val hopLabel = t("Hop", "Hop", "Hop")
    val verityLabel = t("Verity", "Verity", "Verity")
    val localSocks5Label = t("Local SOCKS5", "Локальный SOCKS5", "ローカル SOCKS5")
    val peerCertLabel = t("Peer cert", "Сертификат узла", "ピア証明書")
    val loopSafeLabel = t("Loop-safe", "Loop-safe", "ループ保護")
    val interfaceLabel = t("Interface", "Интерфейс", "インターフェース")
    val backgroundLabel = t("Background", "Фон", "バックグラウンド")
    val noServerSelected = t("No server selected", "Сервер не выбран", "サーバー未選択")
    val notSet = t("Not set", "Не задано", "未設定")
    val established = t("Established", "Поднят", "確立済み")
    val batteryOptimized = t("Battery optimized", "Оптимизация батареи", "バッテリー最適化")
    val serverVersionLabel = t("Server version", "Версия сервера", "サーバーバージョン")
    val allowBackground = t("Allow background", "Разрешить в фоне", "バックグラウンドを許可")
    val sessionLogTitle = t("Session log", "Журнал сессии", "セッションログ")
    val noSessionEventsYet = t("No session events yet.", "Событий сессии пока нет.", "セッションイベントはまだありません。")
    val debugBuildHint = t(
        "If the installed app does not match this UI, uninstall it and reinstall the fresh debug APK.",
        "Если установленное приложение не совпадает с этим интерфейсом, удалите его и установите свежий debug APK.",
        "インストール済みアプリの UI が一致しない場合は、一度削除して新しい debug APK を入れ直してください。",
    )
    val systemwideRoutingActiveDetail = t(
        "Systemwide Android VPN routing is active.",
        "Системная Android VPN-маршрутизация активна.",
        "Android 全体の VPN ルーティングが有効です。",
    )

    val trustAuthHeader = t("Trust & auth", "Доверие и авторизация", "信頼と認証")
    val trustAuthDescription = t(
        "Imported PEM certificates and keys stay encrypted in the app and out of cloud backup.",
        "Импортированные PEM-сертификаты и ключи остаются зашифрованными в приложении и не попадают в облачное резервное копирование.",
        "インポートした PEM 証明書とキーはアプリ内で暗号化され、クラウドバックアップの対象外です。",
    )
    val anonymCaCertificatesTitle = t("Anonym CA certificates", "Сертификаты анонимного CA", "匿名CA証明書")
    val anonymCaCertificatesSubtitle = t(
        "The embedded Anonym CA is always available. Import your own PEM CA if you need a different trust anchor.",
        "Встроенный анонимный CA всегда доступен. Импортируйте свой PEM CA, если нужен другой корневой сертификат доверия.",
        "組み込み匿名CAは常に利用可能です。別の信頼アンカーが必要なら独自のPEM CAをインポートしてください。",
    )
    val noCustomCaImported = t("No custom CAs imported.", "Пользовательские CA еще не импортированы.", "カスタムCAはまだインポートされていません。")
    val authKeysTitle = t("Auth keys", "Ключи авторизации", "認証キー")
    val authKeysSubtitle = t(
        "Yume auth uses a PEM private key. Import it from a file or paste the PEM text and then assign it to a server.",
        "Авторизация Yume использует PEM-приватный ключ. Импортируйте его из файла или вставьте PEM-текст, затем назначьте серверу.",
        "Yume認証ではPEM秘密鍵を使います。ファイルからインポートするかPEMテキストを貼り付けて、サーバーに割り当ててください。",
    )
    val authKeyImportModeHelper = t(
        "This is the input format. Plain PEM is accepted as-is. If the key was encrypted with BaseFWX, switch to Encrypted and enter its password.",
        "Это формат ввода. Обычный PEM принимается как есть. Если ключ зашифрован через BaseFWX, выберите «Зашифрован» и введите пароль.",
        "これは入力形式です。通常の PEM はそのまま使えます。BaseFWX で暗号化されたキーなら「暗号化」を選び、パスワードを入力してください。",
    )
    val regularKeyOption = t("Regular", "Обычный", "通常")
    val encryptedKeyOption = t("Encrypted", "Зашифрован", "暗号化")
    val noAuthKeysImported = t("No auth keys imported yet.", "Ключи авторизации еще не импортированы.", "まだ認証キーはインポートされていません。")
    val importFile = t("Import file", "Импортировать файл", "ファイルをインポート")
    val pasteText = t("Paste text", "Вставить текст", "テキストを貼り付け")
    val builtIn = t("Built in", "Встроенный", "組み込み")

    val savedServersTitle = t("Saved servers", "Сохраненные серверы", "保存済みサーバー")
    val savedServersSubtitle = t("Select, edit, or remove saved servers.", "Выберите, измените или удалите сохраненные серверы.", "保存済みサーバーを選択、編集、削除します。")
    val live = t("Live", "Активен", "稼働中")
    val selected = t("Selected", "Выбран", "選択済み")
    val edit = t("Edit", "Изменить", "編集")
    val delete = t("Delete", "Удалить", "削除")
    val dnsLabel = "DNS"
    val mtuLabel = "MTU"

    val addServerTitle = t("Add server", "Добавить сервер", "サーバーを追加")
    val editServerTitle = t("Edit server", "Изменить сервер", "サーバーを編集")
    val profileNameLabel = t("Profile name", "Имя профиля", "プロファイル名")
    val serverHostLabel = t("Server host", "Хост сервера", "サーバーホスト")
    val portLabel = t("Port", "Порт", "ポート")
    val tlsServerNameLabel = t("TLS server name", "TLS-имя сервера", "TLSサーバー名")
    val tlsServerNameHelper = t("Optional. Leave blank to use the server host.", "Необязательно. Оставьте пустым, чтобы использовать хост сервера.", "任意。空欄の場合はサーバーホストを使います。")
    val httpsMaskingTitle = t("HTTPS masking", "HTTPS-маскировка", "HTTPSマスキング")
    val httpsMaskingLabel = t("HTTPS masking", "HTTPS-маскировка", "HTTPSマスキング")
    val httpsMaskingEnabled = t(
        "Send the HTTP/2 carrier preface after TLS so the control path looks like browser traffic. Enable only when the server has obfs on.",
        "Отправлять HTTP/2 carrier после TLS, чтобы управляющий канал выглядел как браузерный трафик. Включайте только если на сервере включен obfs.",
        "TLS の後に HTTP/2 carrier を送り、制御経路をブラウザー通信のように見せます。サーバー側で obfs が有効な場合のみ使ってください。",
    )
    val httpsMaskingDisabled = t(
        "Connect directly to the Yume AUTH exchange without the HTTPS masking preface.",
        "Подключаться напрямую к обмену Yume AUTH без HTTPS-маскировки.",
        "HTTPS マスキングの前置きなしで Yume AUTH に直接接続します。",
    )
    val httpsMaskingSecretLabel = t("HTTPS masking secret", "Секрет HTTPS-маскировки", "HTTPSマスキングシークレット")
    val httpsMaskingSecretHelper = t(
        "Must match --obfs-secret. Leave blank only when the server has no obfs secret.",
        "Должен совпадать с --obfs-secret. Оставляйте пустым только если на сервере нет obfs-secret.",
        "--obfs-secret と一致させます。サーバーに obfs secret がない場合だけ空欄にします。",
    )
    val dnsServersLabel = t("DNS servers", "DNS-серверы", "DNSサーバー")
    val dnsServersHelper = t("Comma or newline separated numeric IPv4 or IPv6 addresses.", "Числовые IPv4- или IPv6-адреса, разделенные запятыми или новой строкой.", "カンマまたは改行で区切った数値のIPv4/IPv6アドレス。")
    val mtuHelper = t("Android VPNs are most stable between 1280 and 1500.", "VPN на Android наиболее стабильны при MTU от 1280 до 1500.", "Android VPN は 1280 から 1500 の間で最も安定します。")
    val requireAnonymProofTitle = t("Require anonym proof", "Требовать anonym proof", "Anonym proofを必須にする")
    val requireAnonymProofEnabled = t(
        "Only allow servers running trusted anonym proof mode.",
        "Разрешать только серверы, работающие в доверенном режиме anonym proof.",
        "信頼された anonym proof モードで動作するサーバーのみ許可します。",
    )
    val requireAnonymProofDisabled = t(
        "Allow normal-mode servers too. Verity will show FAIL and the server operator may monitor proxied traffic.",
        "Разрешать и обычный режим. Verity покажет FAIL, а оператор сервера может отслеживать проксируемый трафик.",
        "通常モードのサーバーも許可します。Verity は FAIL を表示し、サーバー運営者がプロキシ通信を監視できる場合があります。",
    )
    val anonymCaChoiceTitle = t("Anonym CA", "Анонимный CA", "匿名CA")
    val anonymCaChoiceHelperRequired = t(
        "Choose the trust anchor for anonym verification.",
        "Выберите корневой сертификат доверия для anonym-проверки.",
        "anonym 検証に使う信頼アンカーを選択します。",
    )
    val anonymCaChoiceHelperOptional = t(
        "Choose the trust anchor for anonym verification when this server runs anonym mode. Normal-mode servers can connect without requiring it.",
        "Выберите корневой сертификат доверия для anonym-проверки, когда этот сервер работает в anonym-режиме. Серверы в обычном режиме могут подключаться и без него.",
        "このサーバーが anonym モードで動作する際に使う信頼アンカーを選択します。通常モードのサーバーは必須にしなくても接続できます。",
    )
    val authKeyChoiceTitle = t("Auth key", "Ключ авторизации", "認証キー")
    val authKeyChoiceHelper = t(
        "Choose the PEM private key used for client auth. Leave empty only if you are not ready to connect yet.",
        "Выберите PEM-приватный ключ для авторизации клиента. Оставьте пустым только если пока не готовы подключаться.",
        "クライアント認証に使うPEM秘密鍵を選択します。まだ接続しない場合のみ空欄のままにしてください。",
    )
    val nothingImportedYet = t("Nothing imported yet.", "Пока ничего не импортировано.", "まだ何もインポートされていません。")
    val notesLabel = t("Notes", "Заметки", "メモ")
    val save = t("Save", "Сохранить", "保存")
    val update = t("Update", "Обновить", "更新")
    val cancel = t("Cancel", "Отмена", "キャンセル")

    val notAssignedChoice = t("Not assigned", "Не назначен", "未割り当て")
    val notAssignedChoiceSubtitle = t(
        "Import a PEM private key later if you are only saving the server for now.",
        "Импортируйте PEM-приватный ключ позже, если пока только сохраняете сервер.",
        "いまはサーバーを保存するだけなら、PEM秘密鍵は後でインポートできます。",
    )

    val savedNameLabel = t("Saved name", "Сохраненное имя", "保存名")
    val pemTextLabel = t("PEM text", "PEM-текст", "PEMテキスト")
    val pemTextHelper = t("Paste the full PEM including BEGIN/END lines.", "Вставьте полный PEM, включая строки BEGIN/END.", "BEGIN/END 行を含む完全な PEM を貼り付けてください。")
    val authKeyTextHelper = t(
        "Paste the PEM private key, or switch to Encrypted and enter the BaseFWX password.",
        "Вставьте PEM-приватный ключ, либо переключитесь на «Зашифрован» и введите пароль BaseFWX.",
        "PEM 秘密鍵を貼り付けるか、「暗号化」を選んで BaseFWX のパスワードを入力してください。",
    )

    val diagnosticsReportHeader = t("Yume Android Diagnostics", "Диагностика Yume Android", "Yume Android 診断")
    val appLabel = t("App", "Приложение", "アプリ")
    val performanceProfileLabel = t("Performance profile", "Профиль производительности", "パフォーマンスプロファイル")
    val themeColorsLabel = t("Theme colors", "Цвета темы", "テーマカラー")
    val amoledDarkThemeLabel = t("AMOLED dark theme", "AMOLED темная тема", "AMOLEDダークテーマ")
    val on = t("On", "Вкл", "オン")
    val off = t("Off", "Выкл", "オフ")
    val languageLabel = t("Language", "Язык", "言語")
    val phaseLabel = t("Phase", "Фаза", "フェーズ")
    val headlineLabel = t("Headline", "Заголовок", "見出し")
    val detailLabel = t("Detail", "Описание", "詳細")
    val selectedServerReportLabel = t("Selected server", "Выбранный сервер", "選択中のサーバー")
    val endpointLabel = t("Endpoint", "Адрес", "エンドポイント")
    val tlsNameLabel = t("TLS name", "TLS-имя", "TLS名")
    val anonymPolicyLabel = t("Anonym policy", "Политика anonym", "Anonymポリシー")
    val anonymCaLabel = t("Anonym CA", "Анонимный CA", "匿名CA")
    val authKeyLabel = t("Auth key", "Ключ авторизации", "認証キー")
    val upstreamNetworkReportLabel = t("Upstream network", "Верхняя сеть", "上流ネットワーク")
    val controlSocketProtectedLabel = t("Control socket protected", "Управляющий сокет защищен", "制御ソケット保護")
    val vpnInterfaceEstablishedLabel = t("VPN interface established", "VPN-интерфейс поднят", "VPNインターフェース確立")
    val trafficRoutedLabel = t("Traffic routed through Yume", "Трафик идет через Yume", "Yume経由でトラフィックをルーティング")
    val deviceTitle = t("Device", "Устройство", "デバイス")
    val osLabel = t("OS", "ОС", "OS")
    val androidVersionLabel = t("Android version", "Версия Android", "Android バージョン")
    val apiLevelLabel = t("API level", "Уровень API", "API レベル")
    val securityPatchLabel = t("Security patch", "Патч безопасности", "セキュリティパッチ")
    val manufacturerLabel = t("Manufacturer", "Производитель", "メーカー")
    val brandLabel = t("Brand", "Бренд", "ブランド")
    val modelLabel = t("Model", "Модель", "モデル")
    val deviceCodeLabel = t("Device code", "Код устройства", "デバイスコード")
    val productLabel = t("Product", "Продукт", "製品")
    val hardwareLabel = t("Hardware", "Аппаратная платформа", "ハードウェア")
    val supportedAbisLabel = t("Supported ABIs", "Поддерживаемые ABI", "対応 ABI")
    val buildFingerprintLabel = t("Build fingerprint", "Отпечаток сборки", "ビルドフィンガープリント")
    val runtimeTitle = t("Runtime", "Параметры", "ランタイム")
    val runtimeStatusLabel = t("Status", "Состояние", "状態")
    val runtimeAppliedBridgeTuningTitle = t("Applied tunnel tuning", "Примененная настройка туннеля", "適用されたトンネル調整")
    val runtimeCompatibilityBridgeFallbackStatus = t("legacy bridge fallback", "резервный старый bridge", "旧ブリッジへのフォールバック")
    val runtimeNativeQueueLabel = t("Native queue", "Нативная очередь", "ネイティブキュー")
    val runtimeNativeTcpFlowWindowLabel = t("Native TCP flow window", "Нативное окно TCP-потока", "ネイティブ TCP フローウィンドウ")
    val runtimeNativeUdpFlowBufferLabel = t("Native UDP flow buffer", "Нативный буфер UDP-потока", "ネイティブ UDP フローバッファ")
    val runtimeIpv6PolicyLabel = t("IPv6 policy", "Политика IPv6", "IPv6 ポリシー")
    val runtimeIpv6FailClosedStatus = t("fail-closed", "fail-closed", "フェイルクローズ")
    val runtimeTcpRelaysLabel = t("TCP relays", "TCP-релеи", "TCP リレー")
    val runtimeRelayAcquireTimeoutLabel = t("Relay acquire timeout", "Таймаут получения релея", "リレー取得タイムアウト")
    val runtimeRelayAcquireSliceLabel = t("Relay acquire slice", "Шаг ожидания релея", "リレー取得スライス")
    val runtimeIdleTcpEvictionLabel = t("Idle TCP eviction", "Удаление простаивающих TCP", "アイドル TCP 退避")
    val runtimeTcpEvictionGraceLabel = t("TCP eviction grace", "Льготный период TCP eviction", "TCP 退避猶予")
    val runtimeTcpEvictionBatchLabel = t("TCP eviction batch", "Пакет TCP eviction", "TCP 退避バッチ")
    val runtimeBridgeChunkLabel = t("Bridge chunk", "Размер bridge chunk", "ブリッジチャンク")
    val runtimeTcpStreamBufferLabel = t("TCP stream buffer", "Буфер TCP-потока", "TCP ストリームバッファ")
    val runtimeUdpStreamBufferLabel = t("UDP stream buffer", "Буфер UDP-потока", "UDP ストリームバッファ")
    val runtimeOutboundFrameQueueLabel = t("Outbound frame queue", "Очередь исходящих фреймов", "送信フレームキュー")
    val runtimeTun2SocksConnectTimeoutLabel = t("tun2socks connect timeout", "Таймаут подключения tun2socks", "tun2socks 接続タイムアウト")
    val runtimeTun2SocksTcpReadWriteTimeoutLabel = t("tun2socks TCP read/write timeout", "Таймаут чтения/записи TCP tun2socks", "tun2socks TCP 読み書きタイムアウト")
    val runtimeTun2SocksUdpReadWriteTimeoutLabel = t("tun2socks UDP read/write timeout", "Таймаут чтения/записи UDP tun2socks", "tun2socks UDP 読み書きタイムアウト")
    val runtimeMaxDnsUdpAssociationsLabel = t("Max DNS UDP associations", "Макс. DNS UDP-ассоциаций", "最大 DNS UDP 関連")
    val runtimeMaxUdpAssociationsLabel = t("Max UDP associations", "Макс. UDP-ассоциаций", "最大 UDP 関連")
    val runtimeDnsRelayConcurrencyLabel = t("DNS relay concurrency", "Параллелизм DNS-релея", "DNS リレー並列数")
    val runtimePreRoutingStabilityDelayLabel = t("Pre-routing stability delay", "Задержка стабилизации до маршрутизации", "ルーティング前安定化遅延")
    val runtimePostRoutingTrafficRampLabel = t("Post-routing traffic ramp", "Плавный запуск трафика после маршрутизации", "ルーティング後トラフィック立ち上げ")
    val runtimeUdp443PolicyLabel = t("UDP 443 policy", "Политика UDP 443", "UDP 443 ポリシー")
    val runtimeForcedTcpFallback = t("forced TCP fallback", "принудительный переход на TCP", "TCP フォールバック強制")
    val runtimeBridgeBackedBetaStatus = t("bridge-backed beta path with technique-specific tuning", "beta-маршрут через bridge с настройкой под технику", "方式別チューニング付きのブリッジベータ経路")
    val runtimePacketNativeStatus = t("direct Yume stream tunnel backend", "прямой туннельный backend Yume stream", "Yume ストリーム直接トンネルバックエンド")
    val eventLogTitle = t("Event log", "Журнал событий", "イベントログ")
    val noSessionEventsRecorded = t("No session events recorded.", "События сессии еще не записаны.", "セッションイベントはまだ記録されていません。")
    val none = t("None", "Нет", "なし")
    val yes = t("Yes", "Да", "はい")
    val no = t("No", "Нет", "いいえ")

    val profileNameRequired = t("Profile name is required", "Имя профиля обязательно", "プロファイル名は必須です")
    val serverHostRequired = t("Server host is required", "Хост сервера обязателен", "サーバーホストは必須です")
    val portRangeError = t("Port must be between 1 and 65535", "Порт должен быть в диапазоне от 1 до 65535", "ポートは1から65535の範囲で指定してください")
    val mtuRangeError = t("MTU must be between 1280 and 1500", "MTU должен быть в диапазоне от 1280 до 1500", "MTU は 1280 から 1500 の範囲で指定してください")
    val dnsServerFormatError = t("DNS servers must be numeric IPv4 or IPv6 addresses", "DNS-серверы должны быть числовыми IPv4- или IPv6-адресами", "DNSサーバーは数値の IPv4 または IPv6 アドレスである必要があります")
    val anonymCaUnavailable = t("Selected Anonym CA is unavailable", "Выбранный анонимный CA недоступен", "選択した匿名CAは利用できません")
    val authKeyUnavailable = t("Selected auth key is unavailable", "Выбранный ключ авторизации недоступен", "選択した認証キーは利用できません")
    val serverSaved = t("Server saved", "Сервер сохранен", "サーバーを保存しました")
    val embeddedAnonymCaCannotBeRemoved = t("The embedded Anonym CA cannot be removed", "Встроенный анонимный CA нельзя удалить", "組み込み匿名CAは削除できません")
    val nativeDirectUnavailable = t(
        "Native direct beta is disabled in this build until the packet-native engine is ready.",
        "Native direct beta отключен в этой сборке, пока packet-native движок не будет готов.",
        "Native direct beta は、パケットネイティブエンジンの準備が整うまでこのビルドでは無効です。",
    )
    val amoledOn = t("AMOLED on", "AMOLED включен", "AMOLED オン")
    val amoledOff = t("AMOLED off", "AMOLED выключен", "AMOLED オフ")
    val permissionRequiredHeadline = t("Permission required", "Нужно разрешение", "権限が必要")
    val permissionRequiredDetail = t("Android needs permission to start the VPN.", "Android нужно разрешение для запуска VPN.", "VPN を開始するには Android の権限が必要です。")
    val preparingHeadline = t("Preparing", "Подготовка", "準備中")
    val anonymCaMissingHeadline = t("Anonym CA missing", "Анонимный CA отсутствует", "匿名CAが見つかりません")
    val anonymCaMissingDetail = t("Import an Anonym CA or switch this server to the embedded CA.", "Импортируйте анонимный CA или переключите этот сервер на встроенный CA.", "匿名CAをインポートするか、このサーバーを組み込みCAに切り替えてください。")
    val authKeyRequiredHeadline = t("Auth key required", "Нужен ключ авторизации", "認証キーが必要")
    val authKeyRequiredDetail = t("Import an auth key and assign it to this server.", "Импортируйте ключ авторизации и назначьте его этому серверу.", "認証キーをインポートしてこのサーバーに割り当ててください。")
    val authKeyUnavailableHeadline = t("Auth key unavailable", "Ключ авторизации недоступен", "認証キーを利用できません")
    val authKeyUnavailableDetail = t("The assigned auth key is missing. Edit the server and choose another one.", "Назначенный ключ авторизации отсутствует. Измените сервер и выберите другой.", "割り当てられた認証キーが見つかりません。サーバーを編集して別のキーを選択してください。")
    val credentialLoadFailedHeadline = t("Credential load failed", "Не удалось загрузить учетные данные", "認証情報の読み込みに失敗しました")
    val credentialLoadFailedDetail = t("Yume could not load the CA or auth key for this server.", "Yume не смог загрузить CA или ключ авторизации для этого сервера.", "このサーバーの CA または認証キーを Yume が読み込めませんでした。")

    val permissionDeniedHeadline = t("Permission denied", "Разрешение отклонено", "権限が拒否されました")
    val permissionDeniedDetail = t("Android VPN permission was denied. Yume cannot create a VPN interface without it.", "Разрешение Android VPN было отклонено. Yume не может создать VPN-интерфейс без него.", "Android の VPN 権限が拒否されました。Yume は権限なしでは VPN インターフェースを作成できません。")
    val vpnPrepareFailedHeadline = t("VPN prepare failed", "Не удалось подготовить VPN", "VPN の準備に失敗しました")
    val importedCaFallbackName = t("Imported CA", "Импортированный CA", "インポートしたCA")
    val importedAuthKeyFallbackName = t("Imported auth key", "Импортированный ключ авторизации", "インポートした認証キー")

    val upstreamLostHeadline = t("Upstream lost", "Верхняя сеть потеряна", "上流ネットワーク切断")
    val upstreamLostDetail = t("Wi-Fi or cellular disappeared. Yume is waiting to rebuild the authenticated control path.", "Wi-Fi или мобильная сеть пропали. Yume ждет, чтобы заново построить аутентифицированный управляющий путь.", "Wi-Fi またはモバイル回線が切れました。Yume は認証済み制御経路の再構築を待機しています。")
    val waitingForNetworkHeadline = t("Waiting for network", "Ожидание сети", "ネットワーク待機中")
    val waitingForNetworkDetail = t("Wi-Fi or cellular is unavailable, so Yume cannot rebuild the protected control path yet.", "Wi-Fi или мобильная сеть недоступны, поэтому Yume пока не может заново построить защищенный управляющий путь.", "Wi-Fi またはモバイル回線が利用できないため、Yume はまだ保護された制御経路を再構築できません。")
    val connectingHeadline = t("Connecting", "Подключение", "接続中")
    val disconnectingHeadline = t("Disconnecting", "Отключение", "切断中")
    val disconnectedHeadline = t("Disconnected", "Отключено", "切断済み")
    val connectionFailedHeadline = t("Connection failed", "Сбой подключения", "接続失敗")
    val verifyingRouteHeadline = t("Verifying route", "Проверка маршрута", "経路確認中")
    val verifyingRouteDetail = t("Route is up. Verifying traffic.", "Маршрут поднят. Проверка трафика.", "経路は確立されています。トラフィックを確認中です。")
    val protectedHeadline = t("Protected", "Защищено", "保護中")
    val protectedDetail = t("Traffic is flowing through Yume.", "Трафик проходит через Yume.", "トラフィックは Yume を通っています。")
    val authenticatingNotification = t("Authenticating to Yume over 443", "Аутентификация в Yume через 443", "Yume に 443 で認証中")
    val protectedNotification = t("Protected via Yume", "Защищено через Yume", "Yume で保護中")
    val notificationConnected = t("Protected", "Защищено", "保護中")
    val notificationConnecting = t("Connecting", "Подключение", "接続中")
    val notificationVerifying = t("Verifying", "Проверка", "確認中")
    val notificationDisconnecting = t("Disconnecting", "Отключение", "切断中")
    val notificationError = t("Connection failed", "Сбой подключения", "接続失敗")

    val wifi = "Wi-Fi"
    val cellular = t("Cellular", "Мобильная сеть", "モバイル")
    val ethernet = t("Ethernet", "Ethernet", "イーサネット")
    val vpn = "VPN"
    val otherNetwork = t("Other", "Другая", "その他")
    val founderCeoRole = t(
        "Founder & CEO, FixCraft, Inc.",
        "Основатель и CEO, FixCraft, Inc.",
        "FixCraft, Inc. 創業者兼CEO",
    )
    val leadDeveloperDesignerRole = t(
        "Lead developer and designer.",
        "Главный разработчик и дизайнер.",
        "主任開発者兼デザイナーです。",
    )
    val bestEmployeeOfYearLabel = t(
        "#1 Best Employee of the Year",
        "#1 лучший сотрудник года",
        "年間ベスト社員 #1",
    )
    val chatgptCreditRole = t(
        "Debug support and fast implementation help for the main developer.",
        "Помощь с отладкой и быстрыми правками для основного разработчика.",
        "メイン開発者向けのデバッグ支援と迅速な実装補助。",
    )
    val openSourceComponentLabel = t(
        "Open source component",
        "Компонент с открытым исходным кодом",
        "オープンソースコンポーネント",
    )
    val yumeSocksTunnelCreditRole = t(
        "Originally derived from hev-socks5-tunnel and hev-task-system by hev <r@hev.cc> (MIT), and lwIP by the Swedish Institute of Computer Science (BSD-3-Clause). Significantly modified for YUME on Android; ongoing development is independent and does not track upstream.",
        "Изначально основан на hev-socks5-tunnel и hev-task-system от hev <r@hev.cc> (MIT) и lwIP от Шведского института компьютерных наук (BSD-3-Clause). Существенно изменён для YUME на Android; дальнейшая разработка ведётся независимо и не синхронизируется с апстримом.",
        "もとは hev <r@hev.cc> による hev-socks5-tunnel と hev-task-system（MIT）、およびスウェーデン計算機科学研究所による lwIP（BSD-3-Clause）から派生しています。YUME（Android）向けに大幅に改変されており、今後の開発はアップストリームとは独立して進められます。",
    )
    val developerCreditQuote = "\"Credit is only fair when everyone has their place, and AI deserves to be recognized too.\""

    val founderQuote = t(
        "\"Building on open source is one of the most empowering experiences in software development. You do not have to wait for someone else to fix an issue or add a feature — you can build the solution yourself.\"",
        "\"Разработка на основе open source — один из самых вдохновляющих опытов в создании программного обеспечения. Вам не нужно ждать, пока кто-то исправит проблему или добавит новую функцию — вы можете создать решение самостоятельно.\"",
        "\"オープンソースの上に開発することは、ソフトウェア開発において最も力を与えてくれる体験の一つです。問題の修正や機能の追加を誰かに待つ必要はなく、自分で解決策を作ることができます。\"",
    )

    fun connectionTechniqueName(technique: ConnectionTechnique): String = when (technique) {
        ConnectionTechnique.Tun2SocksStable -> technique.canonicalLabel()
        ConnectionTechnique.NativeDirectBeta -> technique.canonicalLabel()
    }

    fun connectionTechniqueDescription(technique: ConnectionTechnique): String = when (technique) {
        ConnectionTechnique.Tun2SocksStable -> t(
            "Stable Android path through tun2socks with TCP and UDP support.",
            "Стабильный Android-маршрут через tun2socks с поддержкой TCP и UDP.",
            "TCP と UDP をサポートする、tun2socks ベースの安定した Android 経路です。",
        )
        ConnectionTechnique.NativeDirectBeta -> t(
            "Direct Yume TCP and UDP path. Fixed tuning. IPv6 stays fail-closed.",
            "Прямой Yume TCP- и UDP-путь. Фиксированная нативная настройка. IPv6 остается в режиме fail-closed.",
            "Yume の TCP / UDP を直接使う経路です。調整は固定で、IPv6 は引き続きフェイルクローズです。",
        )
    }

    val nativeDirectFixedTuningName = t(
        "Fixed native tuning",
        "Фиксированная нативная настройка",
        "固定ネイティブ調整",
    )

    val nativeDirectFixedTuningDescription = t(
        "Native direct uses fixed tuning. Performance profiles apply only to Tun2Socks stable.",
        "Native direct использует фиксированную настройку. Профили производительности применяются только к Tun2Socks stable.",
        "Native direct は固定チューニングです。パフォーマンスプロファイルは Tun2Socks stable にのみ適用されます。",
    )

    fun performanceProfileName(profile: PerformanceProfile): String = when (profile) {
        PerformanceProfile.Latency -> t(profile.canonicalLabel(), "Задержка", "低遅延")
        PerformanceProfile.Balanced -> t(profile.canonicalLabel(), "Баланс", "バランス")
        PerformanceProfile.Throughput -> t(profile.canonicalLabel(), "Пропускная способность", "スループット")
    }

    fun performanceProfileDescription(profile: PerformanceProfile): String = when (profile) {
        PerformanceProfile.Latency -> t("Lower queueing for interactive traffic.", "Меньше очередей для интерактивного трафика.", "対話的な通信向けにキューを短くします。")
        PerformanceProfile.Balanced -> t("Default mixed-use profile.", "Профиль по умолчанию для смешанного использования.", "標準のバランス型プロファイルです。")
        PerformanceProfile.Throughput -> t("Larger buffers for sustained transfers.", "Более крупные буферы для долгих передач.", "長時間の転送向けにバッファを大きくします。")
    }

    fun themeColorModeName(mode: ThemeColorMode): String = when (mode) {
        ThemeColorMode.Yume -> "Yume"
        ThemeColorMode.System -> t("System", "Системная", "システム")
    }

    fun themeColorModeDescription(mode: ThemeColorMode): String = when (mode) {
        ThemeColorMode.Yume -> t("Muted built-in colors.", "Приглушенные встроенные цвета.", "落ち着いた組み込みカラーを使います。")
        ThemeColorMode.System -> t("Material You on Android 12+.", "Material You на Android 12+.", "Android 12+ の Material You を使います。")
    }

    fun languageName(appLanguage: AppLanguage): String = when (appLanguage) {
        AppLanguage.SystemDefault -> t("System default", "Системный", "システム既定")
        AppLanguage.Maintained.English -> "English"
        AppLanguage.Maintained.Russian -> "Русский"
        AppLanguage.Maintained.Japanese -> "日本語"
        AppLanguage.Maintained.Ukrainian -> "Українська"
        is AppLanguage.AutoTranslated -> AppLanguageCatalog.displayName(appLanguage)
    }

    fun languageDescription(appLanguage: AppLanguage): String? = when (appLanguage) {
        AppLanguage.SystemDefault -> followsSystemLabel
        AppLanguage.Maintained.English -> originalLabel
        AppLanguage.Maintained.Russian -> nativeLabel
        AppLanguage.Maintained.Japanese -> nativeLabel
        AppLanguage.Maintained.Ukrainian -> nativeLabel
        is AppLanguage.AutoTranslated -> onDeviceTranslationLabel
    }

    fun languageSummary(appLanguage: AppLanguage): String {
        val description = when (appLanguage) {
            is AppLanguage.AutoTranslated -> autoShortLabel
            else -> languageDescription(appLanguage)
        }
        return if (description.isNullOrBlank()) {
            languageName(appLanguage)
        } else {
            "${languageName(appLanguage)} ($description)"
        }
    }

    fun logLanguageName(logLanguage: LogLanguage): String = when (logLanguage) {
        LogLanguage.FollowApp -> followAppLabel
        LogLanguage.English -> "English"
        LogLanguage.Russian -> "Русский"
        LogLanguage.Japanese -> "日本語"
        LogLanguage.Ukrainian -> "Українська"
    }

    fun logLanguageDescription(logLanguage: LogLanguage): String? = when (logLanguage) {
        LogLanguage.FollowApp -> null
        LogLanguage.English -> originalLabel
        LogLanguage.Russian -> nativeLabel
        LogLanguage.Japanese -> nativeLabel
        LogLanguage.Ukrainian -> nativeLabel
    }

    fun logLanguageSummary(logLanguage: LogLanguage): String {
        val description = logLanguageDescription(logLanguage)
        return if (description.isNullOrBlank()) {
            logLanguageName(logLanguage)
        } else {
            "${logLanguageName(logLanguage)} ($description)"
        }
    }

    fun preparingLanguage(languageName: String): String {
        return tf("Getting %s ready.", "Подготавливаем %s.", "%s を準備しています。", languageName)
    }

    fun downloadingLanguage(languageName: String): String {
        return tf(
            "Downloading %s for offline use.",
            "%s загружается для офлайн-работы.",
            "%s をオフライン用にダウンロードしています。",
            languageName,
        )
    }

    fun translatingLanguage(languageName: String, done: Int, total: Int): String {
        return tf("Translating %s: %d of %d", "Переводим %s: %d из %d", "%s を翻訳中: %d / %d", languageName, done, total)
    }

    fun languagePreparationFailed(languageName: String): String {
        return tf(
            "Couldn't finish %s yet. Try again with a stable connection.",
            "Не удалось завершить подготовку %s. Попробуйте еще раз при стабильном соединении.",
            "%s の準備を完了できませんでした。安定した接続で再試行してください。",
            languageName,
        )
    }

    fun deletedLanguageDownload(languageName: String): String {
        return tf("Removed %s download.", "Загрузка %s удалена.", "%s のダウンロードを削除しました。", languageName)
    }

    fun failedToDeleteLanguageDownload(languageName: String): String {
        return tf(
            "Couldn't remove %s download.",
            "Не удалось удалить загрузку %s.",
            "%s のダウンロードを削除できませんでした。",
            languageName,
        )
    }

    fun materialSaved(typeLabel: String, displayName: String): String {
        return tf("%s saved: %s", "%s сохранен: %s", "%s を保存しました: %s", typeLabel, displayName)
    }

    fun materialImported(typeLabel: String, displayName: String): String {
        return tf("%s imported: %s", "%s импортирован: %s", "%s をインポートしました: %s", typeLabel, displayName)
    }

    fun failedToReadSelected(typeLabel: String): String {
        val normalizedType = typeLabel.lowercase(Locale.ROOT)
        return tf("Failed to read the selected %s", "Не удалось прочитать выбранный %s", "選択した%sを読み取れませんでした", normalizedType)
    }

    fun materialTextEmpty(typeLabel: String): String {
        return tf("No text provided for %s", "Для %s не указан текст", "%s のテキストが入力されていません", typeLabel)
    }

    fun failedToSave(typeLabel: String): String {
        val normalizedType = typeLabel.lowercase(Locale.ROOT)
        return tf("Failed to save %s", "Не удалось сохранить %s", "%s を保存できませんでした", normalizedType)
    }

    fun failedToImport(typeLabel: String): String {
        val normalizedType = typeLabel.lowercase(Locale.ROOT)
        return tf("Failed to import %s", "Не удалось импортировать %s", "%s をインポートできませんでした", normalizedType)
    }

    fun saving(typeLabel: String): String {
        return tf("Saving %s.", "Сохранение: %s.", "%s を保存しています。", typeLabel)
    }

    fun importing(typeLabel: String): String {
        return tf("Importing %s.", "Импорт: %s.", "%s をインポートしています。", typeLabel)
    }

    fun readingSelectedFile(typeLabel: String): String {
        return tf("Reading selected %s file.", "Чтение выбранного файла: %s.", "選択した%sファイルを読み込んでいます。", typeLabel)
    }

    fun unlockAuthKeyDetail(authKeyName: String): String {
        return tf("Please unlock the key %s.", "Разблокируйте ключ %s.", "キー %s を解除してください。", authKeyName)
    }

    fun unlockingAuthKey(authKeyName: String): String {
        return tf("Unlocking %s.", "Разблокировка %s.", "%s を解除しています。", authKeyName)
    }

    fun savingServer(profileName: String): String {
        return tf("Saving %s.", "Сохранение: %s.", "%s を保存しています。", profileName)
    }

    val updatingAppDataProtection = t(
        "Updating app data protection.",
        "Обновление защиты данных приложения.",
        "アプリデータ保護を更新しています。",
    )

    fun processingText(actionLabel: String, modeLabel: String): String {
        return tf("%s with %s.", "%s: %s.", "%2\$s で%1\$sしています。", actionLabel, modeLabel)
    }

    fun logAndroidNeedsVpnPermission(): String = t(
        "Android needs VPN permission before Yume can create the interface.",
        "Android требует разрешение VPN, прежде чем Yume сможет создать интерфейс.",
        "Yume がインターフェースを作成する前に、Android の VPN 権限が必要です。",
    )

    fun logAndroidVpnPermissionGranted(): String = t(
        "Android VPN permission granted.",
        "Разрешение Android VPN получено.",
        "Android の VPN 権限が許可されました。",
    )

    fun logAndroidVpnPermissionDenied(): String = t(
        "Android VPN permission was denied.",
        "В разрешении Android VPN отказано.",
        "Android の VPN 権限が拒否されました。",
    )

    fun logRequestingNotificationPermission(): String = t(
        "Requesting Android notification permission for VPN status updates.",
        "Запрашивается разрешение Android на уведомления для статуса VPN.",
        "VPN 状態更新のために Android の通知権限を要求しています。",
    )

    fun logOpeningVpnPermissionDialog(): String = t(
        "Opening the Android VPN permission dialog.",
        "Открывается диалог разрешения Android VPN.",
        "Android の VPN 権限ダイアログを開いています。",
    )

    fun logAndroidVpnPermissionAlreadyGranted(): String = t(
        "Android VPN permission is already granted.",
        "Разрешение Android VPN уже получено.",
        "Android の VPN 権限はすでに許可されています。",
    )

    fun logSelectedServer(name: String, endpointLabel: String): String =
        tf("Selected server: %s (%s).", "Выбранный сервер: %s (%s).", "選択したサーバー: %s (%s)。", name, endpointLabel)

    fun logSelectedAnonymCaUnavailable(): String = t(
        "The selected anonym CA is unavailable.",
        "Выбранный анонимный CA недоступен.",
        "選択した匿名CAは利用できません。",
    )

    fun logSelectedAuthKeyRequired(): String = t(
        "This server does not have an auth key assigned.",
        "Для этого сервера не назначен ключ авторизации.",
        "このサーバーには認証キーが割り当てられていません。",
    )

    fun logSelectedAuthKeyUnavailable(): String = t(
        "The selected auth key is unavailable.",
        "Выбранный ключ авторизации недоступен.",
        "選択した認証キーは利用できません。",
    )

    fun logCredentialsReady(): String = t(
        "Local CA and auth key are ready. Starting the Android VPN service.",
        "Локальный CA и ключ авторизации готовы. Запускается Android VPN service.",
        "ローカル CA と認証キーの準備ができました。Android VPN サービスを開始します。",
    )

    fun logTechnique(summary: String): String =
        tf("Technique: %s.", "Техника: %s.", "方式: %s。", summary)

    fun localizedHeadline(headline: String): String = when (headline) {
        "Disconnected" -> disconnectedHeadline
        "Connection failed" -> connectionFailedHeadline
        "Verifying route" -> verifyingRouteHeadline
        "Protected" -> protectedHeadline
        "Connecting" -> connectingHeadline
        "Disconnecting" -> disconnectingHeadline
        "Waiting for network" -> waitingForNetworkHeadline
        "Upstream lost" -> upstreamLostHeadline
        "Permission required" -> permissionRequiredHeadline
        else -> headline
    }

    fun localizedDetail(detail: String): String = when (detail) {
        "" -> ""
        "Route is up. Verifying traffic." -> verifyingRouteDetail
        "Traffic is flowing through Yume." -> protectedDetail
        "Wi-Fi or cellular is unavailable, so Yume cannot rebuild the protected control path yet." -> waitingForNetworkDetail
        "Wi-Fi or cellular disappeared. Yume is waiting to rebuild the authenticated control path." -> upstreamLostDetail
        "Android needs permission to start the VPN." -> permissionRequiredDetail
        else -> detail
    }

    fun logStartingAndroidSession(name: String, endpointLabel: String): String =
        tf(
            "Starting Yume Android session for %s (%s).",
            "Запуск Android-сессии Yume для %s (%s).",
            "%s (%s) の Yume Android セッションを開始しています。",
            name,
            endpointLabel,
        )

    fun logRequestedProtectionPolicy(value: String): String =
        tf("Requested protection policy: %s.", "Запрошенная политика защиты: %s.", "要求された保護ポリシー: %s。", value)

    fun logLoadedAnonymCa(displayName: String): String =
        tf("Loaded anonym CA: %s.", "Загружен анонимный CA: %s.", "匿名CAを読み込みました: %s。", displayName)

    fun logLoadedAuthKey(displayName: String): String =
        tf("Loaded auth key: %s.", "Загружен ключ авторизации: %s.", "認証キーを読み込みました: %s。", displayName)

    fun logOpeningAuthenticatedControlPath(networkLabel: String): String =
        tf(
            "Opening authenticated Yume control path on %s.",
            "Открывается аутентифицированный управляющий путь Yume через %s.",
            "%s で認証済みの Yume 制御経路を開いています。",
            networkLabel,
        )

    fun logProtectedControlSocket(): String = t(
        "Protected the control socket from the VPN path.",
        "Управляющий сокет выведен из VPN-маршрута.",
        "制御ソケットを VPN 経路から保護しました。",
    )

    fun logSelectedIpv4ControlEndpoint(endpoint: String, host: String): String =
        tf(
            "Selected IPv4 control endpoint %s for %s.",
            "Выбран IPv4 управляющий endpoint %s для %s.",
            "%2\$s 用に IPv4 制御エンドポイント %1\$s を選択しました。",
            endpoint,
            host,
        )

    fun logTlsHandshakeCompleted(detail: String): String =
        tf("TLS handshake completed: %s.", "TLS-рукопожатие завершено: %s.", "TLS ハンドシェイクが完了しました: %s。", detail)

    fun logReceivedAuthChallenge(): String = t(
        "Received Yume AUTH challenge.",
        "Получен AUTH challenge от Yume.",
        "Yume AUTH チャレンジを受信しました。",
    )

    fun logAuthenticatedToServer(): String = t(
        "Authenticated to the Yume server.",
        "Аутентификация на сервере Yume выполнена.",
        "Yume サーバーへの認証が完了しました。",
    )

    fun logRunningRelayCanary(target: String): String =
        tf("Running a Yume relay canary via %s.", "Запуск relay canary Yume через %s.", "%s 経由で Yume リレーのカナリア確認を実行しています。", target)

    fun logVerifiedAppRelayTraffic(exitIp: String): String =
        tf(
            "Verified app relay traffic via Yume. Exit IP: %s.",
            "Трафик приложения через Yume подтвержден. Выходной IP: %s.",
            "Yume 経由のアプリ中継トラフィックを確認しました。出口 IP: %s。",
            exitIp,
        )

    fun logRunningRoutedProbe(): String = t(
        "Running an in-app routed Android traffic probe through Yume.",
        "Запускается внутриприкладной Android probe маршрутизации через Yume.",
        "Yume 経由でアプリ内の Android ルーティング確認を実行しています。",
    )

    fun logVerifiedSystemTraffic(exitIp: String): String =
        tf(
            "Verified system traffic via Yume. Exit IP: %s.",
            "Системный трафик через Yume подтвержден. Выходной IP: %s.",
            "Yume 経由のシステムトラフィックを確認しました。出口 IP: %s。",
            exitIp,
        )

    fun logTlsHandshakeFailed(detail: String): String =
        tf("TLS handshake failed: %s", "TLS-рукопожатие не удалось: %s", "TLS ハンドシェイクに失敗しました: %s", detail)

    fun logTlsProviderHandshakeFailed(): String = t(
        "TLS handshake failed on this Android TLS provider",
        "TLS-рукопожатие не удалось на этом Android TLS provider",
        "この Android TLS プロバイダでは TLS ハンドシェイクに失敗しました",
    )

    fun logHandshakeFailed(): String = t("Handshake failed", "Рукопожатие не удалось", "ハンドシェイクに失敗しました")

    fun logVpnPrepareFailed(detail: String): String =
        tf("VpnService.prepare() failed: %s", "VpnService.prepare() завершился ошибкой: %s", "VpnService.prepare() に失敗しました: %s", detail)

    fun logUnhandledVpnCoroutineFailure(detail: String): String =
        tf("Unhandled VPN coroutine failure: %s", "Необработанная ошибка VPN coroutine: %s", "VPN コルーチンで未処理の障害が発生しました: %s", detail)

    fun logNoUpstreamNetwork(): String = t(
        "No upstream network is available. Waiting for Wi-Fi or cellular.",
        "Нет доступной внешней сети. Ожидание Wi-Fi или мобильной сети.",
        "利用可能な上流ネットワークがありません。Wi-Fi またはモバイル回線を待機しています。",
    )

    fun logUpstreamNetworkLost(): String = t(
        "Upstream network lost. Waiting for Wi-Fi or cellular.",
        "Внешняя сеть потеряна. Ожидание Wi-Fi или мобильной сети.",
        "上流ネットワークが失われました。Wi-Fi またはモバイル回線を待機しています。",
    )

    fun logFailedToVerifySystemRouting(detail: String): String =
        tf(
            "Failed to verify systemwide Yume routing: %s",
            "Не удалось подтвердить системную маршрутизацию Yume: %s",
            "システム全体の Yume ルーティング確認に失敗しました: %s",
            detail,
        )

    fun removed(displayName: String): String = tf("%s removed", "%s удален", "%s を削除しました", displayName)

    fun reconnectFor(label: String): String {
        return tf("Reconnect for %s.", "Переподключитесь для %s.", "%s を有効にするには再接続してください。", label)
    }

    fun checkingKeysFor(endpointLabel: String): String {
        return tf(
            "Checking keys and VPN permission for %s.",
            "Проверка ключей и VPN-разрешения для %s.",
            "%s のキーと VPN 権限を確認しています。",
            endpointLabel,
        )
    }

    fun loadingKeysFor(endpointLabel: String): String {
        return tf(
            "Loading keys and opening a control path to %s.",
            "Загрузка ключей и открытие управляющего пути к %s.",
            "%s への制御経路を開くためにキーを読み込んでいます。",
            endpointLabel,
        )
    }

    fun usingAnonymCa(displayName: String): String {
        return tf("Using %s", "Используется %s", "%s を使用中", displayName)
    }

    fun selectedServerSummary(
        profileName: String,
        selectedCaName: String,
        selectedAuthKeyName: String,
    ): String {
        return tf(
            "Selected server: %s, %s: %s, %s: %s",
            "Выбранный сервер: %s, %s: %s, %s: %s",
            "選択中のサーバー: %s、%s: %s、%s: %s",
            profileName,
            anonymCaLabel,
            selectedCaName,
            authKeyLabel,
            selectedAuthKeyName,
        )
    }

    fun currentPhaseSummary(phase: String, technique: String, profile: String): String {
        return tf(
            "Current phase: %s • Technique: %s • Profile: %s",
            "Текущая фаза: %s • Техника: %s • Профиль: %s",
            "現在のフェーズ: %s • 方式: %s • プロファイル: %s",
            phase,
            technique,
            profile,
        )
    }

    fun appVersion(value: String): String = tf("App: %s", "Приложение: %s", "アプリ: %s", value)

    fun routePath(technique: String, profile: String): String {
        return tf("Path: %s • %s", "Маршрут: %s • %s", "経路: %s • %s", technique, profile)
    }

    fun exitIpAndUpstream(exitIp: String, upstream: String): String {
        return tf(
            "Exit IP: %s • Upstream: %s",
            "Выходной IP: %s • Верхняя сеть: %s",
            "出口IP: %s • 上流: %s",
            exitIp,
            upstream,
        )
    }

    fun anonymSummary(required: Boolean): String {
        val value = if (required) anonymRequired else anonymOptional
        return "$anonymLabel: $value"
    }

    fun authKeySummary(displayName: String): String {
        return tf("Auth key: %s", "Ключ авторизации: %s", "認証キー: %s", displayName)
    }

    fun materialTypeLabel(type: SecureMaterialType): String = when (type) {
        SecureMaterialType.AnonymCa -> anonymCaLabel
        SecureMaterialType.AuthKey -> authKeyLabel
    }

    fun anonymWithVerity(veritySummary: String): String = "$anonymLabel $veritySummary"

    fun boolValue(value: Boolean): String = if (value) yes else no

    fun notificationLocation(flagEmoji: String?, countryName: String?): String {
        val normalizedFlag = flagEmoji?.trim().orEmpty()
        val normalizedCountry = countryName?.trim().orEmpty()
        return when {
            normalizedFlag.isNotEmpty() && normalizedCountry.isNotEmpty() -> "$normalizedFlag $normalizedCountry"
            normalizedCountry.isNotEmpty() -> normalizedCountry
            normalizedFlag.isNotEmpty() -> normalizedFlag
            else -> t("Unknown location", "Неизвестная локация", "不明な場所")
        }
    }

    fun notificationProtectedIn(location: String): String {
        return tf("Protected in %s", "Защищено через %s", "%s に接続中", location)
    }

    fun notificationTrafficTotals(download: String, upload: String): String {
        return "↓ $download   ↑ $upload"
    }

    fun buildVersionLabel(versionName: String, versionCode: Int, debug: Boolean): String {
        return if (debug) {
            tf("DEBUG build v%s (%d)", "DEBUG-сборка v%s (%d)", "DEBUG ビルド v%s (%d)", versionName, versionCode)
        } else {
            "v$versionName"
        }
    }

    fun phaseStatus(phase: VpnPhase, protected: Boolean, proxyReady: Boolean): String {
        return when (phase) {
            VpnPhase.Idle -> t("Idle", "Ожидание", "待機中")
            VpnPhase.PermissionRequired -> t("Permission needed", "Нужно разрешение", "権限が必要")
            VpnPhase.Connecting -> t("Connecting", "Подключение", "接続中")
            VpnPhase.Verifying -> t("Verifying", "Проверка", "確認中")
            VpnPhase.Connected -> when {
                protected -> t("Protected", "Защищено", "保護中")
                proxyReady -> t("Proxy ready", "Прокси готов", "プロキシ準備完了")
                else -> t("Connected", "Подключено", "接続済み")
            }
            VpnPhase.Disconnecting -> t("Disconnecting", "Отключение", "切断中")
            VpnPhase.Error -> t("Error", "Ошибка", "エラー")
        }
    }

    companion object {
        private val translationSeeds: List<TranslationSeed> by lazy {
            val collector = linkedSetOf<TranslationSeed>()
            val seedStrings = AppStrings(
                language = ResolvedAppLanguage.English,
                seedCollector = collector,
            )
            seedStrings.javaClass.declaredMethods
                .filter { method ->
                    method.parameterCount == 0 &&
                        method.returnType == String::class.java &&
                        method.name.startsWith("get")
                }
                .sortedBy { it.name }
                .forEach { method ->
                    runCatching { method.invoke(seedStrings) }
                }
            collector += TranslationSeed("Idle", "Ожидание", "待機中")
            collector += TranslationSeed("Permission needed", "Нужно разрешение", "権限が必要")
            collector += TranslationSeed("Connecting", "Подключение", "接続中")
            collector += TranslationSeed("Verifying", "Проверка", "確認中")
            collector += TranslationSeed("Protected", "Защищено", "保護中")
            collector += TranslationSeed("Proxy ready", "Прокси готов", "プロキシ準備完了")
            collector += TranslationSeed("Connected", "Подключено", "接続済み")
            collector += TranslationSeed("Disconnecting", "Отключение", "切断中")
            collector += TranslationSeed("Error", "Ошибка", "エラー")
            collector.toList()
        }

        fun forLanguage(
            language: ResolvedAppLanguage,
            autoTranslate: ((TranslationSeed) -> String)? = null,
        ): AppStrings = AppStrings(language, autoTranslate)

        fun translationSeeds(): List<TranslationSeed> = translationSeeds
    }
}

fun appStrings(
    language: AppLanguage,
    systemLocale: Locale = Locale.getDefault(),
    autoTranslate: ((TranslationSeed) -> String)? = null,
): AppStrings = AppStrings.forLanguage(language.resolve(systemLocale), autoTranslate)

fun appStrings(
    context: Context,
    language: AppLanguage,
): AppStrings {
    val resolved = AppLocaleManager.resolveLanguage(context, language)
    val autoTranslate = when (resolved) {
        is ResolvedAppLanguage.AutoTranslated -> { seed: TranslationSeed ->
            AutoTranslationRuntime.getOrQueue(
                context = context,
                targetLocaleTag = resolved.localeTag,
                seed = seed,
            )
        }
        else -> null
    }
    return AppStrings.forLanguage(resolved, autoTranslate)
}

private val LocalAppStrings = staticCompositionLocalOf { appStrings(AppLanguage.SystemDefault) }

@Composable
fun ProvideAppStrings(
    context: Context,
    language: AppLanguage,
    content: @Composable () -> Unit,
) {
    val resolved = remember(context, language) { AppLocaleManager.resolveLanguage(context, language) }
    val autoLocaleTag = (resolved as? ResolvedAppLanguage.AutoTranslated)?.localeTag
    val revision by remember(autoLocaleTag) {
        autoLocaleTag?.let(AutoTranslationRuntime::revision)
            ?: MutableStateFlow(0)
    }.collectAsState()
    LaunchedEffect(autoLocaleTag) {
        autoLocaleTag?.let { localeTag ->
            AutoTranslationRuntime.prewarm(
                context = context,
                targetLocaleTag = localeTag,
                seeds = AppStrings.translationSeeds(),
            )
        }
    }
    val strings = remember(language, resolved, revision) {
        when (resolved) {
            is ResolvedAppLanguage.AutoTranslated -> AppStrings.forLanguage(resolved) { seed ->
                AutoTranslationRuntime.getOrQueue(
                    context = context,
                    targetLocaleTag = resolved.localeTag,
                    seed = seed,
                )
            }
            else -> AppStrings.forLanguage(resolved)
        }
    }
    CompositionLocalProvider(LocalAppStrings provides strings, content = content)
}

@Composable
fun appStrings(): AppStrings = LocalAppStrings.current
