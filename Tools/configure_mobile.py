from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

STRINGS = {
    "en": {
        "VinoSubscriptionInfo": "Paste a subscription link, update the list, then enable it. Servers are checked every 30 seconds; subscriptions refresh hourly. Only vinoGram traffic uses this connection.",
        "VinoSubscriptionUse": "Use subscription",
        "VinoSubscriptionRefresh": "Update subscription",
        "VinoSubscriptionRemove": "Remove subscription",
        "VinoSubscriptionUrlError": "Enter an HTTP or HTTPS subscription link.",
        "VinoSubscriptionLoading": "Downloading and checking subscription…",
        "VinoSubscriptionImported": "Imported servers:",
        "VinoSubscriptionSkipped": "Skipped:",
        "VinoSubscriptionUpdateError": "Could not import the subscription. Supported formats: URI list, Base64, Xray JSON, AURA export. Previous servers are preserved.",
        "VinoSubscriptionDisabled": "Subscription disabled. Previous proxy settings restored.",
        "VinoSubscriptionStarting": "Starting subscription connection…",
        "VinoSubscriptionRestarting": "Connection core stopped. Reconnecting; direct fallback is blocked.",
        "VinoSubscriptionChecking": "Checking subscription servers…",
        "VinoSubscriptionAlive": "Available servers:",
    },
    "ru": {
        "VinoSubscriptionInfo": "Вставьте ссылку подписки, обновите список и включите её. Серверы проверяются каждые 30 секунд, подписка обновляется раз в час. Подключение действует только внутри vinoGram.",
        "VinoSubscriptionUse": "Использовать подписку",
        "VinoSubscriptionRefresh": "Обновить подписку",
        "VinoSubscriptionRemove": "Удалить подписку",
        "VinoSubscriptionUrlError": "Введите ссылку подписки HTTP или HTTPS.",
        "VinoSubscriptionLoading": "Загрузка и проверка подписки…",
        "VinoSubscriptionImported": "Импортировано серверов:",
        "VinoSubscriptionSkipped": "Пропущено:",
        "VinoSubscriptionUpdateError": "Не удалось импортировать подписку. Поддерживаются список ссылок, Base64, JSON Xray и экспорт AURA. Прежние серверы сохранены.",
        "VinoSubscriptionDisabled": "Подписка отключена. Восстановлены прежние настройки прокси.",
        "VinoSubscriptionStarting": "Запуск подключения по подписке…",
        "VinoSubscriptionRestarting": "Ядро подключения остановилось. Переподключение; прямой обход заблокирован.",
        "VinoSubscriptionChecking": "Проверка серверов подписки…",
        "VinoSubscriptionAlive": "Доступно серверов:",
    },
}


def main():
    res = ROOT / "TMessagesProj/src/main/res"
    for folder in res.glob("values*"):
        strings = folder / "strings.xml"
        if not strings.exists(): continue
        text = strings.read_text(encoding="utf-8")
        for key, value in (("AppName", "vinoGram"), ("AppNameBeta", "vinoGram Beta"), ("Page1Title", "vinoGram")):
            text = re.sub(rf'(<string name="{key}">).*?(</string>)', rf'\g<1>{value}\g<2>', text)
        if folder.name == "values":
            text = re.sub(r'(<string name="Page1Message">).*?(</string>)', r'\g<1>An independent Telegram client.\nSubscriptions built in.\g<2>', text)
        if folder.name == "values-ru":
            text = re.sub(r'(<string name="Page1Message">).*?(</string>)', r'\g<1>Независимый Telegram-клиент.\nПодписки на прокси внутри.\g<2>', text)
        strings.write_text(text, encoding="utf-8")
    for language, entries in STRINGS.items():
        folder = res / ("values" if language == "en" else f"values-{language}")
        path = folder / "strings.xml"
        text = path.read_text(encoding="utf-8")
        additions = ''.join(f'    <string name="{key}">{value}</string>\n'
                            for key, value in entries.items() if f'name="{key}"' not in text)
        path.write_text(text.replace('</resources>', additions + '</resources>'), encoding="utf-8")
    manifest = ROOT / "TMessagesProj_AppStandalone/src/main/AndroidManifest.xml"
    text = manifest.read_text(encoding="utf-8")
    if 'android:extractNativeLibs=' not in text:
        text = text.replace('tools:replace="name"', 'android:extractNativeLibs="true" tools:replace="name"')
    manifest.write_text(text, encoding="utf-8")
    for path in ROOT.glob("TMessagesProj*/google-services.json"):
        path.unlink()
    (ROOT / "TMessagesProj/config/release.keystore").unlink(missing_ok=True)


if __name__ == "__main__":
    main()
