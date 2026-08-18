# ملاحظات إعادة التصميم الاحترافي

## طابع المرجع (Whacka web app)
- خلفية تدرج أخضر داكن (Teal عميق #0F4C4C → أخضر مزرق #17787A) في شاشة الدخول.
- بطاقة بيضاء كبيرة بزوايا دائرية كبيرة (~24dp)، عنوان كبير بخط عريض، حقل إدخال رمادي فاتح بزوايا دائرية كاملة، زر تسجيل دخول أخضر (Teal #1AB394 تقريبًا) بزوايا دائرية كاملة وعريض، فاصل «المالك»، زر outline ثانوي.
- الشعار أعلى الصفحة: أيقونة فاتورة داخل مربع أخضر.

## خطة إعادة تصميم التطبيق
1. **الألوان الجديدة**: Primary = Teal #0F766E / #0D9488، Accent #14B8A6، خلفية داكنة #042F2E، سطح داكن #134E4A، سطح فاتح أبيض #FFFFFF مع خلفيات #F2F6F5.
2. **شاشة الدخول**: تدرج Teal خلفي + بطاقة بيضاء بزوايا 28dp + أيقونة التطبيق + زر primary كامل الاستدارة + حقول بخلفية #F3F4F6 وحدود دائرية.
3. **الشريط السفلي**: شريط عائم (floating) بزوايا دائرية وظل، العنصر النشط بتظليل Teal + أيقونة ملونة، مع مؤثر ripple.
4. **البطاقات**: حدود دائرية 16dp، ظل ناعم، أيقونات داخل دوائر بتدرج Teal، مسافات مريحة.
5. **الأزرار**: primary ممتلئ بأيقونة + نص وزوايا 14dp، secondary outline.
6. **عناوين الشاشات**: شريط علوي أبيض/سطح نظيف مع عنوان عريض وأيقونة رجوع دائرية، أو استخدام MaterialToolbar مخصص.
7. **ThemeHelper**: تحديث الألوان للوضعين النهاري والليلي.
8. **activity_login**: تصميم مرجعي كامل.
9. **MainActivity bar**: floating pill.
10. **HomeFragment**: بطاقات إحصائيات متدرجة، قائمة أنيقة.
11. **GroupsFragment**: بطاقات مجموعات مع أيقونة مجلد دائرية ملونة، زر إضافة عائم FAB.
12. **ScannerFragment**: بطاقة ماسح كبيرة مع إطار ماسح متحرك وإطار دائري للزر.
13. **الخط**: استخدام خط عربي احترافي — نضيف Cairo/Tajawal من res/font (أو نكتفي بخط النظام العريض للـtitle). الأفضل: نسخ خطوط Tajawal TTF إلى res/font وتعديل themes.xml لاستخدام fontFamily.
14. **Logo**: إنشاء شعار جديد (فاتورة داخل مربع Teal) كـvector drawable متدرج.
15. **الوضع الليلي**: ألوان مريحة (خلفية #042F2E، بطاقات #0B4A45، نص #E6FFFB).

## ملفات للتعديل
- res/values/colors.xml, themes.xml
- themes: fontFamily خط Tajawal (يجب توفير ttf)
- login_bg.xml, activity_login.xml, LoginActivity.kt
- activity_main.xml + MainActivity.kt (شريط سفلي عائم)
- fragment_home.xml + HomeFragment.kt
- fragment_groups.xml + GroupsFragment.kt + item_group.xml
- fragment_scanner.xml
- fragment_settings.xml + SettingsFragment.kt
- activity_group.xml + GroupActivity.kt + item_invoice.xml
- activity_invoice.xml + InvoiceActivity.kt
- activity_team.xml + TeamActivity.kt + item_member.xml
- fragment_search.xml + SearchFragment.kt
- ThemeHelper.kt
- جميع drawable الأيقونات: توحيد stroke/fill

## من لقطة الرئيسية المرجعية (الوضع الليلي)
- خلفية داكنة جدًا تقريبًا سوداء مع لمسة Teal: #051514/#0A1F1E
- عنوان أعلى: «ماسح» بالـTeal (#14B8A6) + «الحسابات» أبيض عريض كبير، وتحته وصف رمادي صغير.
- بطاقتا إحصائيات داكنتان: أيقونة صغيرة Teal أعلى يمين البطاقة، رقم عريض كبير أبيض، تسمية صغيرة رمادية.
- زر عريض أخضر فاتح (#14B8A6) بزوايا 20dp مع ظل Teal وأيقونة سكانر ونص «ابدأ مسحًا جديدًا».
- قسم «آخر العمليات» مع أيقونة ساعة، وبطاقة حالة فارغة دائرية.
- تذييل: «التطبيق من تطوير مصطفي ❤️ عبدالفتاح» رمادي صغير.
- شريط سفلي داكن: العنصر النشط Teal، وأيقونة السكانر في مربع أخضر مرتفع عن الشريط (floating).

## حالة إعادة التصميم (قبل ضغط السياق)
### مكتمل:
- colors.xml: نظام جديد كامل (primary #0D9488, accent #14B8A6, night #041615/#0B2623/#123631, day #F5F9F8/#FFFFFF/#EDF5F3, input_fill/fill_night/stroke/stroke_night, login_top/mid/bottom, night_nav_bar #06201E, day_nav_bar #FFFFFF)
- themes.xml: خط Tajawal (Regular) في الثيم + TextStyle للـbutton/editText + shapeAppearance
- خطوط: res/font/Tajawal_Regular.ttf, Tajawal_Bold.ttf, Tajawal_Medium.ttf (من github google/fonts ofl/tajawal)
- strings.xml: +app_name_title (ماسح Teal), +home_subtitle, dev_credit بلون أحمر للقلب
- login_bg.xml: تدرج login_top→mid→bottom بزاوية 135
- input_bg.xml: input_fill + 14dp + حد input_stroke
- card_surface.xml: day_surface_high (تُستخدم scanner tiles → تم استبدالها بـscanner_tile_bg)
- card_surface_settings.xml: night_surface_high + حد night_card_stroke (تُستخدم fragment_settings rows)
- circle_primary.xml: دائرة accent
- nav_bar_bg.xml + scanner_fab_bg.xml: للشريط السفلي العائم الجديد
- group_icon_bg.xml + scanner_tile_bg.xml: تدرجات Teal
- fragment_home.xml: عنوان مركب app_name_title، بطاقات إحصائيات 20dp بأيقونات Teal tint، زر start_scan accent 20dp
- HomeFragment.kt: إصلاح مستدعي السكانر المكسور (nav_scanner.performClick)، strokeColor للبطاقات، تلوين أيقونات الإحصائيات، tint حقل recent_empty
- activity_main.xml: شريط سفلي عائم (margin 12dp، elevation 10dp، nav_bar_bg) مع مربع سكانر أخضر 52dp بارز
- MainActivity.kt: activeColor=ThemeHelper.accent، inactiveLabel=ThemeHelper.inactiveLabel، bar tint=navBarColor
- fragment_scanner.xml: بطاقات camera/gallery بخلفية scanner_tile_bg ونصوص بيضاء Tajawal_Bold
- ScannerFragment.kt: أزيل تلوين الأيقونات والنصوص (البطاقات تدرج ثابت)
- fragment_groups.xml: title 24 → لم يعدل بعد؟ (تم تعديل activity_group فقط)
- item_group.xml: بطاقة 20dp، أيقونة مجلد في FrameLayout 48dp بتدرج group_icon_bg، أيقونة بيضاء
- GroupsFragment.kt: تم فيه سابقًا حماية try/catch (لا تغيير جديد)
- activity_group.xml: أزرار filter/reset cornerRadius 14dp + Tajawal_Medium، et_search خلفية input_bg 48dp، زر إضافة circle_primary 46dp
- fragment_search.xml: عنوان Tajawal_Bold 26، حقل بحث مع أيقونة search_icon داخل FrameLayout (paddingStart 48)، زر filter cornerRadius 14
- item_invoice.xml: margins 20dp، corRadius 18، elevation 3
- item_member.xml: أيقونة شخصية داخل group_icon_bg 44dp، اسم Tajawal_Bold، حذف borderless

### متبقي:
1. SettingsFragment.kt applyTheme: السطور 74-90 تكتب setBackgroundColor(surface) على كل صف — تمسح drawable الخلفي! يجب: إزالة التلوين أو استخدام cardSurface بدلًا منه (الأفضل: استبدال السطر 82 بترك الخلفية كما هي وتعديل الألوان حسب الوضع عبر setColorFilter على الخلفية، أو جعل card_surface_settings يستخدم ألوان theme عبر state). الحل العملي: استبدل view.findViewById<LinearLayout>(id)?.setBackgroundColor(surface) بعملية tint على drawable.
2. fragment_settings.xml: صفوف بخلفية card_surface_settings (dark) ثابتة — في الوضع النهاري ستبقى داكنة. يجب تلوينها ديناميكيًا في SettingsFragment حسب الوضع (surface نهار/سطح ليلي).
3. activity_team.xml: فحص وتجميل (عنوان Tajawal_Bold).
4. activity_invoice.xml: حقول input_bg، أزرار، تجميل populateGroupSelector + applyTheme (214-233).
5. GroupActivity.kt: applyTheme 247-258 يضع setBackgroundColor على et_search وselection_bar — يجب استبداله بـ tint أو إزالة. أيضًا ItemsAdapter يلوّن البطاقات بالكود.
6. SearchFragment.kt: buildResultCard (192-249) وshowSuggestions (163-190) — تجميل البطاقات وchips + تلوين search_icon بلون textSecondary.
7. TeamActivity.kt: بسيط، فحص سريع.
8. LoginActivity.kt: تعديل الشريط (status bar) شفاف مع login_bg (فحص)، لا حاجة لتعديل كبير.
9. activity_crop_edit.xml: فحص سريع.
10. أيقونات: فحص ic_home, ic_groups_filled, ic_search_filled, ic_settings_filled, ic_sun_filled — قد تكون outline رمادية. لا تغيير ضروري.
11. إعادة البناء: cd /home/ubuntu/android_app && export ANDROID_HOME=/home/ubuntu/android_app/sdk && ./gradlew assembleDebug --no-daemon -x lint ثم cp APK.
