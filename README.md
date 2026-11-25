# Асыл тас

Қазақ тіліндегі Android қосымшасы: 12 тас каталогы, алқа құрастыру, жеке кабинет,
таңдаулылар, жинақтар, өтінімдер және әкімші бөлімі. Java/XML, SQLite және BigDecimal.

[Android 3.0 APK жүктеу](deliverables/AsylTas-3.0.apk?raw=true) ·
[Қолдану нұсқаулығы](README_KZ.txt) · [Тексеру нәтижелері](TEST_RESULTS.txt) ·
[Зертханалық мүмкіндіктер](LAB_FEATURES_KZ.txt)

Android 7.0 немесе кейінгі нұсқа қажет. Аккаунттар мен өтінімдер құрылғыда сақталады.
Алғашқы тіркелген аккаунт — әкімші. Онлайн төлем мен құрылғылар арасында синхрондау жоқ.
«Алматы, Қазақстан» — әкімші өзгерте алатын үлгі мекенжай.

Үлгі алқа: **6 тас · 5.90 карат · 616 200 теңге**. Бағалар мен салмақтар — оқу деректері.
12 нақты Wikimedia Commons фотосының авторлары мен лицензиялары [PHOTO_CREDITS.txt](PHOTO_CREDITS.txt) ішінде.

## Жинау

Android Studio ішінде `android` бумасын ашыңыз; `shared` оның жанында қалсын.

```powershell
.\android\gradlew.bat -p android :app:assembleDebug
.\android\gradlew.bat -p android :app:lintDebug
```

Release қолтаңбасы үшін `JAVA_HOME`, `ANDROID_HOME` (немесе `ANDROID_SDK_ROOT`),
`ASYL_KEYSTORE` және `ASYL_STORE_PASSWORD` орта айнымалыларын баптап,
`powershell -File tools/build_v3.ps1` іске қосыңыз.
`ASYL_KEY_ALIAS` әдепкіде `asyltas`; бөлек кілт паролі болса, `ASYL_KEY_PASSWORD` қолданылады.
Кілттер, парольдер және `local.properties` репозиторийге кірмейді.

## Тексерілген нұсқа

Модель: 43/43. SQLite: 92/92. UI: 64/64 сценарий, жеті аралық 3.0 жинағында;
оның 11-і соңғы APK-да. Pixel_4, Android 10 / API 29 эмуляторы қолданылды.
Lint: 0 Error/Fatal, 214 Warning. Нақты телефон мен API 24/35 жеке тексерілмеді.

APK SHA-256: `19fad1f21645fcb27721ffb11bb54e27ce0b26028d2ef78417e62bc50f04a2b7`.
Толық дәлелдер [reports/qa_v3_20261125](reports/qa_v3_20261125) ішінде.
Бұрынғы жергілікті ZIP жинақтары мен Windows EXE бұл репозиторийге қосылмайды.

Орындағандар: **Болат Тәңірберді, Ергаш Назым, Ғалымжан Шынай**.
