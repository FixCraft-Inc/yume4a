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
import com.fixcraft.yume.vpn.VpnPhase
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow

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
    private fun t(english: String, russian: String, japanese: String = english): String {
        val seed = TranslationSeed(english, russian, japanese)
        seedCollector?.add(seed)
        return when (language) {
            ResolvedAppLanguage.English -> english
            ResolvedAppLanguage.Russian -> russian
            ResolvedAppLanguage.Japanese -> japanese
            is ResolvedAppLanguage.AutoTranslated -> autoTranslate?.invoke(seed) ?: english
        }
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
    val amoledTitle = t("AMOLED black", "Глубокий черный", "AMOLEDブラック")
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
        "Save trust and auth material locally, pick a gateway, and bring up a loop-safe systemwide Yume VPN on Android.",
        "Сохраните локально доверенные материалы и ключи, выберите шлюз и поднимите loop-safe системный Yume VPN на Android.",
        "信頼情報と認証情報を端末に保存し、ゲートウェイを選んで、Android 全体を保護するループセーフな Yume VPN を起動します。",
    )
    val setUpYumeVpnSubtitle = t(
        "Import trust or auth material at any time, then add your first server profile.",
        "Импортируйте доверенные данные или ключ авторизации в любое время, затем добавьте первый профиль сервера.",
        "信頼情報や認証情報は後からでも追加できます。まずは最初のサーバープロファイルを追加してください。",
    )
    val noServersConfigured = t("No servers configured", "Серверы не настроены", "サーバー未設定")
    val addFirstGatewayTitle = t("Add your first gateway", "Добавьте первый шлюз", "最初のゲートウェイを追加")
    val addFirstGatewayDetail = t(
        "Server profiles store the host, TLS name, DNS, MTU, Anonym CA, and auth key. The first saved profile becomes active.",
        "Профиль сервера хранит хост, TLS-имя, DNS, MTU, анонимный CA и ключ авторизации. Первый сохраненный профиль станет активным.",
        "サーバープロファイルにはホスト、TLS 名、DNS、MTU、匿名CA、認証キーを保存します。最初に保存したプロファイルが選択されます。",
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
        "Imported PEM certificates and keys are encrypted at rest inside the app and stored outside cloud backup. Auth keys also keep a BaseFWX layer at rest.",
        "Импортированные PEM-сертификаты и ключи шифруются при хранении внутри приложения и не попадают в облачное резервное копирование. Ключи авторизации также защищены слоем BaseFWX.",
        "インポートしたPEM証明書とキーはアプリ内で暗号化保存され、クラウドバックアップ対象外です。認証キーには保存時に BaseFWX レイヤーも適用されます。",
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
    val runtimeAppliedBridgeTuningTitle = t("Applied bridge tuning", "Примененная bridge-настройка", "適用されたブリッジ調整")
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
    val runtimePacketNativeStatus = t("packet-native data plane", "packet-native data plane", "パケットネイティブデータプレーン")
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
    val nativeDirectUnavailable = t("Native direct beta is unavailable in this build.", "Native direct beta недоступен в этой сборке.", "Native direct beta はこのビルドでは利用できません。")
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
    val developerCreditQuote = "\"Credit is only fair when everyone has their place, and AI deserves to be recognized too.\""
    val founderThanksQuote = t(
        "\"We only got this far because my friend ChatGPT is here. I could not have handled all this app work without that help.\"",
        "\"Мы дошли так далеко только потому, что рядом был мой друг ChatGPT. Я бы не справился с таким объемом работы над приложением без этой помощи.\"",
        "\"ここまで来られたのは、友人の ChatGPT がいてくれたからです。この量のアプリ開発を、その助けなしで進めることはできませんでした。\"",
    )

    fun connectionTechniqueName(technique: ConnectionTechnique): String = when (technique) {
        ConnectionTechnique.Tun2SocksStable -> "Tun2Socks stable"
        ConnectionTechnique.NativeDirectBeta -> t("Native direct beta", "Native direct beta")
    }

    fun connectionTechniqueDescription(technique: ConnectionTechnique): String = when (technique) {
        ConnectionTechnique.Tun2SocksStable -> t(
            "Stable Android path through tun2socks.",
            "Стабильный Android-маршрут через tun2socks.",
            "tun2socks を使う安定した Android 経路です。",
        )
        ConnectionTechnique.NativeDirectBeta -> t(
            "Bridge-backed beta path with tighter tuning.",
            "Бета-маршрут с bridge и более точной настройкой.",
            "ブリッジ経由のベータ経路で、より細かく調整されています。",
        )
    }

    fun performanceProfileName(profile: PerformanceProfile): String = when (profile) {
        PerformanceProfile.Latency -> t("Latency", "Задержка", "低遅延")
        PerformanceProfile.Balanced -> t("Balanced", "Баланс", "バランス")
        PerformanceProfile.Throughput -> t("Throughput", "Пропускная способность", "スループット")
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
        is AppLanguage.AutoTranslated -> AppLanguageCatalog.displayName(appLanguage)
    }

    fun languageDescription(appLanguage: AppLanguage): String? = when (appLanguage) {
        AppLanguage.SystemDefault -> followsSystemLabel
        AppLanguage.Maintained.English -> originalLabel
        AppLanguage.Maintained.Russian -> nativeLabel
        AppLanguage.Maintained.Japanese -> nativeLabel
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
    }

    fun logLanguageDescription(logLanguage: LogLanguage): String? = when (logLanguage) {
        LogLanguage.FollowApp -> null
        LogLanguage.English -> originalLabel
        LogLanguage.Russian -> nativeLabel
        LogLanguage.Japanese -> nativeLabel
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
        return t(
            "Getting $languageName ready.",
            "Подготавливаем $languageName.",
            "$languageName を準備しています。",
        )
    }

    fun downloadingLanguage(languageName: String): String {
        return t(
            "Downloading $languageName for offline use.",
            "$languageName загружается для офлайн-работы.",
            "$languageName をオフライン用にダウンロードしています。",
        )
    }

    fun translatingLanguage(languageName: String, done: Int, total: Int): String {
        return t(
            "Translating $languageName: $done of $total",
            "Переводим $languageName: $done из $total",
            "$languageName を翻訳中: $done / $total",
        )
    }

    fun languagePreparationFailed(languageName: String): String {
        return t(
            "Couldn't finish $languageName yet. Try again with a stable connection.",
            "Не удалось завершить подготовку $languageName. Попробуйте еще раз при стабильном соединении.",
            "$languageName の準備を完了できませんでした。安定した接続で再試行してください。",
        )
    }

    fun deletedLanguageDownload(languageName: String): String {
        return t(
            "Removed $languageName download.",
            "Загрузка $languageName удалена.",
            "$languageName のダウンロードを削除しました。",
        )
    }

    fun failedToDeleteLanguageDownload(languageName: String): String {
        return t(
            "Couldn't remove $languageName download.",
            "Не удалось удалить загрузку $languageName.",
            "$languageName のダウンロードを削除できませんでした。",
        )
    }

    fun materialSaved(typeLabel: String, displayName: String): String {
        return t("$typeLabel saved: $displayName", "$typeLabel сохранен: $displayName", "$typeLabel を保存しました: $displayName")
    }

    fun materialImported(typeLabel: String, displayName: String): String {
        return t("$typeLabel imported: $displayName", "$typeLabel импортирован: $displayName", "$typeLabel をインポートしました: $displayName")
    }

    fun failedToReadSelected(typeLabel: String): String {
        val normalizedType = typeLabel.lowercase(Locale.ROOT)
        return t(
            "Failed to read the selected $normalizedType",
            "Не удалось прочитать выбранный $normalizedType",
            "選択した${normalizedType}を読み取れませんでした",
        )
    }

    fun materialTextEmpty(typeLabel: String): String {
        return t(
            "No text provided for $typeLabel",
            "Для $typeLabel не указан текст",
            "$typeLabel のテキストが入力されていません",
        )
    }

    fun failedToSave(typeLabel: String): String {
        val normalizedType = typeLabel.lowercase(Locale.ROOT)
        return t("Failed to save $normalizedType", "Не удалось сохранить $normalizedType", "$normalizedType を保存できませんでした")
    }

    fun failedToImport(typeLabel: String): String {
        val normalizedType = typeLabel.lowercase(Locale.ROOT)
        return t("Failed to import $normalizedType", "Не удалось импортировать $normalizedType", "$normalizedType をインポートできませんでした")
    }

    fun saving(typeLabel: String): String {
        return t(
            "Saving $typeLabel.",
            "Сохранение: $typeLabel.",
            "$typeLabel を保存しています。",
        )
    }

    fun importing(typeLabel: String): String {
        return t(
            "Importing $typeLabel.",
            "Импорт: $typeLabel.",
            "$typeLabel をインポートしています。",
        )
    }

    fun readingSelectedFile(typeLabel: String): String {
        return t(
            "Reading selected $typeLabel file.",
            "Чтение выбранного файла: $typeLabel.",
            "選択した${typeLabel}ファイルを読み込んでいます。",
        )
    }

    fun savingServer(profileName: String): String {
        return t(
            "Saving $profileName.",
            "Сохранение: $profileName.",
            "$profileName を保存しています。",
        )
    }

    val updatingAppDataProtection = t(
        "Updating app data protection.",
        "Обновление защиты данных приложения.",
        "アプリデータ保護を更新しています。",
    )

    fun processingText(actionLabel: String, modeLabel: String): String {
        return t(
            "$actionLabel with $modeLabel.",
            "$actionLabel: $modeLabel.",
            "${modeLabel} で${actionLabel}しています。",
        )
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

    fun logSelectedServer(name: String, endpointLabel: String): String = t(
        "Selected server: $name ($endpointLabel).",
        "Выбранный сервер: $name ($endpointLabel).",
        "選択したサーバー: $name ($endpointLabel)。",
    )

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

    fun logTechnique(summary: String): String = t(
        "Technique: $summary.",
        "Техника: $summary.",
        "方式: $summary。",
    )

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

    fun logStartingAndroidSession(name: String, endpointLabel: String): String = t(
        "Starting Yume Android session for $name ($endpointLabel).",
        "Запуск Android-сессии Yume для $name ($endpointLabel).",
        "$name ($endpointLabel) の Yume Android セッションを開始しています。",
    )

    fun logRequestedProtectionPolicy(value: String): String = t(
        "Requested protection policy: $value.",
        "Запрошенная политика защиты: $value.",
        "要求された保護ポリシー: $value。",
    )

    fun logLoadedAnonymCa(displayName: String): String = t(
        "Loaded anonym CA: $displayName.",
        "Загружен анонимный CA: $displayName.",
        "匿名CAを読み込みました: $displayName。",
    )

    fun logLoadedAuthKey(displayName: String): String = t(
        "Loaded auth key: $displayName.",
        "Загружен ключ авторизации: $displayName.",
        "認証キーを読み込みました: $displayName。",
    )

    fun logOpeningAuthenticatedControlPath(networkLabel: String): String = t(
        "Opening authenticated Yume control path on $networkLabel.",
        "Открывается аутентифицированный управляющий путь Yume через $networkLabel.",
        "$networkLabel で認証済みの Yume 制御経路を開いています。",
    )

    fun logProtectedControlSocket(): String = t(
        "Protected the control socket from the VPN path.",
        "Управляющий сокет выведен из VPN-маршрута.",
        "制御ソケットを VPN 経路から保護しました。",
    )

    fun logSelectedIpv4ControlEndpoint(endpoint: String, host: String): String = t(
        "Selected IPv4 control endpoint $endpoint for $host.",
        "Выбран IPv4 управляющий endpoint $endpoint для $host.",
        "$host 用に IPv4 制御エンドポイント $endpoint を選択しました。",
    )

    fun logTlsHandshakeCompleted(detail: String): String = t(
        "TLS handshake completed: $detail.",
        "TLS-рукопожатие завершено: $detail.",
        "TLS ハンドシェイクが完了しました: $detail。",
    )

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

    fun logRunningRelayCanary(target: String): String = t(
        "Running a Yume relay canary via $target.",
        "Запуск relay canary Yume через $target.",
        "$target 経由で Yume リレーのカナリア確認を実行しています。",
    )

    fun logVerifiedAppRelayTraffic(exitIp: String): String = t(
        "Verified app relay traffic via Yume. Exit IP: $exitIp.",
        "Трафик приложения через Yume подтвержден. Выходной IP: $exitIp.",
        "Yume 経由のアプリ中継トラフィックを確認しました。出口 IP: $exitIp。",
    )

    fun logRunningRoutedProbe(): String = t(
        "Running an in-app routed Android traffic probe through Yume.",
        "Запускается внутриприкладной Android probe маршрутизации через Yume.",
        "Yume 経由でアプリ内の Android ルーティング確認を実行しています。",
    )

    fun logVerifiedSystemTraffic(exitIp: String): String = t(
        "Verified system traffic via Yume. Exit IP: $exitIp.",
        "Системный трафик через Yume подтвержден. Выходной IP: $exitIp.",
        "Yume 経由のシステムトラフィックを確認しました。出口 IP: $exitIp。",
    )

    fun logTlsHandshakeFailed(detail: String): String = t(
        "TLS handshake failed: $detail",
        "TLS-рукопожатие не удалось: $detail",
        "TLS ハンドシェイクに失敗しました: $detail",
    )

    fun logTlsProviderHandshakeFailed(): String = t(
        "TLS handshake failed on this Android TLS provider",
        "TLS-рукопожатие не удалось на этом Android TLS provider",
        "この Android TLS プロバイダでは TLS ハンドシェイクに失敗しました",
    )

    fun logHandshakeFailed(): String = t(
        "Handshake failed",
        "Рукопожатие не удалось",
        "ハンドシェイクに失敗しました",
    )

    fun logVpnPrepareFailed(detail: String): String = t(
        "VpnService.prepare() failed: $detail",
        "VpnService.prepare() завершился ошибкой: $detail",
        "VpnService.prepare() に失敗しました: $detail",
    )

    fun logUnhandledVpnCoroutineFailure(detail: String): String = t(
        "Unhandled VPN coroutine failure: $detail",
        "Необработанная ошибка VPN coroutine: $detail",
        "VPN コルーチンで未処理の障害が発生しました: $detail",
    )

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

    fun logFailedToVerifySystemRouting(detail: String): String = t(
        "Failed to verify systemwide Yume routing: $detail",
        "Не удалось подтвердить системную маршрутизацию Yume: $detail",
        "システム全体の Yume ルーティング確認に失敗しました: $detail",
    )

    fun removed(displayName: String): String = t("$displayName removed", "$displayName удален", "$displayName を削除しました")

    fun reconnectFor(label: String): String {
        return t("Reconnect for $label.", "Переподключитесь для $label.", "$label を有効にするには再接続してください。")
    }

    fun checkingKeysFor(endpointLabel: String): String {
        return t(
            "Checking keys and VPN permission for $endpointLabel.",
            "Проверка ключей и VPN-разрешения для $endpointLabel.",
            "$endpointLabel のキーと VPN 権限を確認しています。",
        )
    }

    fun loadingKeysFor(endpointLabel: String): String {
        return t(
            "Loading keys and opening a control path to $endpointLabel.",
            "Загрузка ключей и открытие управляющего пути к $endpointLabel.",
            "$endpointLabel への制御経路を開くためにキーを読み込んでいます。",
        )
    }

    fun usingAnonymCa(displayName: String): String {
        return t("Using $displayName", "Используется $displayName", "$displayName を使用中")
    }

    fun selectedServerSummary(
        profileName: String,
        selectedCaName: String,
        selectedAuthKeyName: String,
    ): String {
        return t(
            "Selected server: $profileName, $anonymCaLabel: $selectedCaName, ${this.authKeyLabel}: $selectedAuthKeyName",
            "Выбранный сервер: $profileName, $anonymCaLabel: $selectedCaName, ${this.authKeyLabel}: $selectedAuthKeyName",
            "選択中のサーバー: $profileName、$anonymCaLabel: $selectedCaName、${this.authKeyLabel}: $selectedAuthKeyName",
        )
    }

    fun currentPhaseSummary(phase: String, technique: String, profile: String): String {
        return t(
            "Current phase: $phase • Technique: $technique • Profile: $profile",
            "Текущая фаза: $phase • Техника: $technique • Профиль: $profile",
            "現在のフェーズ: $phase • 方式: $technique • プロファイル: $profile",
        )
    }

    fun appVersion(value: String): String = t("App: $value", "Приложение: $value", "アプリ: $value")

    fun routePath(technique: String, profile: String): String {
        return t("Path: $technique • $profile", "Маршрут: $technique • $profile", "経路: $technique • $profile")
    }

    fun exitIpAndUpstream(exitIp: String, upstream: String): String {
        return t("Exit IP: $exitIp • Upstream: $upstream", "Выходной IP: $exitIp • Верхняя сеть: $upstream", "出口IP: $exitIp • 上流: $upstream")
    }

    fun anonymSummary(required: Boolean): String {
        val value = if (required) anonymRequired else anonymOptional
        return "$anonymLabel: $value"
    }

    fun authKeySummary(displayName: String): String {
        return t("Auth key: $displayName", "Ключ авторизации: $displayName", "認証キー: $displayName")
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
        return t(
            "Protected in $location",
            "Защищено через $location",
            "$location に接続中",
        )
    }

    fun notificationTrafficTotals(download: String, upload: String): String {
        return "↓ $download   ↑ $upload"
    }

    fun buildVersionLabel(versionName: String, versionCode: Int, debug: Boolean): String {
        return if (debug) {
            t(
                "DEBUG build v$versionName ($versionCode)",
                "DEBUG-сборка v$versionName ($versionCode)",
                "DEBUG ビルド v$versionName ($versionCode)",
            )
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
