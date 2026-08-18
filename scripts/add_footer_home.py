import re

path = 'app/src/main/res/layout/fragment_home.xml'
content = open(path).read()

# 1. حذف TextView dev_credit القديم بالكامل
pattern = re.compile(r'\s*<TextView\n\s*android:id="@\+id/dev_credit"\n(?:\s*android:\S+="[^"]*"\n)*?\s*/>\n')
content = pattern.sub('\n', content)

# 2. إضافة include قبل </LinearLayout> الداخلي الأخير
marker = '    </LinearLayout>\n</androidx.core.widget.NestedScrollView>'
if marker in content:
    content = content.replace(marker, '        <include layout="@layout/layout_footer" />\n' + marker, 1)
else:
    print('MARKER NOT FOUND')

open(path, 'w').write(content)

# تحقق
if 'layout_footer' in content:
    print('footer added OK')
else:
    print('footer MISSING')
if 'dev_credit' in content:
    print('dev_credit still present (check)')
else:
    print('dev_credit removed OK')
