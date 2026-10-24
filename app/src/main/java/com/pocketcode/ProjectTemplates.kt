package com.pocketcode

data class ProjectTemplate(val id: String, val title: String, val about: String, val files: Map<String, String>)

private const val HOOKS_PY = "*.py => python3 -m py_compile {file}\n"

val PROJECT_TEMPLATES = listOf(
    ProjectTemplate("empty", "Пустой проект", "Только AGENTS.md с правилами для агента", mapOf(
        "AGENTS.md" to "# Проект\n\nОпиши здесь, что делает проект, как его запускать и какие правила соблюдать. Агент читает этот файл в начале каждого чата.\n",
    )),
    ProjectTemplate("python-script", "Скрипт на Python", "main.py, requirements.txt и автопроверка синтаксиса", mapOf(
        "AGENTS.md" to "# Python-скрипт\n\n- Запуск: python3 main.py\n- Зависимости: requirements.txt\n- Пиши код с аннотациями типов, секреты бери из переменных окружения.\n",
        "main.py" to "def main() -> None:\n    print(\"Привет из PocketCode\")\n\n\nif __name__ == \"__main__\":\n    main()\n",
        "requirements.txt" to "",
        ".pocketcode/hooks.txt" to HOOKS_PY,
    )),
    ProjectTemplate("telegram-bot", "Telegram-бот (Python)", "Каркас бота на aiogram 3; токен в переменной BOT_TOKEN", mapOf(
        "AGENTS.md" to "# Telegram-бот\n\n- Библиотека: aiogram 3.\n- Токен только из переменной окружения BOT_TOKEN, в код не писать.\n- Запуск: BOT_TOKEN=... python3 bot.py\n- Обработчики держи в handlers.py.\n",
        "bot.py" to "import asyncio\nimport os\n\nfrom aiogram import Bot, Dispatcher\nfrom aiogram.filters import CommandStart\nfrom aiogram.types import Message\n\ndp = Dispatcher()\n\n\n@dp.message(CommandStart())\nasync def start(message: Message) -> None:\n    await message.answer(\"Привет! Я бот.\")\n\n\n@dp.message()\nasync def echo(message: Message) -> None:\n    await message.answer(message.text or \"\")\n\n\nasync def main() -> None:\n    bot = Bot(os.environ[\"BOT_TOKEN\"])\n    await dp.start_polling(bot)\n\n\nif __name__ == \"__main__\":\n    asyncio.run(main())\n",
        "requirements.txt" to "aiogram>=3.4\n",
        ".pocketcode/hooks.txt" to HOOKS_PY,
    )),
    ProjectTemplate("web", "Сайт (HTML)", "Адаптивная страница index.html для телефона", mapOf(
        "AGENTS.md" to "# Сайт\n\n- Один index.html со встроенными стилями и скриптом, адаптивный под телефон.\n- После правок открывай превью (инструмент preview).\n",
        "index.html" to "<!doctype html>\n<html lang=\"ru\">\n<head>\n<meta charset=\"utf-8\">\n<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n<title>Моя страница</title>\n<style>\nbody{font-family:system-ui,sans-serif;margin:0;padding:24px;line-height:1.5}\nh1{margin-top:0}\n</style>\n</head>\n<body>\n<h1>Привет!</h1>\n<p>Начни отсюда.</p>\n</body>\n</html>\n",
    )),
    ProjectTemplate("node", "Node.js", "package.json и index.js с проверкой синтаксиса", mapOf(
        "AGENTS.md" to "# Node.js проект\n\n- Запуск: node index.js\n- Тесты: npm test\n",
        "package.json" to "{\n  \"name\": \"app\",\n  \"version\": \"1.0.0\",\n  \"main\": \"index.js\",\n  \"scripts\": { \"start\": \"node index.js\" }\n}\n",
        "index.js" to "console.log('Привет из PocketCode');\n",
        ".pocketcode/hooks.txt" to "*.js => node --check {file}\n*.json => python3 -m json.tool {file} > /dev/null\n",
    )),
)
