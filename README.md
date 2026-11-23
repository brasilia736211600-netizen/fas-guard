# FAS Guard

تطبيق أندرويد خفيف يجمع **جدار حماية لكل تطبيق** + **تحديد السرعة** + **سلسلة بروكسي** + **إدارة ملفات VPN** في نفق واحد.

> الحزمة: `com.fas.guard` · أقل إصدار: Android 8.0 (API 26) · الهدف: API 35
> الحجم: ~53 KB · بدون AndroidX أو Compose أو أي تبعية خارجية.

---

## التحميل والتثبيت

آخر بناء تلقائي (GitHub Actions):

```
https://github.com/brasilia736211600-netizen/fas-guard/releases/latest
```

1. نزّل ملف `FASGuard-*.apk`.
2. اسمح لمدير الملفات بـ«تثبيت تطبيقات من مصادر غير معروفة».
3. افتح التطبيق → اضغط زر التشغيل → اقبل طلب إذن VPN.

---

## الميزات الحالية

- **جدار حماية لكل تطبيق**: حجب/سماح بالاعتماد على UID المالك للحزمة (Android 10+)، دون root.
- **تحديد سرعة** تنزيل/رفع لكل تطبيق وللاتجاهين مع سقف عام (Token Bucket).
- **نفق IPv4 TCP/UDP كامل في مساحة المستخدم** مع DNS وإعادة توجيه آمنة.
- **حماية تسريب IPv6** وحجب QUIC اختياري.
- **بروكسي صاعد**: SOCKS5 (مع مصادقة) أو HTTP CONNECT (Basic Auth)، ويشمل DNS-over-TCP.
- **ملفات VPN**: استيراد `.ovpn` / `.conf` / `.wg` وتسليمها بأمان عبر `content://` إلى تطبيق OpenVPN أو WireGuard الرسمي.
- **واجهة داكنة** بخمسة تبويبات: Home · Apps · VPN · Proxy · Logs.
- تشغيل تلقائي عند الإقلاع (اختياري)، إشعار خفي مع زر إيقاف، سجل حي وإحصاءات.

## غير مدعوم بعد (بصراحة)

- الاتصال الداخلي بـ OpenVPN/WireGuard (يُفوَّض حاليًا لتطبيقاتهما الرسمية).
- Shadowsocks / Trojan / SSH / SOCKS4 / V2Ray.
- تمرير IPv6 (يُحجب حاليًا)، وUDP عبر SOCKS5.
- إحصاءات لكل تطبيق، ووضع root.
- الحجب لكل تطبيق على Android 8–9 (تعذّر تحديد UID).

---

## البناء

### محليًا (Termux أو Linux)

المتطلبات: JDK 17+، Android SDK (platform + build-tools 35) أو `ANDROID_JAR` مباشرة.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # أو ANDROID_JAR=/path/to/android.jar
./build.sh
# الناتج: dist/FASGuard-1.0.0.apk
```

السكربت يستخدم `aapt2 → javac → d8 → zipalign → apksigner` فقط. لا يحتاج Gradle.
لتوليد مفتاح إصدار ثابت:

```bash
keytool -genkeypair -keystore fasguard.keystore -alias fasguard \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass 'CHANGE_ME' -keypass 'CHANGE_ME' -dname "CN=FAS Guard"
KEYSTORE=fasguard.keystore KEYSTORE_PASS=... KEY_PASS=... ./build.sh
```

### عبر GitHub Actions (موصى به للهواتف الضعيفة)

كل دفع إلى `main` يبني التطبيق ويحدّث إصدار `latest` تلقائيًا.

لضمان توقيع ثابت بين الإصدارات، أضف أسرار المستودع:

| Secret | القيمة |
|---|---|
| `ANDROID_KEYSTORE_B64` | `base64 -w0 fasguard.keystore` |
| `ANDROID_KEYSTORE_PASS` | كلمة سر المخزن |
| `ANDROID_KEY_PASS` | كلمة سر المفتاح |

بدون الأسرار يبني الـ CI بمفتاح مؤقت (قد يتطلب إعادة تثبيت عند التحديث).

---

## البنية

```
app/src/main/java/com/fas/guard/
├─ vpn/FasVpnService.java   محرك النفق: TUN، UID، الحجب، التحديد، DNS، UDP/TCP
├─ net/Net.java             تحليل/بناء IPv4·IPv6·TCP·UDP + checksums
├─ net/Proxy.java           SOCKS5 + HTTP CONNECT + اختبار
├─ net/Limits.java          دمج الحدود العامة/لكل تطبيق
├─ net/Rate.java            Token Bucket
├─ core/Prefs.java          الإعدادات والقواعد
├─ core/Profile*.java       استيراد/تحليل/تخزين ملفات VPN
├─ core/Apps.java           كتالوج التطبيقات
├─ data/ProfileProvider.java  جسر content:// آمن
├─ ui/MainActivity.java     الواجهة (5 تبويبات)
└─ BootReceiver.java        التشغيل التلقائي
```

### كيف يعمل؟

1. `VpnService` ينشئ واجهة TUN ويوجّه كل حركة IPv4/IPv6 إليها.
2. لكل حزمة يُستخرج UID المالك عبر `ConnectivityManager.getConnectionOwnerUid`، ويُطبَّق قرار الحجب.
3. تُعاد حزم TCP/UDP عبر مقابس محمية بـ `protect()`، مع Token Bucket لكل UID واتجاه.
4. عند تفعيل البروكسي تُمرَّر كل مقابس TCP (وDNS عبر TCP) خلال SOCKS5/HTTP.
5. تُسجَّل الإحصاءات وتُعرض في الواجهة والإشعار.

---

## الترخيص

خاص. جميع الحقوق محفوظة لصاحب المستودع.
