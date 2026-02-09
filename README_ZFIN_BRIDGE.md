# ZFIN Bridge

Утилита перекладывает файлы между банковской папкой и ZFIN.

## Логика маршрутизации
1. `Bank OUT (*.txt)` -> `ZFIN in (*.occ)`
2. `ZFIN out (*.ifm)` -> `Bank IN (*.ifm)`

После успешной перекладки исходники переносятся в архивы:
- Bank source -> `ARH/<yyyy-MM-dd>/<source-folder>/...`
- ZFIN source -> `arc/<yyyy-MM-dd>/<source-folder>/...`

Порядок обработки (сортировка):
- сначала по времени изменения файла,
- затем по имени файла.

## Прод-функции
- single-instance lock (`lock_file`) — второй экземпляр не запустится;
- логирование в файл (`log_dir`) + ротация по размеру (`log_rotate_bytes`, `log_rotate_files`);
- автоочистка старых архивов (`archive_retention_days`);
- фильтр минимального возраста файла (`min_file_age_seconds`) для защиты от недописанных файлов.

## Конфиг
Файл: `config.ini` (UTF-8, формат `key=value`).

Ключевые параметры:
- `bank_root`, `zfin_root`
- `bank_out_dir`, `bank_in_dir`, `bank_archive_dir`
- `zfin_in_dir`, `zfin_out_dir`, `zfin_archive_dir`
- `bank_to_zfin_source_ext`, `bank_to_zfin_target_ext`
- `zfin_to_bank_source_ext`, `zfin_to_bank_target_ext`
- `poll_interval_seconds`
- `archive_retention_days`
- `lock_file`
- `log_dir`, `log_rotate_bytes`, `log_rotate_files`

## Важно про P: и службу
Если запускать как сервис/системная задача, mapped-диск `P:` часто недоступен.
Для прод-запуска лучше указать в `zfin_root` UNC путь (например `\\10.30.71.229\zf_share_dir\home\vsftpd\ftpuser`).

## Ручной запуск
Один проход (проверка):
```bat
run-zfin-bridge.bat --once
```

Постоянный режим:
```bat
run-zfin-bridge.bat
```

Прямой запуск:
```bat
java -jar zfin-bridge.jar --config config.ini
```

## Полностью готовый запуск (без донастройки)
Выполнить один раз от администратора:
```powershell
powershell -ExecutionPolicy Bypass -File .\install-ready.ps1
```

Скрипт автоматически:
- пытается заменить `zfin_root` из `P:\...` в UNC (если `P:` смонтирован как сетевой диск),
- выполняет self-test (`--once`),
- ставит автозапуск на старте Windows.

## Автозапуск без внешних зависимостей (рекомендуется)
Ставит задачу в Task Scheduler от `SYSTEM` с автоперезапуском:
```powershell
powershell -ExecutionPolicy Bypass -File .\install-startup-task.ps1
```

Удаление:
```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall-startup-task.ps1
```

## Запуск как Windows Service (через NSSM)
`nssm.exe` уже включен в пакет (`nssm.exe` рядом со скриптами).

Установка:
```powershell
powershell -ExecutionPolicy Bypass -File .\install-service-nssm.ps1
```

Удаление:
```powershell
powershell -ExecutionPolicy Bypass -File .\uninstall-service-nssm.ps1
```
