# مراجع OCR محلي

- [Tesseract4Android](https://github.com/adaptech-cz/Tesseract4Android) هو fork حديث من tess-two يُبنى عبر CMake ويدعم Android Studio الحديث؛ تعرض صفحته إصداراً منشوراً `4.9.0` ويتطلب ملفات بيانات مدرّبة منفصلة للغات.
- [tess-two](https://github.com/deekoder/tess_two) مشروع أقدم يعتمد Tesseract 3.02.02 وأدوات بناء قديمة، ويطلب وضع ملف اللغة داخل مجلد `tessdata`؛ لذلك لا يُستخدم كاعتماد جديد في هذا التطبيق.
- [tessdata](https://github.com/tesseract-ocr/tessdata) هو المصدر المرجعي لملفات اللغة المدربة في Tesseract، بما فيها بيانات العربية.

تمت مراجعة هذه المراجع في 2026-08-19. سيبقى OCR محلياً بالكامل مع تضمين محركه ونموذج اللغة في APK، ولن يعتمد على خدمات Google أو اتصال شبكة وقت التشغيل.
