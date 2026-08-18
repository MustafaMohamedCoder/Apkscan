# حالة العمل — تطبيق ماسح الحسابات

## المهمة الحالية (المطلوب الآن)
1. **إلغاء** «تعديل كلمة المرور» (سبب تعديل كلمة المرور من البطاقة + showEditPasswordDialog).
2. أيقونة العين في بطاقة العضو (iv_copy_password في item_member.xml) → **تعرض/تخفي كلمة المرور** (toggle visibility في TextView member_password) بدل النسخ.
3. **حوار تعديل العضو**: كلمة المرور تظهر مشفرة عند المستخدم — السبب: `etPassword.setText(user.passwordHash)` في السطر 286 يكتب الهاش الخام **قبل** منطق decoded في السطر 308-316، لكن setSelection/setText قد يتعطل مع transformationMethod. الحل: إزالة السطر 286 نهائيًا (المنطق اللاحق decoded يكفي)، والتأكد أن ivEye موجود في dialog_add_member.xml (R.id.iv_show_password).
   - تنبيه: المستخدم ما زال يقول كلمة المرور مشفرة في الحوار — ربما كلمة مروره فعلًا من النوع v2 الخاطئ (هاكس). لكن إصلاح authenticate الجديد يعيد ضبطها عند الدخول الناجح بـ mustafa/0 — لكن قد يدخل عبر "تذكرني" بدون المرور بـ authenticate! يجب أيضًا تشغيل إصلاح كلمات المرور في بداية TeamActivity (ensureDefaults تجري في initAppContext من onCreate — يجب أن تعمل الآن).
   - **الأهم**: في ensureDefaults إصلاح v2 الخاطئ يحدده بـ length==64 hex فقط — تأكد أن منطق ensureDefaults ما زال موجودًا (نعم، في السطور 102+).
4. البناء: `cd /home/ubuntu/android_app && export ANDROID_HOME=/home/ubuntu/android_app/sdk && ./gradlew assembleDebug --no-daemon -x lint` ثم `cp app/build/outputs/apk/debug/app-debug.apk /home/ubuntu/MasahHisabat-Android.apk`
5. التسليم: رسالة موجزة + APK.

## بنية الملفات ذات الصلة
- TeamActivity.kt: onBindViewHolder (سطور 123-195) يعرض row_password/member_password/iv_copy_password؛ showEditPasswordDialog (سطور 200-231) — يجب حذفها.
- item_member.xml: row_password (View قابل للنقر)، member_password (TextView)، iv_copy_password (ImageView عين).
- dialog_add_member.xml: et_username، et_password، iv_show_password (عين)، tv_hint، sp_role.
- HashUtil.kt: encodePlain/decodePlain/decodePlain("v2:...")/isDecodable.

## خلفية المشكلة الرئيسية (محصورة الآن)
- كانت ensureDefaults لا تُستدعى إلا بعد منح الإذن (onActivityResult). أُصلح: initAppContext يستدعيها الآن.
- authenticate: fallback يعيد ضبط كلمة ADMIN إلى "0" إذا كانت v2 خاطئة (64 hex).
- LoginActivity يسجل login_diag.txt عند فشل الدخول.
- APK التسليم: /home/ubuntu/MasahHisabat-Android.apk

## ما تم إنجازه سابقًا
- فواصل واتساب، إخفاء إدارة الفريق لغير mustafa، عرض كلمة المرور في البطاقة، إصلاح الدخول mustafa/0 — أُرسلت كلها.

## جلسة متابعة (Aug 18) — عارض الصور الكامل
- عارض الصور (ImageViewerActivity): تم بنائه. GroupActivity.showImagePreview تفتح ImageViewerActivity مع تمرير group_id وimage_index (فهرس الصورة بين صور المجموعة بالترتيب المعروض من الأحدث للأقدم).
- إصلاح خطأ بناء: استيراد RecyclerView وتصحيح تعريف adapter كـ inner class.
- APK أُعيد بناؤه بنجاح: /home/ubuntu/MasahHisabat-Android.apk (7.63MB)

## جلسة متابعة (Aug 18) — تكبير الصور
- تم إنشاء ZoomableImageView.kt: pinch-to-zoom (أصبعان) + نقرة مزدوجة للتكبير/التصغير (حتى 4x) + تمرير داخلي عند التكبير + حدود عدم خروج الصورة عن الشاشة.
- عند عدم التكبير: السحب الأفقي ينقل بين الصور في ViewPager2 (التمرير الداخلي معطّل والحدث يمرر للـ pager).
- ImageViewerActivity: pager.isUserInputEnabled=true + ItemTouchListener يمنع التنقل عند isZoomed.
- ImageViewHolder يحمل ZoomableImageView. APK جديد مبني.

## جلسة متابعة (Aug 18) — إصلاح الصور + حذف تغيير كلمة المرور
1. **الصور بعد إعادة التثبيت (تم الإصلاح):**
   - السبب: الصور كانت تُنسخ إلى cacheDir الداخلي الذي يُحذف مع التطبيق، والمسار المخزن في items.json يصبح مكسورًا بعد إعادة التثبيت.
   - الحل: AppRepository.persistAppImage() تنسخ الصور إلى Documents/MasahHisabat/images (دائم) عند الإرسال في المجموعات والفواتير.
   - AppRepository.remapTempImagePaths() يُستدعى عند عرض عناصر المجموعة لإعادة ربط أي مسارات داخلية قديمة موجودة بدائم.
   - GroupActivity (الإرسال + العرض) + InvoiceActivity (الحفظ) يستخدمون persistAppImage.
2. **حذف «تغيير كلمة المرور» من الإعدادات:** حُذف setupItem + showChangePasswordDialog + تلوين الصفوف من SettingsFragment، ووُضع visibility="gone" على item_password في fragment_settings.xml.
3. **كلمات المرور غير مشفرة:** كانت أصلًا تُعرض غير مشفرة (decodePlain) في TeamActivity — النظام v2 قابل للفك. أي كلمات مرور قديمة SHA-256 يظهر لها حوار «غير قابلة للعرض» فيتعين على المستخدم إعادة تعيينها.
- APK مبني بنجاح: /home/ubuntu/MasahHisabat-Android.apk (7.63MB)

## جلسة تحسين احترافي التطبيق (Aug 18) — قيد التنفيذ
المطلوب من المستخدم: "حسن التطبيق بشكل عام واجعله احترافي".

### التحسينات المنفذة حتى الآن:
1. **ThemeHelper.kt** — دوال جديدة: primaryGradientColors(), chipBgColor(), chipTextColor(), dividerColor(), greetingColor().
2. **MainActivity.kt** — switchTab مع fade animations، تكبير الأيقونة النشطة (scale 1.15) والليبل (1.08) بحركة 180ms، خط عريض للعنصر النشط.
3. **fragment_home.xml** — شريط ترحيب جديد (greeting "مرحبًا بك 👋" + title + أيقونة welcome_badge دائرية)، subtitle بخط medium، بطاقات إحصائيات بزوايا 24dp + stroke + elevation 4dp + أيقونات 36dp + أرقام 32sp بلون primary_dark، أيقونة ٧ بخلفية دائرية surface_high.
4. **HomeFragment.kt** — تلوين greeting/welcome_badge، تلوين خلفية أيقونة ٧، عدّاد تصاعدي animateCounter مع ease-out.
5. **item_group.xml** — زوايا 24dp + stroke elevation 4dp، إزالة selectableItemBackground وhint القديم من group_name، group_date بخط medium.

### المتبقي:
- تحسين fragment_groups.xml (ترويسة + زر إضافة بارز).
- تحسين msg_bubble_bg وitem_invoice (بطاقات الرسائل).
- تحسين activity_login (زر متدرج + ظلال).
- تحسين item_member.xml (تاج الصلاحية chipBgColor).
- ملاحظة: strokeColor في XML ثابت (@color/day_card_stroke) — البطاقات تستخدم ThemeHelper.strokeColor في الكود، لكن XML يعيد الضبط؛ الحل الأفضل ترك stroke 1dp مع day_card_stroke لأنه مقبول في الليل أيضًا.
- البناء والتسليم بأمر build القياسي → /home/ubuntu/MasahHisabat-Android.apk

### نتيجة التحسين الاحترافي (مكتمل):
- البناء ناجح: BUILD SUCCESSFUL. APK: /home/ubuntu/MasahHisabat-Android.apk
- المنفذ: ترحيب في الرئيسية + عدّاد تصاعدي، حركات تبديل التبويبات، بطاقة المجموعة والمجموعات والعضو بزوايا 24dp وstroke، فقاعة رسائل بتدرج Teal، أيقونات تعديل/مشاركة/إرسال/إرفاق دائرية بارزة، شاشة دخول محسّنة (أيقونة أكبر + شارة الحساب التجريبي + فوتر Teal)، حركات دخول العناصر.

## جلسة توحيد الألوان (Aug 18) — المطلوب: "حسّن واجهة تسجيل الدخول وواجهة المستخدم والمجموعات والرسائل داخل المجموعات وألوان الحقول والنصوص وألوان الفقاعات وألوان التطبيق"

### نتائج فحص الألوان الثابتة (hardcoded) في layouts/drawables:
- **activity_main.xml**: labels غير النشطة `#8FA3A6` ثابت (يجب أن يتغير مع الوضع) — الحل: تطبيق inactiveLabel ديناميكيًا في applyTheme (يُكتب في MainActivity — موجود جزئيًا via setColorFilter لكن labelText يبقى في XML فقط).
- **activity_login.xml**: textColor حقول الإدخال `#000000` أسود صلب — المستخدم طلب سابقًا لون أسود، سنحافظ عليه.
- **fragment_groups.xml / item_group.xml / item_member.xml**: tint أبيض ثابت لأيقونات داخل خلفيات متدرجة — مقبول (أبيض على متدرج Teal).
- **fragment_scanner.xml**: نص tiles أبيض على متدرج — مقبول.
- **activity_group.xml / item_invoice.xml**: tint أبيض ثابت (دوائر Teal) — مقبول.
- **msg_bubble_bg.xml**: تدرج `#0D6E66→#0F766E→#128B80` — ألوان ثابتة لا تتغير مع الوضع (الفقاعات داكنة في الوضع الليلي والنهاري) — المستخدم قال «ألوان الفقاعات»؛ نُنشئ نسخة ليلية: msg_bubble_bg_night.xml بتدرج أفتح، ونختار في الكود (GroupActivity يلوّن bubble؟).
- **viewer_counter_bg.xml**: `#88000000` عداد الصور في الوضع الليلي مقبول لكن في النهاري غير واضح — نُنشئ نسخة فاتحة viewer_counter_bg_light + نختار في ImageViewerActivity حسب الوضع.

### خطة توحيد الألوان:
1. **colors.xml**: إضافة ألوان ليلية/نهارية لكل العناصر الموحدة:
   - bubble_night (تدرج فاتح للوضع الليلي) / bubble_day (التدرج الحالي)
   - right_text: أبيض دائم للفقاعة
   - left_text / right_time: ألوان موحدة للوقت والتذييل
2. **MainActivity.applyTheme**: تلوين labels غير النشطة ديناميكيًا (كانت ثابتة #8FA3A6 في XML).
3. **msg_bubble_bg.xml** يبقى نهاري + جديد **msg_bubble_bg_night.xml** + في GroupActivity: اختيار الخلفية حسب الوضع (البحث عن msg_bubble_bg في الكود).
4. **ImageViewerActivity**: viewer_counter_bg حسب الوضع (خلفية شبه شفافة فاتحة في النهاري / داكنة في الليلي — الأفضل: لون ثابت يتغير حسب الوضع).
5. **item_invoice**: نص التذييل msg_time_date/msg_sender msg_seen — التأكد من ألوان موحدة (تظهر أزرق #... عند المشاهدة) — التحقق من GroupActivity bind.
6. **activity_login**: توحيد خلفيات الحقول (input_bg) مع النظام الليلي.
7. بناء وتسليم.

### أوامر البناء/التسليم:
```bash
cd /home/ubuntu/android_app && export ANDROID_HOME=/home/ubuntu/android_app/sdk && ./gradlew assembleDebug --no-daemon -x lint 2>&1 | tail -4 && cp /home/ubuntu/android_app/app/build/outputs/apk/debug/app-debug.apk /home/ubuntu/MasahHisabat-Android.apk
```
التسليم: message result + إرفاق /home/ubuntu/MasahHisabat-Android.apk

### توحيد الألوان (مكتمل Aug 18):
- colors.xml: bubble_day/night + bubble_text/time/seen + counter_light/dark
- msg_bubble_bg_night.xml + viewer_counter_bg_light.xml
- ThemeHelper: bubbleBgRes/bubbleText/bubbleTime/bubbleSeen/counterBgRes/counterText
- GroupActivity: الفقاعة خلفية متدرجة موحدة حسب الوضع + نص أبيض + وقت فاتح + ✓ أزرق فاتح (seen)
- ImageViewerActivity: عداد حسب الوضع
- LoginActivity: applyLoginTheme (خلفية login_bg نيلي/login_bg_day نهاري، حقول input_fill ديناميكيًا، نصوص حسب الوضع)
- البناء ناجح → /home/ubuntu/MasahHisabat-Android.apk


## جلسة أيقونات خطية بنمط جوجل (Aug 18 ~02:50)
- **الطلب:** جعل أيقونات التطبيق بسيطة تشبه نمط أيقونات جوجل (Material line icons).
- الأدوات: خط Material Symbols Outlined v220 woff2 في /home/ubuntu/icons/icons.woff2
- سكربت التوليد: /home/ubuntu/icons/gen_icons.py → vector XML في /home/ubuntu/icons/out/
- **المشكلة:** الأيقونات المولدة تظهر ممتلئة (مربعات سوداء) لأن كل خط = مساران (outline خارجي + interior أصغر) والملء non-zero يكسب الفراغ.
- **الحل:** إضافة `android:fillType="evenOdd"` في القالب (آمن: minSdk=26) ثم إعادة التوليد والمعاينة (preview.py).
- MAP كامل في gen_icons.py (home→ic_home، groups→ic_groups_filled، barcode_scanner→ic_scanner_filled، search→ic_search_filled، settings→ic_settings_filled، light_mode→ic_sun_filled، dark_mode→ic_moon، camera→ic_camera، image→ic_gallery، add→ic_add، send→ic_send، edit→ic_edit، share→ic_share، delete→ic_delete، refresh→ic_reset، sync→ic_sync، visibility→ic_visibility، visibility_off→ic_visibility_off، logout→ic_logout، image_search→ic_search_lens، check_circle→ic_seen_check، history→ic_history، folder→ic_folder، person→ic_person، group→ic_people، key→ic_key، grid_view→ic_grid، close→ic_close، keyboard_arrow_down→ic_chevron_down، upload→ic_upload، download→ic_import، drive_file_move→ic_export، text_fields→ic_text، qr_code_scanner→ic_scanner_white، photo_library→ic_image_attach، more_vert→ic_more، note_add→ic_invoice_new، receipt_long→ic_invoice، compare→ic_compare، filter_list→ic_filter، rotate_right→ic_rotate، wifi→ic_wifi، check→ic_check، done→ic_done)
- الألوان المطلوبة في الملفات: ic_home=@color/accent، ic_scanner_filled=gray(#8FA8AF)، ic_gallery_white/#FFF، ic_image_attach=@color/primary، ic_send/@color/accent — يجب الحفاظ على fillColor الحالية في كل ملف.
- الأيقونات في الشريط السفلي activity_main.xml: ic_home, ic_groups_filled, ic_scanner_white, ic_search_filled, ic_sun_filled, ic_settings_filled
- البناء/التسليم: أمر build القياسي → /home/ubuntu/MasahHisabat-Android.apk + message result

### نتائج تجارب توليد الأيقونات (Aug 18):
- fillType=evenOdd مع حذف أكبر subpath: فاشل (أشكال مشوهة) — gen2.py و out2/ ملغيان.
- النهج الرابح: **stroke** — معاينة /tmp/test_stroke.png أظهرت أيقونة home خطية مثالية. المسار الأصلي من الخط (2 subpaths: الشكل + الحدود الخارجية) مع stroke-width=6 (viewport 96).
- خطة out3: توليد vector XML بـ fill="none" + stroke بلون fillColor الأصلي + stroke-width="6" + strokeLineJoin="round" + strokeLineCap="round". strokeColor يحتاج لونًا صلبًا — fillColor الحالي في بعض الملفات @color/accent أو @color/primary (resource refs مقبولة في strokeColor).
- معاينة preview3.py تستخدم stroke="#0F766E"/"white"/"#8FA8AF".

### تجربة stroke (out3): نتائج مختلطة — home و chevron_down و close ممتازة، لكن camera/gallery/invoice/settings أخطأت لأن الخط يرسم بأكثر من subpath (outline داخلي وخارجي معًا) والرسم كله كخط واحد يتداخل. الحل الجديد: stroke على الـ subpath الأطول فقط (الشكل الخارجي) — سنجرب gen4: نفس gen3 لكن نأخذ أطول subpath فقط بدل كل subpaths.

### قرار مهم: توليد الأيقونات من خطوط Material Symbols فاشل (gen2/gen3/gen4 كلها أعطت أشكالًا مشوهة). النهج الجديد: تنزيل أيقونات line جاهزة من مستودع Google الرسمي `google/material-design-icons` على GitHub (مجلد svg/production) — كل أيقونة متوفرة كـ SVG خطي مستقل يمكن تحويله مباشرة إلى vector XML أندرويد. سنجرب تنزيل ic_home_black_24dp.svg أولًا والتحويل والتحقق من الشكل.

## مهمة أيقونات Google line (Aug 18 — تحديث)
**المصدر الموثوق:** مستودع `material-icons/material-icons` على GitHub (not google/material-design-icons الذي يوفر PNG فقط). أيقونات outline في: `svg/{icon_name}/outline.svg`. أمثلة ناجحة التنزيل: `svg/home/outline.svg`, `svg/group/outline.svg`, `svg/qr_code_scanner/outline.svg`, `svg/settings/outline.svg`. المستودع يحتوي ~2191 أيقونة × 5 متغيرات.

**سكربت العمل:** `/home/ubuntu/icons/gen5.py` — ينزل outline.svg ويحوّله إلى Android vector XML بـ stroke (1.5 + strokeLineCap/Join=round) مع قلب الإحداثيات Y.
- MAP: home→ic_home(@color/accent), group→ic_groups_filled(#FFF), qr_code_scanner→ic_scanner_filled(#8FA8AF), search→ic_search_filled(#FFF), settings→ic_settings_filled(#FFF), light_mode→ic_sun_filled(#FFF), dark_mode→ic_moon(#8FA8AF), photo_camera→ic_camera(#FFF), image→ic_gallery(#FFF), add→ic_add(#000), send→ic_send(#FFF), edit→ic_edit(#FFF), share→ic_share(#FFF), delete→ic_delete(#FFF), refresh→ic_reset(#FFF), sync→ic_sync(#FFF), visibility→ic_visibility(#FFF), visibility_off→ic_visibility_off(#FFF), logout→ic_logout(#FFF), image_search→ic_search_lens(@color/primary), check_circle→ic_seen_check(#FFF), history→ic_history(#FFF), folder→ic_folder(#FFF), person→ic_person(#FFF), groups→ic_people(#FFF), key→ic_key(#FFF), grid_view→ic_grid(#FFF), close→ic_close(#FFF), keyboard_arrow_down→ic_chevron_down(#FFF), upload→ic_upload(#FFF), download→ic_import(#FFF), drive_file_move→ic_export(#FFF), text_fields→ic_text(#FFF), photo_library→ic_image_attach(@color/primary), more_vert→ic_more(#FFF), note_add→ic_invoice_new(#FFF), receipt_long→ic_invoice(#FFF), compare→ic_compare(#FFF), filter_list→ic_filter(#FFF), rotate_right→ic_rotate(#FFF), wifi→ic_wifi(#FFF), check→ic_check(#FFF), done→ic_done(#FFF)
- **ملاحظة:** قد لا توجد بعض الأسماء بالمطابقة الحرفية (مثل qr_code_scanner قد تكون image_not_supported أو qr_code_2) — gen5 يطبع missing.
- **خطأ preview5:** strokeColor="@color/accent" لا يُستبدل لأن الاستبدال يحدث بعد replace للـ xmlns — يجب إعادة ترتيب الاستبدالات (استبدال الألوان قبل xmlns).
- المعاينة: preview5.py (من preview3 مع استبدال out3→out5) + grid5.py (يخرّج icons_grid6.png).
- **المتبقي:** إصلاح preview5 ثم معاينة النتائج، ثم نسخ ملفات out5/*.xml إلى android_app/app/src/main/res/drawable/ (استبدال الأيقونات الحالية بنفس الأسماء)، ثم بناء APK وتسليمه.
- ملاحظة تصميمية: stroke-width=1.5 في viewport 24 → مناسب للخطوط الرفيعة (نمط جوجل). ألوان stroke في XML تُستبدل أثناء المعاينة فقط.

### تشخيص gen5 الحالي: المسار الناتج خاطئ — flip_coords_simple يحوّل رقمًا مفردًا (بعد H/V/h/v) إلى زوج (x, vh-x) لأن nums الفردية تُكمّل بـ "0"! النتيجة "M 19 11 h -6 24 v 6 24" معطلة. الحل: H/V/h/v تأخذ رقمًا واحدًا (X أو Y فقط)، يجب معالجتها منفصلة: regex مخصص يأخذ الحرف وكل الأرقام بعده حتى الحرف التالي، ويرسم H/V من رقم واحد فقط.

### تشخيص gen5 (2): المسار الناتج لا يزال خاطئًا ("v 30.00", "h 6 v 30"). السبب: القيم النسبية (الحروف الصغيرة m/l/h/v/c/s/q/t) يجب قلب إشارتها (-y) وليس (24-y) لأنها إحداثيات نسبية لا مطلقة. الحل الصحيح في المعالجة: الحروف الكبيرة (M,L,H,V,C,S,Q,T,A): قلب الإحداثيات المطلقة (y→24-y, وv→-v في اتجاهها). الحروف الصغيرة: قلب الإشارة (v→-v, y→-y, x يبقى). سأعيد كتابة المعالجة في gen5 بهذه القاعدة.

### gen5 مشكلة A: "A 5.50 0.00 0.00 0 61 22.00 -0.00" — المصدر "a1.2-.4.6 0 1 22.00-.00" تقريبًا منسوب. regex يأخذ الأرقام لكن chunking بـ 7 قيم خاطئ لأن الأرقام المنسوبة "-.4" تُقرأ كـ "" و"-" و"4". الحل الموثوق: استخدام مكتبة svg.path لتحويل d إلى أوامر + التعامل معها (pip install svg.path). سأعيد كتابة التحويل باستخدام svg.path بدل regex.

## حالة gen6 (السكربت الجديد)
- `/home/ubuntu/icons/gen6.py` يستخدم svg.path (sudo pip3 install svg.path) لتحليل d من outline.svg بشكل صحيح، ويعكس Y في كل النقاط (مطلقة: vh-y).
- MAP كامل وBASE وOUT=/home/ubuntu/icons/out6 في السكربت نفسه.
- المعاينة: انسخ preview5.py إلى preview6.py (تبديل out5→out6 وpreview5→preview6) وشبكة: grid6.py من grid5.py.
- الخطوات المتبقية: (1) sudo pip3 install svg.path، (2) python3 gen6.py، (3) معاينة icons_grid، (4) إذا سليمة: cp out6/*.xml إلى /home/ubuntu/android_app/app/src/main/res/drawable/ (استبدال الأيقونات الحالية بنفس الأسماء: ic_home, ic_groups_filled, ic_scanner_filled, ic_search_filled, ic_settings_filled, ic_sun_filled, ic_moon, ic_camera, ic_gallery, ic_add, ic_send, ic_edit, ic_share, ic_delete, ic_reset, ic_sync, ic_visibility, ic_visibility_off, ic_logout, ic_search_lens, ic_seen_check, ic_history, ic_folder, ic_person, ic_people, ic_key, ic_grid, ic_close, ic_chevron_down, ic_upload, ic_import, ic_export, ic_text, ic_image_attach, ic_more, ic_invoice_new, ic_invoice, ic_compare, ic_filter, ic_rotate, ic_wifi, ic_check, ic_done)، (5) بناء APK: cd /home/ubuntu/android_app && export ANDROID_HOME=/home/ubuntu/android_app/sdk && ./gradlew assembleDebug --no-daemon -x lint، (6) cp app/build/outputs/apk/debug/app-debug.apk /home/ubuntu/MasahHisabat-Android.apk، (7) تسليم للمستخدم.
- أسماء الأيقونات الحالية في drawable/ يجب مطابقتها أولًا قبل النسخ (grep srcCompat في layouts لمعرفة الأسماء الفعلية المستخدمة).
- ألوان stroke في XML المولد: home/ic_search_lens/ic_image_attach → @color/accent أو @color/primary (تتكيف)، ic_scanner_filled → #8FA8AF (ثابت — لا يُلوَّن بالشريط!)، البقية #FFFFFF (للشريط السفلي الداكن).
- ملاحظة: الشريط السفلي في MainActivity يعتمد أيقونات بيضاء على خلفية داكنة، لكن عند تفعيل التبويب تلون بالأخضر عبر tintColor في الكود.

### معاينة gen6: الأيقونات مشوهة (أشكال مضخمة ومشوهة). السبب: outline.svg من material-icons يستخدم fill (stroke-like shapes ممتلئة كخطوط سميكة) وتحويلها strokeWidth=1.5 يخلط. الحل out7: استخدام fill بدل stroke — fillColor="#000000" (ثابت أسود/رمادي) أو استخدام fillColor الأصلي من SVG مع تلوين via tintColor في الكود. الأيقونات line بهذا الستايل تكون fill shapes — Android vector يقبلها، والتلوين الديناميكي عبر ImageView.setColorFilter.

### معاينة gen7 (fill): أغلب الأيقونات خطية نظيفة لكن بعضها (home, history, people, person, settings, sun, scanner_filled) ممتلئة/مشوهة لأن أشكالها مبنية من stroke-like fills تحتاج fillType="evenOdd" في Android vector. الحل out8: نفس gen7 لكن إضافة android:fillType="evenOdd" على <path>.

### نتيجة out8: الأيقونات الجيدة نظيفة خطية (add, camera, check, chevron, close, edit, export, filter, folder, gallery, grid, import, invoice, key, logout, more, seen_check, send, share, text, upload, visibility). المشوهة/الممتلئة التي تحتاج بديلاً: home, history, people, person, settings_filled, sun_filled, scanner_filled, moon, groups_filled, reset, compare, delete, image_attach, search_lens, filter?, wifi, done. الخطة: رسم هذه يدويًا كمسارات line بسيطة (material icons 24dp معيارية معروفة) — نسخ مساراتها الرسمية من مستودع Google الرسمي (master branch svg/production). بديل أسرع: استخدام أيقونات line من مستودع marella/material-icons (PNG) → تحويل لمسار؟ لا. سنجرب تنزيل SVG من: https://raw.githubusercontent.com/google/material-design-icons/master/src/materialsymbolsoutlined/home/svg/production/ic_home_24.svg

### مصادر أيقونات line (من البحث):
- مستودع marella/material-symbols: svg/500 يحتوي SVG icons لكل نمط: `https://github.com/marella/material-symbols/tree/main/svg/500` — unfilled = FILL 0 line icons. مثال URL: https://raw.githubusercontent.com/marella/material-symbols/main/svg/500/filled/home.svg (وunfilled ربما في مجلد آخر). فحص: README يقول SVGs متاحة لـ unfilled (FILL 0) و filled (FILL 1).
- مستودع google/material-design-icons: المسار الجديد غير معروف (old svg/production لم يعد موجودًا في master).
- الحل المطبق حاليًا: gen8.py تولد /home/ubuntu/icons/out8 مع fill + fillType=evenOdd — جيدة لأغلب الأيقونات. المشوهة: home, history, people, person, settings_filled, sun_filled, scanner_filled, moon, groups_filled, reset, compare, delete, image_attach, search_lens, wifi, done (يجب التحقق).
- الخطوة التالية: تجربة marella: curl https://raw.githubusercontent.com/marella/material-symbols/main/svg/500/outlined/home.svg — إذا نجح، تحويلها بـ gen8 pipeline (svg.path + fill).

### gen9 فشل: المعاينة تظهر أيقونات فارغة تقريبًا (القلب اليدوي يكسر المسارات). الحل: استخدام conv_segment من gen6.py (الذي يعمل صحيحًا) + fillColor بدل stroke + حتىOdd. الملفان المرجعيان: /home/ubuntu/icons/gen6.py (conv_segment صحيح) وpreview9.py (معاينة صحيحة).

### المعاينة 10 فارغة تمامًا: يجب فحص XML مباشرة — الأرجح أن معاينة 10 لا تطابق fillColor بسبب newlines/indentation. فحص /home/ubuntu/icons/out10/ic_home.xml + preview10.py

### XML 10 سليم والمسار صحيح. المعاينة فارغة بسبب حتىOdd: المسار line مُصمم بـ 3 subpaths متداخلة (outer+cutout+inner) وevenOdd يفرغ المناطق المتداخجة بشكل خاطئ هنا. الحل: بدون حتىOdd! (المسار line أصلاً مصمم fill-rule non-zero). سنجرب preview بدون fill-rule.

### معاينة out11 (بدون حتىOdd): فارغة بالكامل (icons_grid11.png مربعات بيضاء). يجب اختبار render واحد مع طباعة الخطأ الصريح لمعرفة السبب — احتمال regex في preview11 يشطب جزءًا مهمًا من XML.

### اكتشاف جذري: المعاينة 11 فارغة لأن إحداثيات المسار تمتد حتى 1807 بينما viewport=960×960 — المسار خارج نطاق العرض! السبب: gen10.py استخدم viewportWidth ثابت 960 لجميع الأيقونات لكن خطوط marella/svg/500 لها viewBox أكبر (1536 أو 2048 حسب فILL). الحل: استخراج viewBox الفعلي من SVG المصدر وضبط viewportWidth/Height مطابقًا له.

### السبب الحقيقي: SVG marella/500 يستخدم viewBox="0 -960 960 960" — إحداثيات سالبة! gen10 قلب y عبر (vh-y) مع vh=960 فخرجت الإحداثيات 960..1807. التحويل الصحيح: newY = 960 + y (إزاحة فقط، لا قلب — SVG أصلاً oriented بحيث -960=أعلى و0=أسفل، Android viewport origin أعلى). حل gen12.

### معاينة 12 ما تزال فارغة (لكن groups_filled يظهر خط رفيع). يجب اختبار render واحد بدون أي استبدال لمعرفة الخطأ الحقيقي.

### خطأ gen12: ty() أُطبقت على x وy معًا (960+real و960+imag) بينما الإزاحة 960 يجب على y فقط! إصلاح gen13: ty فقط على imag.

### preview13/ic_home.png الآن صحيح (alpha 0-255، bbox 20-107). icons_grid13.png لم يُنشأ — grid13.py فشل بصمت؟ أعده تشغيل مع التحقق.

### نجاح gen13: 14/15 أيقونة line مثالية (home, history, groups, people, person, settings, sun, moon, reset, scanner, search_lens, wifi, done, delete). ic_compare مشوهة — سنرسمها يدويًا (مسار compare بسيط: خطان عموديان متقابلان أو arrow icons). البقية تُستخدم من out13.

### ic_compare: SVG الأصلي نفسه يعطي شكلًا غريبًا (مثلث كبير) — الأيقونة الأصلية 'compare' هي لوحةان مع أسهم لكن المسار هنا مضغوط. الحل: رسم يدوي 24dp لمادة compare: خطان عموديان بأسهم عند أطرافهما. سنرسمها يدويًا بنفس ستايل line 60+dp stroke-thickness.

### ic_compare اليدوي الأولي غير واضح. البديل: تنزيل compare_arrows من marella (سهمان متقابلان) — أوضح وظيفيًا.

### ic_compare = compare_arrows (سهمان متقابلان): مقبولة وواضحة. الآن: نسخ out13/* إلى drawable (استبدال الموجودة)، ثم بناء APK. الأسماء في out13: ic_home, ic_history, ic_people, ic_groups_filled, ic_done, ic_person, ic_settings_filled, ic_sun_filled, ic_moon, ic_reset, ic_compare, ic_delete, ic_scanner_filled, ic_search_lens, ic_wifi — تحقق من عدم تعارض الألوان: كلها @color/accent ما عدا ic_scanner_filled الذي يجب أن يبقى #8FA8AF؟ تحقق من out13 ic_scanner_filled fillColor.

### خطة النسخ النهائية:
- من out13 (كلها accent): ic_home, ic_history, ic_people, ic_groups_filled, ic_done, ic_person, ic_settings_filled, ic_sun_filled, ic_moon, ic_reset, ic_compare, ic_delete, ic_search_lens, ic_wifi
- تعديلات لون مطلوبة بعد النسخ: ic_scanner_filled (يجب #8FA8AF وليس accent — لم يُستخدم كـ src في layouts لكن يوجد كملف)، أي ملف من out13 يُستخدم في الشريط السفلي الداكن يظل accent لأن الشريط يتلون ديناميكيًا عبر setColorFilter في الكود؟ **تحذير**: الشريط السفلي في MainActivity ملأه داكن ويطبق tintColor ديناميكيًا (الأخضر للنشط، #8FA3A6 للغير) عبر setColorFilter — هذا يعمل مع fillColor accent (يتلون بالـ color filter)؟ setColorFilter مع PorterDuff.Mode.SRC_IN يحل محل كل ألوان البكسل → نعم يتلون بشكل صحيح. إذن لا مشكلة.
- لكن ملاحظة: ic_sun_filled في الشريط السفلي (تبويب الوضع الليلي) يلون بالأخضر/رمادي عبر الكود — OK.
- أيقونات بيضاء موجودة سابقًا (ic_camera_white, ic_gallery_white, ic_scanner_white): تُستخدم على دوائر ملونة — نحافظ عليها كما هي (من out8 الجيدة).
- ic_search_filled في layouts (fragment_search?) — نتركه كما هو (من out8)؟ الأفضل أيضًا توليده من marella 'search' — لكن out13/ic_search_lens موجود من marella. ic_search_filled مختلف (عدسة تعبئة). تحقق أين يُستخدم ic_search_filled في layout.

### تأكيد: MainActivity.kt:116 يطبق setColorFilter على كل أيقونات الشريط السفلي (activeColor/inactiveColor) — fillColor في XML لا يهم، الأيقونات تتلون ديناميكيًا. ic_scanner_white يبقى أبيض (على FAB أخضر). نسخ out13 إلى drawable الآن + إبقاء الأيقونات البيضاء (camera_white, gallery_white, scanner_white) من out8.

### مُكتمل: 14 أيقونة line جديدة من marella/material-symbols في drawable (out13 + ic_compare=compare_arrows). APK جديد مبني: /home/ubuntu/MasahHisabat-Android.apk (7.4MB). الأيقونات البيضاء (camera_white, gallery_white, scanner_white) بقيت من out8 على الدوائر الخضراء. تُسلم للمستخدم للفحص البصري على التابلت.

## إصلاح: الشريط السفلي يغطي تسجيل الخروج (Aug 18 ~03:15)
- السبب: activity_main.xml يستخدم FrameLayout — الشريط العائم يطفو فوق محتوى fragment_container. fragment_settings.xml ScrollView بـ match_parent بدون padding سفلي → آخر العناصر (item_logout + version_text) تنحجب خلف الشريط.
- الحل: إضافة `android:clipToPadding="false"` وpaddingBottom=110dp (حوالي 80dp ارتفاع الشريط + 30dp هامش) على ScrollView.
- بناء + تسليم APK.

### إضافة: RecyclerView في fragment_groups (سطر 50-55) clipToPadding=false بدون paddingBottom → آخر مجموعة تُنحجب خلف الشريط. fragment_search ScrollView بلا paddingBottom → نتائج البحث السفلية تُنحجب. fragment_home: NestedScrollView padding-bottom 16 فقط → المحتوى السفلي ينحجب. إصلاح الثلاثة جميعًا بإضافة paddingBottom=120dp (مع clipToPadding=false حيث يلزم).

### إصلاح تغطية الشريط السفلي: paddingBottom=120dp + clipToPadding=false أُضيف إلى: fragment_settings (ScrollView)، fragment_groups (RecyclerView)، fragment_search (ScrollView النتائج)، fragment_home (NestedScrollView). fragment_scanner لا يحتوي قوائم طويلة. البناء الآن.

## المهمة الجديدة (Aug 18 ~03:20): إعادة تصميم أيقونة التطبيق الرئيسية الخارجية بنمط بسيط مثل أيقونات جوجل
- الحالة الحالية: adaptive icon في mipmap-anydpi-v26/ic_launcher.xml: background=@color/ic_bg (#06252C)، foreground=@drawable/ic_launcher_foreground.xml (viewport 108dp: إطار سكانر + خط مسح بأكبر أسهم) — design معقد نسبيًا.
- المطلوب: تصميم بسيط بنمط Material You (Google): foreground أبيض/أفتح خطي بسيط على خلفية ic_bg (سنبقي الإخفاء الآمن: foreground يجب أن يكون داخل safe zone 72dp — أبعاد المكون تُضبط على 108 لكن content ضمن 36-72).
- خطة: foreground جديد بخط عدسة سكانر مبسطة: مربع scan frame بخطوط قصيرة + qr dot بسيط، stroke أبيض، أو تصميم Material Icons: barcode_reader مبسطة. سنرسم vector: إطار مربع بزوايا + 4 نقاط QR داخل — بسيطة وواضحة بالأبيض على خلفية #06252C.
- ملاحظة: يوجد أيضًا mipmap-xxxhdpi/ic_launcher.png (PNG احتياطي) — يجب توليده من XML أو تحديثه. الأفضل توليد PNG بحجم 432x432 من vector: circle radius=108/2 خلفية ic_bg + foreground.
- بعد التعديل: بناء APK + تسليم.

### معاينة launcher: foreground لم يُرسم — cairosvg لا يفهم a:prefix. الحل: تحويل سمات android: إلى أسماء SVG قياسية (stroke, stroke-width, d, fill, stroke-linecap) بعد إزالة xmlns:android.

### معاينة الأيقونة الجديدة: ممتازة — إطار سكانر أبيض بخطوط زوايا + 4 مربعات QR + خط مسح سماوي. بنمط Material You البسيط. البناء الآن.
