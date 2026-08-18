# توقيع نسخة Release

يُبنى ملف Release فقط عند وجود ملف `keystore.properties` محلي مكتمل. هذا الملف وملف المفتاح (`.jks`) مستثنيان من Git عمدًا، ولذلك لا تُحفظ بيانات اعتماد التوقيع ضمن المصدر أو في سجل الالتزامات.

## إنشاء مفتاح التوقيع

أنشئ مجلدًا آمنًا خارج المستودع، ثم شغّل الأمر التالي من جهاز موثوق. سيطلب `keytool` كلمات المرور تفاعليًا، فلا تكتبها في ملف أوامر أو في سجل الطرفية.

```bash
mkdir -p ../masahhisabat-signing
keytool -genkeypair -v \
  -keystore ../masahhisabat-signing/masahhisabat-release.jks \
  -alias masahhisabat \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Masah Hisabat, OU=Mobile, O=Masah Hisabat, C=EG"
```

## ربط Gradle بالمفتاح محليًا

انسخ القالب ثم استبدل قيم كلمات المرور بالقيم التي اخترتها أثناء إنشاء المفتاح:

```bash
cp keystore.properties.template keystore.properties
```

بعدها نفّذ البناء والتحقق:

```bash
export ANDROID_HOME=/home/ubuntu/android_app/sdk
./gradlew assembleRelease --no-daemon
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

## حفظ المفتاح

احتفظ بنسختين مشفرتين من ملف `.jks` في موقعين آمنين منفصلين. يجب استخدام **المفتاح نفسه** في كل تحديث لاحق؛ فقدانه يمنع نشر تحديث متوافق مع النسخة الموقعة السابقة.
