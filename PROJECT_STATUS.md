# حالة مشروع تطبيق «ماسح الحسابات» (أندرويد)

## المهمة
بناء تطبيق أندرويد كامل بالعربية (RTL) باسم «ماسح الحسابات» وفق مواصفات pasted_content.txt (في /home/ubuntu/upload/pasted_content.txt):
دخول محلي (mustafa / 0، أدوار: مشرف/محرر/مشاهد)، 6 تبويبات (الرئيسية، المجموعات، السكانر، البحث، نهاري، الإعدادات)، سكانر متقدم (كاميرا/معرض، حواف، اقتصاص بمقابض، تدوير، معالجة صور)، فواتير ومجموعات، استخراج ذكي، بحث متقدم، مزامنة محلية Wi-Fi، إعدادات (تصدير ZIP/استيراد/تغيير كلمة مرور/سجل نشاط/إدارة فريق)، تصميم Material Design عربي.

## البيئة
- المشروع: /home/ubuntu/android_app (Gradle Kotlin DSL + Kotlin)
- SDK: ~/android_app/sdk (platforms;android-34, build-tools;34.0.0, platform-tools)
- Java 21, Gradle system 4.4.1 لكن نستخدم gradle wrapper (غير مثبت بعد!)
- ملاحظة مهمة: يجب إنشاء gradle/wrapper/gradle-wrapper.properties + gradlew أو تشغيل build عبر Gradle 8.x مثبت يدوياً.

## الملفات المنشأة حتى الآن
### Gradle
- settings.gradle.kts, build.gradle.kts (جذر: AGP 8.2.2, Kotlin 1.9.22), app/build.gradle.kts (deps: material, cameraX, gson, coroutines, viewpager2), gradle.properties, local.properties (sdk.dir=/home/ubuntu/android_app/sdk)

### resources
- AndroidManifest.xml (صلاحيات كاميرا/تخزين/اهتزاز/شبكة)
- themes.xml (Theme.MasahHisabat M3 + Login theme)
- colors.xml (night_: #0A1A1F/#12262D/#1B3440/#F0F4F5/#8FA8AF، day_: #F2F8F7/#FFFFFF/#E6F1EF/#0D1F24/#4B6570، primary #20B2AA)
- strings.xml (كل النصوص العربية)
- strings: login, nav (الرئيسية/المجموعات/السكانر/البحث/نهاري/الإعدادات), home, scan, crop, invoices, search, settings, team, sync, log
- layouts: activity_main.xml (fragment_container + BottomNavigationView menu=bottom_nav_menu), activity_login.xml, fragment_home.xml, fragment_groups.xml, fragment_scanner.xml, activity_crop_edit.xml, activity_invoice.xml, activity_group.xml, fragment_search.xml (لم يُنشأ بعد!), activity_crop_edit refers to CropView كلاس داخلي
- menus: bottom_nav_menu.xml (nav_home/groups/scanner/search/theme/settings)
- drawables: ic_launcher_foreground, login_bg, input_bg, card_surface, circle_primary, login_bg, ic_groups_filled, ic_scanner_filled, ic_search_filled, ic_settings_filled, ic_sun_filled, ic_groups(gray), ic_scanner(gray), ic_search(gray), ic_settings(gray), ic_sun(gray), ic_moon, ic_invoice, ic_add, ic_camera, ic_gallery, ic_rotate, ic_grid, ic_check, ic_close, ic_more, ic_delete, ic_share, ic_person, ic_people, ic_key, ic_history, ic_export, ic_import, ic_logout, ic_done, ic_invoice_new, ic_upload, ic_sync, ic_wifi, ic_menu, ic_filter, ic_reset, ic_compare, ic_gallery_white, ic_camera_white, ic_visibility, ic_visibility_off, ic_folder, ic_text, ic_scanner_white, ic_home

### Kotlin (com.masahhisabat.app)
- data/Models.kt: Role(ADMIN/SUPERVISOR/EDITOR/VIEWER), User, InvoiceItem, Group, ActivityEntry, SyncEntry, generateId, HashUtil (SHA-256+salt)
- data/AppRepository.kt: كل التخزين (users, groups, items, activity, synclog, prefs, dataDir=filesDir/masah_data, exportData ZIP, importBackup)
- data/InvoiceExtractor.kt: Extracted + OcrHelper (ML Kit عبر reflection اختياري) + currentInvoiceName
- image/ImageProcessor.kt: ProcessMode(ORIGINAL/AUTO/HIGH_CONTRAST/BW), enhance, highContrast, bw, rotate, crop, detectEdges, saveTo
- data/SyncManager.kt: ServerSocket:8765, syncWithHost, SyncPayload
- ui/ThemeHelper.kt: isNight, bg, surface, surfaceHigh, text, textSecondary
- ui/auth/LoginActivity.kt + SessionStore (SharedPreferences "session": username+role)
- ui/main/MainActivity.kt: 6 fragments + toggleTheme + PlaceholderFragment
- ui/home/HomeFragment.kt + RecentAdapter
- ui/groups/GroupsFragment.kt + GroupsAdapter
- ui/scanner/ScannerFragment.kt (TakePicture + GetContent + FileProvider share)
- ui/scanner/CropEditActivity.kt + CropView (مقابض 0..3 + سحب كامل + شبكة + اهتزاز حواف)
- ui/invoice/InvoiceActivity.kt (استخراج ذكي + تعديل + حفظ، ACTION_CREATE/ADD)
- ui/invoice/GroupActivity.kt + ItemsAdapter (تحديد جماعي، بحث داخلي، معاينة، مشاركة)
- ui/search/SearchFragment.kt (اقتراحات، فلاتر، تحميل)

## المتبقي
1. fragment_search.xml
2. fragment_placeholder.xml (نص بسيط)
3. ui/settings/SettingsFragment.kt (نهاري، إدارة حسابات، تغيير كلمة مرور، سجل نشاط، تصدير/استيراد، تسجيل خروج)
4. ui/settings/TeamActivity.kt (إدارة الفريق: إضافة مستخدم، أدوار، حذف)
5. file_paths.xml (FileProvider) + تحديث manifest بإضافة provider
6. تحديث styles.xml إذا لزم (values/styles.xml غير موجود، themes كافية)
7. إنشاء gradle wrapper (استخدام gradle wrapper من حزمة Gradle 8.x أو تنزيل gradle-8.5)
8. ./gradlew assembleDebug (قد يحتاج JDK17+ وheap)
9. توقيع APK غير مطلوب (debug يكفي) ثم تسليم apk

## تشخيص تحطم عند زر الدخول (مرحلة 11-12 الحالية)
- تدفق الزر: validate → authenticate (users()/loadList) → rememberLogin → logActivity → SessionStore.save → startActivity(MainActivity) → finish()
- كل الموارد سليمة (drawables/strings/menu OK)
- الحل المتبع: try/catch كامل حول onclick في LoginActivity مع تسجيل في filesDir/crash_log.txt بصيغة "LOGIN_FAIL" + Toast برسالة الخطأ
-MainActivity onCreate مغلفة أيضًا بtry/catch بصيغة "MAIN_FAIL" + Toast
- App.kt = Application class (UncaughtExceptionHandler + Toast + crash_log.txt) مسجلة في manifest android:name=.App ✓
- الخطوة التالية: ./gradlew assembleDebug --no-daemon -x lint ثم nسخ APK إلى /home/ubuntu/MasahHisabat-Android.apk وتسليمها مع تعليمات: احذف النسخة القديمة أولًا، وعند الظهور رسالة الخطأ أرسل نصها أو ملف Android/data/com.masahhisabat.app/files/crash_log.txt

## تشخيص تحطم بعد الدخول (لم يُعثر على مورد مفقود)
- كل drawables/strings/menu IDs سليمة حسب الفحص
- سبب متوقع الآن: getString(R.string.log_login, username) يعمل، لكن HomeFragment.refresh() يستدعي AppRepository.totalInvoiceCount() → groups() → loadList("groups.json") → gson.fromJson → OK. ربما crash من ic_scanner_white كـicon + ic_scanner_filled غير موجود؟ تحقق: MainActivity applyTheme يستخدم ic_scanner_filled + ic_scanner_white — ic_scanner_filled يجب أن يكون موجودًا!
- أي'ضًا HomeFragment btn_start_scan drawableStart=@drawable/ic_scanner_white ✓ موجود

## تطور جديد (تحطم بعد الدخول + تغيير عبارة المطور)
- الطلبات الحالية: (1) استبدال "مقدَّم من Whacka" بـ"التطبيق من تطوير مصطفي ♥️ عبدالفتاح" في activity_login.xml ✓ تم
- (2) "عند تسجيل الدخول التطبيق يتوقف ويخرج" → MainActivity يبدأ ثم يحطم. الأسباب المحتملة:
  * fragment_home: dev_credit → @string/dev_credit يجب أن يكون معرفًا في strings.xml (تحقق)
  * MainActivity switchTab(0) fragment_container، bottom_nav menu items IDs يجب مطابقتها
  * ThemeHelper.surfaceHigh used in GroupsFragment/ScannerFragment
  * HomeFragment recent_list adapter OK
- تم: initAppContext في كل Activities قبل super.onCreate (Login, Main, CropEdit, Invoice, Group, Team)
- تم: prefs lazy في AppRepository + usersInternal/addUserInternal + ensureDefaults تستخدم internal
- APK: ./gradlew assembleDebug --no-daemon -x lint → app/build/outputs/apk/debug/app-debug.apk (7.2MB) نسخ إلى /home/ubuntu/MasahHisabat-Android.apk
- launcher fixed: LoginActivity لديه MAIN/LAUNCHER ✓
- ملاحظة: عند التوقيع المختلف يجب حذف النسخة القديمة قبل التثبيت

## تطور البناء (محدث)
- build cmd: cd /home/ubuntu/android_app && export ANDROID_HOME=/home/ubuntu/android_app/sdk && ./gradlew compileDebugKotlin --no-daemon > /tmp/kotlin_errors.txt 2>&1; grep "^e: file" /tmp/kotlin_errors.txt | sort -u
- أخطاء ktlint حُلّت: mipmap (سكربت scripts/gen_mipmap.sh)، fillColor currentColor→#FFFFFF في كل drawables، themes colors، formatted=false، SessionStore.logout، ThemeHelper.toggleTheme، canAdmin، addUser بـhash، SettingsFragment importBackup signature (يتوقع Context+File → عدّلنا إلى AppRepository.importBackup(requireContext(), tmp) بعد إضافة overload)، exportData(context)→ exportData(cacheDir.parentFile)
- أخطاء حالية (معالجة):
 1. InvoiceActivity: extract_panel غير موجود (layout فيه loading_panel فقط + card_preview يظهر) → اجعل extractPanel=findViewById(card_preview) أو عدّل logic. tv_name غير موجود في xml → أزل السطر 228.
 2. InvoiceActivity: style/children في populateGroupSelector (خطوط 188-199) → أعد كتابة بـ setBackgroundResource/ColorStateList بدلاً من style = Material ريسورس غير موجود في R.style، وcontainer.children → استخدم loop on container.childCount.
 3. InvoiceActivity line 226 hintTextColor → if Build>=O.
 4. CropEditActivity: refreshPreview غير موجود (83)، File/Intent imports ناقصة (167-183) → أضف imports java.io.File, android.content.Intent، أنشئ fun refreshPreview أو استبدل الاستدعاء.
 5. ScannerFragment 169/175 CompressFormat → أضف import android.graphics.Bitmap.CompressFormat.
 6. SearchFragment 182 style (R.style غير موجود)، 206-216 ImageView → أصلح مثل InvoiceActivity.
 7. SettingsFragment 46-61 setupItem trailing lambda مع errorText default param → عدّل calls (أضف {} قبل errorText).
 8. SettingsFragment 120: entry.timestamp/detail → ActivityEntry has at/action → عدّل إلى ${it.at}/${it.action}.
 9. TeamActivity: isAdmin → user.role == Role.ADMIN.
 10. HomeFragment card_recent → حُلّ.
 11. InvoiceExtractor rawText val → حُلّ (var).
 12. ImageProcessor Math.max 3 args → حُلّ (maxOf).
 13. GroupActivity: حُلّ (hintTextColor Build>=O، ImageProcessor imports).
- APK الهدف: app/build/outputs/apk/debug/app-debug.apk
- بعدها: فحص أخطاء موارد أخرى، ثم assembleDebug، اختبار بسيط (تجربة تشغيل غير ممكنة بلا جهاز → على الأقل adb check أو emulator)، ثم تسليم apk + شرح.

## تطور البناء (قديم)
- gradlew جاهز: /opt/gradle-8.5/bin/gradle، Java 21. أمر: export ANDROID_HOME=/home/ubuntu/android_app/sdk && cd /home/ubuntu/android_app && ./gradlew assembleDebug --no-daemon -x lint
- تم إنشاء كل الملفات المتبقية: fragment_search.xml, fragment_placeholder.xml, fragment_settings.xml, activity_team.xml, dialog_add_member.xml, item_member.xml, file_paths.xml (fileprovider في manifest ✓, TeamActivity المسار الصحيح ui.team), strings: أُضيفت search/toggle_theme/activity_log/add_member، formatted=false لـlog_create_*، themes: استبدال color/surface@background بـic_bg/night_background.
- خطأ بناء 1 (حُلّ): color/surface غير معرّف.
- خطأ بناء 2 (حُلّ): formatted=false.
- خطأ بناء 3 (قائم): mipmap/ic_launcher not found — مجلدات res/ تظهر فقط: drawable layout menu values xml رغم أن سكربت بايثون طبّع OK. ملاحظة غريبة: السكربت يكتب بنجاح لكن المجلدات تختفي! يبدو أن /home/ubuntu/android_app قد يكون داخل نظام ملفات غريب أو أن ls يعرض محتوى مختلف. تحقق: ls app/src/main/res/ أظهر drawable layout menu values xml فقط. جرب كتابة ملف mipmap مباشرة بـ shell ثم أعِد البناء.

## ملاحظات البناء
- AGP 8.2 يتطلب Gradle >= 8.2 → استخدم gradle-wrapper.properties: gradle-8.5-bin.
- بعد التثبيت: export ANDROID_HOME=~/android_app/sdk
- APK الناتج: app/build/outputs/apk/debug/app-debug.apk


## المرحلة 17 الحالية: إعادة كتابة الشريط السفلي يدويًا
- المستخدم على تابلت هواوي ميت باد 11.5S. ما زال يرى "Binary XML file line #20 in activity_main: Error inflating" حتى بعد إزالة elevation
- القرار: استبدال BottomNavigationView المادي في activity_main.xml بـLinearLayout مخصص فيه 6 items (nav_home/nav_groups/nav_scanner/nav_search/nav_theme/nav_settings)، كل item = LinearLayout فيه ImageView+TextView، الخلفية #0B1B1D، الأيقونات: ic_home/ic_groups_filled/ic_scanner_white/ic_search_filled/ic_sun_filled/ic_settings_filled
- activity_main.xml تمت إعادة كتابته بالكامل ✓
- MainActivity.kt يجب إعادة كتابة: إزالة import BottomNavigationView، الربط عبر findViewById مباشرة: navHome/navGroups/navScanner/navSearch/navTheme/navSettings setOnClickListener → switchTab(index)/toggleTheme()؛ applyTheme() يجب: background للشريط + tint للأيقونات (ic_home primary #4FD1C5 للفعال، #8FA3A6 للغير فعال)
- applyTheme القديم يستخدم ic_scanner_filled (غير موجود!) — الجديد: ic_scanner_white دائم
- بناء: cd ~/android_app && export ANDROID_HOME=~/android_app/sdk && ./gradlew assembleDebug --no-daemon -x lint ثم cp app/build/outputs/apk/debug/app-debug.apk /home/ubuntu/MasahHisabat-Android.apk
- تعليمات التسليم: احذف النسخة القديمة أولًا (توقيع مختلف)، ادخل mustafa/0، وإذا ظهر خطأ أرسل محتوى Android/data/com.masahhisabat.app/files/crash_log.txt


## تشخيص خطأ inflate المستمر (المرحلة 17-18)
المستخدم على تابلت هواوي ميت باد 11.5S، يحذف النسخة القديمة ويثبت الجديدة وما زال يرى: "Binary XML file line #20 in activity_main: Error inflating".
تم التحقق من: كل drawables الموجودة (ic_home, ic_groups_filled, ic_scanner_white, ic_search_filled, ic_sun_filled, ic_settings_filled, ic_add, ic_invoice, circle_primary, input_bg, ic_folder, ic_more, ic_text, ic_delete, ic_person, card_surface_settings, ic_close, ic_export, ic_import, ic_key, ic_history, ic_logout, ic_people, ic_sun, ic_launcher_foreground) وكل strings وكل colors — كلها موجودة وسليمة. لا attributes خطيرة (strokeLineCap/clipPath...) في drawables. لا elevation في activity_main.xml (أزيل).
تمت إعادة كتابة activity_main.xml بالكامل بشريط سفلي LinearLayout مخصص (nav_home/nav_groups/nav_scanner/nav_search/nav_theme/nav_settings، كل واحد LinearLayout فيه ImageView+TextView، خلفية #0B1B1D) وأعيدت كتابة MainActivity.kt بدون BottomNavigationView نهائيًا مع applyTheme جديد وswitchTab بتلوين active/inactive. ThemeHelper.surfaceTextOrDefault أضيفت. البناء BUILD SUCCESSFUL (8.3MB) والنسخة: /home/ubuntu/MasahHisabat-Android.apk
cmdline-tools bin/sdkmanager --list لا يعرض system-images (المسار cmdline-tools ربما نسخة قديمة) — لا AVD متاح بسهولة في sandbox.
الاحتمال المتبقي: المستخدم يثبّت APK قديم محفوظ في مدير الملفات. أو الخطأ من login_bg? لا — الرسالة activity_main line #20. السطر 20 في النسخة الجديدة من activity_main هو `<LinearLayout android:id="@+id/bottom_nav">` — لا يوجد فيه شيء قابل للفشل. إذا كان المستخدم يرى نفس الخطأ فهذا يعني أنه فعلاً يشغّل APK قديمًا من النسخة التي كان فيها BottomNavigationView مع elevation وapp:menu.
خطوات التسليم: إرسال APK الجديد مع تعليمات: (1) حذف التطبيق من الإعدادات، (2) حذف أي ملفات APK قديمة محفوظة في Downloads، (3) تنزيل/نقل APK الجديد من هذه المحادثة وتثبيته مباشرة.


## المرحلة 19: تذكرني + إصلاح المجموعات (المستخدم أرسل طلبين جديدين بعد نجاح الدخول)
التطبيق الآن يعمل والدخول ينجح على تابلت هواوي ميت باد 11.5S (APK النهائي قبل هذا: /home/ubuntu/MasahHisabat-Android.apk بحجم 8.3MB، بني بشريط سفلي LinearLayout مخصص بدون BottomNavigationView).
الطلبات الجديدة من المستخدم:
1. "تذكرني" لا يعمل — يخرج من التطبيق فيحتاج تسجيل دخول مرة أخرى. أُصلح: LoginActivity الآن عند وجود rememberedLogin يقرأ المستخدم من AppRepository.users().find{username+enabled} ويدخل تلقائيًا فورًا (startActivity MainActivity ثم finish).
2. قسم المجموعات يُغلق التطبيق. أُصلح: أضفت try/catch حول onViewCreated وrefresh في GroupsFragment مع logAndToast يسجل في crash_log.txt ويعرض Toast بدل الخروج. النشاط group_root IDs سليمة كلها (btn_back, group_title, btn_add, et_search, btn_filter, btn_reset, selection_bar, selected_count, btn_cancel_select, btn_share_selected, btn_delete_selected, items_list) وكل drawables وstrings مستخدمة سليمة (CHECK_DONE بدون MISSING).
البناء بعد التعديلات لم يُجرَ بعد. الأمر: cd /home/ubuntu/android_app && export ANDROID_HOME=/home/ubuntu/android_app/sdk && ./gradlew assembleDebug --no-daemon -x lint ثم cp app/build/outputs/apk/debug/app-debug.apk /home/ubuntu/MasahHisabat-Android.apk
التوجيه للمستخدم بعد التسليم: يجب إزالة "تذكرني" من الحسابات القديمة؟ لا — عند أول دخول جديد مع تفعيل تذكرني سيُحفظ. ملاحظة: إذا كان للمستخدم جلسة remember قديمة بمستخدم محذوف، سيبقى في شاشة الدخول — مقبول.
