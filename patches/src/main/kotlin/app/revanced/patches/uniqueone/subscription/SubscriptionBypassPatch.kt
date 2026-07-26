package app.revanced.patches.uniqueone.subscription

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.removeInstructions
import app.revanced.patcher.fingerprint.MethodFingerprint
import app.revanced.patcher.patch.BytecodePatch
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.annotation.CompatiblePackage
import app.revanced.patcher.patch.annotation.Patch
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethod
import app.revanced.patcher.util.proxy.mutableTypes.MutableMethodImplementation
import app.revanced.patches.uniqueone.subscription.fingerprints.GetSharedPreferencesFingerprint
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

@Patch(
    name = "Subscription bypass",
    description = "Bypasses subscription verification in app.unique.one.",
    compatiblePackages = [CompatiblePackage("app.unique.one")]
)
@Suppress("unused")
object SubscriptionBypassPatch : BytecodePatch(
    setOf(GetSharedPreferencesFingerprint)
) {
    private const val KEY_OAS = "oas"
    private const val KEY_OSV = "osv"
    private val JSON_KEYS = setOf("st", "pu", "ex", "al", "ca", "v", "te")

    override fun execute(context: BytecodeContext) {
        val managerClass = GetSharedPreferencesFingerprint.result?.classDef
            ?: throw PatchException("Cannot locate subscription manager class")

        val mutableManager = context.classes.getOrReplaceMutable(managerClass)

        // (String, int) -> int  : SP int reader
        val readIntMethod = managerClass.methods.single { m: Method ->
            AccessFlags.STATIC.isSet(m.accessFlags) &&
            m.returnType == "I" &&
            m.parameterTypes.size == 2 &&
            m.parameterTypes[0] == "Ljava/lang/String;" &&
            m.parameterTypes[1] == "I"
        }

        // () -> lf  : subscription data factory
        val getDataMethod = managerClass.methods.single { m: Method ->
            AccessFlags.STATIC.isSet(m.accessFlags) &&
            m.parameterTypes.isEmpty() &&
            m.returnType.startsWith("L") &&
            m.returnType !in setOf(
                "Landroid/content/SharedPreferences;",
                "Ljava/lang/String;",
                "Ljava/lang/Object;"
            )
        }

        // Discover and verify lf
        val dataClassName = getDataMethod.returnType
        val dataClass = context.classes.first { it.name == dataClassName }
        val instanceFields = dataClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
        if (instanceFields.size != 7)
            throw PatchException("Expected 7 fields in $dataClassName, got ${instanceFields.size}")

        // Map JSON keys -> field names via annotation "value"
        val fieldByKey = mutableMapOf<String, String>()
        for (field in instanceFields) {
            for (ann in field.annotations) {
                for (el in ann.elements) {
                    if (el.name == "value" && el.value is StringEncodedValue) {
                        fieldByKey[el.value.value] = field.name
                    }
                }
            }
        }
        val missing = JSON_KEYS.filter { it !in fieldByKey }
        if (missing.isNotEmpty()) throw PatchException("Missing JSON keys: $missing")

        // Static cache field (type == dataClassName)
        val cacheField = managerClass.fields.firstOrNull { f ->
            AccessFlags.STATIC.isSet(f.accessFlags) && f.type == dataClassName
        }

        // ---- Apply patches ----

        // 1. readInt: return 1 for "oas", 0 for "osv"
        val mReadInt = mutableManager.methods.first { it.name == readIntMethod.name }
        mReadInt.replaceBody(3, """
            const-string v0, "$KEY_OAS"
            invoke-virtual {p0, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :not_oas
            const/4 v0, 0x1
            return v0
            :not_oas
            const-string v0, "$KEY_OSV"
            invoke-virtual {p0, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :not_osv
            const/4 v0, 0x0
            return v0
            :not_osv
            return p1
        """.trimIndent())

        // 2. getData: construct fake lf
        val mGetData = mutableManager.methods.first { it.name == getDataMethod.name }
        mGetData.replaceBody(4, buildString {
            appendLine("new-instance v0, $dataClassName")
            appendLine("invoke-direct {v0}, $dataClassName-><init>()V")
            fieldByKey["st"]?.let { appendLine("const/4 v1, 0x1"); appendLine("iput v1, v0, $dataClassName->$it:I") }
            fieldByKey["al"]?.let { appendLine("const/4 v1, 0x1"); appendLine("iput-boolean v1, v0, $dataClassName->$it:Z") }
            fieldByKey["pu"]?.let { appendLine("const/4 v1, 0x1"); appendLine("iput-boolean v1, v0, $dataClassName->$it:Z") }
            fieldByKey["ex"]?.let { appendLine("const-wide v2, 0x7fffffffffffffffL"); appendLine("iput-wide v2, v0, $dataClassName->$it:J") }
            appendLine("invoke-static {}, Ljava/lang/System;->currentTimeMillis()J")
            appendLine("move-result-wide v2")
            fieldByKey["ca"]?.let { appendLine("iput-wide v2, v0, $dataClassName->$it:J") }
            fieldByKey["v"]?.let { appendLine("const/4 v1, 0x1"); appendLine("iput v1, v0, $dataClassName->$it:I") }
            fieldByKey["te"]?.let { appendLine("const/4 v1, 0x0"); appendLine("iput v1, v0, $dataClassName->$it:I") }
            cacheField?.let { appendLine("sput-object v0, ${managerClass.name}->${it.name}:${it.type}") }
            appendLine("return-object v0")
        }.trimIndent())

        // 3. boolean methods: version check (false) + feature gate (true)
        val boolMethods = managerClass.methods.filter { m: Method ->
            AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "Z" && m.parameterTypes.isEmpty()
        }
        val versionCheck = boolMethods.single { hasString(it, KEY_OSV) }
        val featureGate = boolMethods.single { hasString(it, KEY_OAS) && it.name != versionCheck.name }

        val mVersion = mutableManager.methods.first { it.name == versionCheck.name }
        mVersion.replaceBody(1, "const/4 v0, 0x0\nreturn v0")

        val mFeature = mutableManager.methods.first { it.name == featureGate.name }
        mFeature.replaceBody(1, "const/4 v0, 0x1\nreturn v0")
    }

    private fun hasString(method: Method, value: String): Boolean {
        return method.implementation?.instructions?.any { it.toString().contains("\"$value\"") } == true
    }

    private fun MutableMethod.replaceBody(registerCount: Int, smali: String) {
        this.implementation = MutableMethodImplementation(registerCount)
        this.addInstructions(0, smali)
    }
}
