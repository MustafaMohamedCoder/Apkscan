package com.masahhisabat.app.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.lang.reflect.Type

/**
 * يسمح لـGson بفك تسلسل البيانات classes (data classes) التي لها خصائص بقيم افتراضية.
 * بدون هذا الـfactory، Gson يعيد LinkedTreeMap لأمثال Group/User، ما يجعل loadList
 * يرجع قوائم فارغة: اسم المجموعة الجديدة لا يظهر، والمستخدم الجديد لا يظهر في الإدارة.
 */
class DataClassAdapterFactory : TypeAdapterFactory {

    override fun <T : Any> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        val raw = type.rawType
        val targets = listOf(
            Group::class.java, User::class.java, InvoiceItem::class.java,
            ActivityEntry::class.java, SyncEntry::class.java
        )
        if (raw !in targets) return null

        // نختار constructor الأذكى: الأكبر عدد معاملات بين الـconstructors القابلة للبناء
        // (Kotlin يولّد constructors إضافية synthetic قد تتصدر المصفوفة)
        val ctor = raw.declaredConstructors
            .maxByOrNull { it.parameterCount }
            ?: return null

        val delegate = gson.getDelegateAdapter(this, type)

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                delegate.write(out, value)
            }

            override fun read(`in`: JsonReader): T {
                val element = JsonParser.parseReader(`in`)
                // نحاول أولًا الطريقة القياسية
                try {
                    val obj = delegate.fromJsonTree(element)
                    if (obj != null) return obj
                } catch (_: Exception) {}
                // fallback: بناء يدوي من JsonElement
                return buildFromElement(raw, ctor, element)
            }

            private fun buildFromElement(raw: java.lang.Class<*>, ctor: java.lang.reflect.Constructor<*>, element: JsonElement?): T {
                val map: Map<String, JsonElement> = if (element != null && element.isJsonObject) {
                    element.asJsonObject.entrySet().associate { (k, v) -> k to v }
                } else emptyMap()

                // serialName -> fieldName
                val serialToField = mutableMapOf<String, String>()
                val fields = raw.declaredFields
                for (f in fields) {
                    val sn = f.getAnnotation(com.google.gson.annotations.SerializedName::class.java)
                    if (sn != null) serialToField[sn.value] = f.name
                }

                fun getElement(fieldOrSerial: String): JsonElement? =
                    map[fieldOrSerial] ?: serialToField[fieldOrSerial]?.let { map[it] }

                val params = ctor.parameters
                val args = arrayOfNulls<Any>(params.size)
                params.forEachIndexed { i, p ->
                    val el = getElement(p.name)
                    val coerced = coerce(el, p.type)
                    // خصائص Kotlin non-null: إذا كانت القيمة null نستبدلها بقيمة صالحة
                    args[i] = coerced ?: defaultValueFor(p.type)
                }
                if (!ctor.isAccessible) ctor.isAccessible = true
                return ctor.newInstance(*args) as T
            }

            private fun coerce(el: JsonElement?, type: java.lang.reflect.Type): Any? {
                if (el == null || el.isJsonNull) return null
                return when (type) {
                    java.lang.Long::class.java, java.lang.Long.TYPE -> el.asLong
                    java.lang.Integer::class.java, java.lang.Integer.TYPE -> el.asInt
                    java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> el.asBoolean
                    String::class.java -> el.asString
                    else -> {
                        val enumClass = type as? java.lang.Class<*>
                        if (enumClass != null && enumClass.isEnum) {
                            try {
                                java.lang.Enum.valueOf(enumClass as Class<out Enum<*>>, el.asString)
                            } catch (_: Exception) { null }
                        } else el.asString
                    }
                }
            }

            private fun defaultValueFor(type: java.lang.reflect.Type): Any? {
                return when (type) {
                    java.lang.Long::class.java, java.lang.Long.TYPE -> 0L
                    java.lang.Integer::class.java, java.lang.Integer.TYPE -> 0
                    java.lang.Boolean::class.java, java.lang.Boolean.TYPE -> false
                    String::class.java -> ""
                    else -> {
                        val cls = type as? java.lang.Class<*>
                        if (cls != null && cls.isEnum) cls.enumConstants.firstOrNull()
                        else null
                    }
                }
            }
        }
    }
}
