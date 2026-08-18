# مرجع دمج كشف المستندات المحلي

## OpenCV for Android

تؤكد وثائق OpenCV الرسمية أن حزمة Android بصيغة AAR متاحة عبر Maven Central منذ الإصدار 4.9.0، وأن Gradle يضيف المكتبات الأصلية تلقائيًا إلى APK. كما توضح ضرورة استدعاء `OpenCVLoader.initLocal()` قبل أول استخدام للمكتبة.

المصدر: [OpenCV4Android usage models](https://opencv.org/opencv4android-usage-models/)

تشير وثائق OpenCV أيضًا إلى مثال الاعتمادية `implementation 'org.opencv:opencv:4.9.0'` وتوضح أن هذا المسار مناسب لدمج OpenCV محليًا في تطبيق Android دون الحاجة إلى تنزيل SDK مستقل على الجهاز.

المصدر: [Enhanced OpenCV For Android Support & ARM Performance Gains](https://opencv.org/enhanced-opencv-for-android-support-arm-performance-gains/)

## قرار التطبيق

سيستخدم التطبيق الاعتمادية الرسمية عبر Maven Central لتنفيذ كشف الحواف والـContours والرباعي وتصحيح المنظور محليًا. لا يعتمد التشغيل على خدمات Google أو الإنترنت بعد تثبيت APK؛ فالاعتمادية تُضمَّن ضمنه وقت البناء.
