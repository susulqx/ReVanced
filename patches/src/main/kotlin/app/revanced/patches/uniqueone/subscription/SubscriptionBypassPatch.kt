package app.revanced.patches.uniqueone.subscription

import app.revanced.patcher.data.BytecodeContext
import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.containsLiteralInstruction
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
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

/**
 * ReVanced port of the universal Xposed subscription bypass module.
 *
 * Instead of hooking at runtime, this patch statically rewrites the target
 * App's subscription manager so that every verification path behaves as if the
 * user had a valid subscription.
 *
 * Mapping from the original Xposed module:
 *
 * | Xposed hook | ReVanced equivalent |
 * |-------------|---------------------|
 * | Write "oas"=1 / "osv"=0 to SP and hook (String,int)->int | Patch [readSubscriptionInt] to return 1 for "oas" and 0 for "osv" |
 * | Hook ()->lf to return fake data | Patch [getSubscriptionData] to construct and return a fully populated fake lf |
 * | Hook ()->boolean, return false for Fragment callers | Patch the version-check method to always return false |
 * | Hook ()->boolean, return true for Activity callers | Patch the feature-gate method to always return true |
 * | Locate zcc from stack trace | Fingerprint the getSharedPreferences("app_setting") method |
 * | Locate lf by field signature + annotations | Discover lf from the return type of getSubscriptionData and verify its fields |
 */
@Patch(
    name = "Subscription bypass",
    description = "Bypasses subscription verification in app.unique.one by forcing the verified subscription code path.",
    compatiblePackages = [
        CompatiblePackage("app.unique.one")
    ]
)
@Suppress("unused")
object SubscriptionBypassPatch : BytecodePatch(
    setOf(GetSharedPreferencesFingerprint)
) {
    // Stable identifiers that are functional strings and therefore survive obfuscation.
    private const val SP_NAME = "app_setting"
    private const val KEY_OAS = "oas"
    private const val KEY_OSV = "osv"

    private val SUBSCRIPTION_DATA_JSON_KEYS = setOf("st", "pu", "ex", "al", "ca", "v", "te")

    override fun execute(context: BytecodeContext) {
        // 1. Locate the subscription manager class (zcc) from the getSharedPreferences entry point.
        val managerMatch = GetSharedPreferencesFingerprint.result
            ?: throw PatchException("Cannot locate subscription manager: no method references '$SP_NAME'")
        val managerClass = managerMatch.classDef
        val mutableManagerClass = context.classes.getOrReplaceMutable(managerClass)

        // 2. Locate the two core subscription manager methods by signature.
        val readIntMethod = managerClass.methods.singleOrNull { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.returnType == "I" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == "Ljava/lang/String;" &&
            method.parameterTypes[1] == "I"
        } ?: throw PatchException("Cannot locate (String,int)->int SP reader in subscription manager")

        val getDataMethod = managerClass.methods.singleOrNull { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.isEmpty() &&
            method.returnType.startsWith("L") &&
            method.returnType != "Landroid/content/SharedPreferences;" &&
            method.returnType != "Ljava/lang/String;" &&
            method.returnType != "Ljava/lang/Object;"
        } ?: throw PatchException("Cannot locate ()->lf subscription data factory in subscription manager")

        // 3. Discover and verify the subscription data class (lf) from the factory return type.
        val dataClassName = getDataMethod.returnType
        val dataClass = context.classes.find { it.name == dataClassName }
            ?: throw PatchException("Cannot locate subscription data class $dataClassName")

        if (!isSubscriptionDataClass(dataClass)) {
            throw PatchException("Class $dataClassName does not match the subscription data class signature")
        }

        // 4. Map annotation JSON keys (st/pu/ex/al/ca/v/te) to concrete fields.
        val fieldMap = dataClass.fields
            .filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
            .mapNotNull { field ->
                val jsonKey = field.annotations
                    .flatMap { annotation -> annotation.elements }
                    .find { element -> element.name == "value" }
                    ?.value
                    ?.let { value -> if (value is StringEncodedValue) value.value else null }
                jsonKey?.let { key -> key to field }
            }
            .toMap()

        if (!SUBSCRIPTION_DATA_JSON_KEYS.all { it in fieldMap }) {
            throw PatchException("Subscription data class is missing one or more JSON key annotations")
        }

        // 5. Find the static cache field in the subscription manager that holds the lf singleton.
        val cacheField = managerClass.fields.singleOrNull { field ->
            AccessFlags.STATIC.isSet(field.accessFlags) && field.type == dataClassName
        }

        // 6. Apply bytecode patches.
        patchReadIntMethod(mutableManagerClass.methods.first { it.name == readIntMethod.name })
        patchGetDataMethod(
            mutableManagerClass.methods.first { it.name == getDataMethod.name },
            managerClass.name,
            dataClassName,
            fieldMap,
            cacheField
        )

        val booleanMethods = managerClass.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.returnType == "Z" &&
            method.parameterTypes.isEmpty()
        }

        val versionCheckMethod = booleanMethods.singleOrNull { it.containsLiteralInstruction(KEY_OSV) }
            ?: throw PatchException("Cannot locate the version-check ()->boolean method")
        val featureGateMethod = booleanMethods.singleOrNull {
            it.containsLiteralInstruction(KEY_OAS) && it.name != versionCheckMethod.name
        } ?: throw PatchException("Cannot locate the feature-gate ()->boolean method")

        patchBooleanMethod(mutableManagerClass.methods.first { it.name == versionCheckMethod.name }, false)
        patchBooleanMethod(mutableManagerClass.methods.first { it.name == featureGateMethod.name }, true)
    }

    /**
     * Verifies that [classDef] is the subscription data class:
     * exactly 7 instance fields, type combination 3×int + 2×boolean + 2×long,
     * and all expected JSON keys present in field annotations.
     */
    private fun isSubscriptionDataClass(classDef: ClassDef): Boolean {
        val instanceFields = classDef.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
        if (instanceFields.size != 7) return false

        var intCount = 0
        var boolCount = 0
        var longCount = 0
        val foundKeys = mutableSetOf<String>()

        for (field in instanceFields) {
            when (field.type) {
                "I" -> intCount++
                "Z" -> boolCount++
                "J" -> longCount++
                else -> return false
            }
            field.annotations
                .flatMap { annotation -> annotation.elements }
                .find { element -> element.name == "value" }
                ?.value
                ?.let { value -> if (value is StringEncodedValue) value.value else null }
                ?.let { foundKeys.add(it) }
        }

        return intCount == 3 && boolCount == 2 && longCount == 2 &&
            SUBSCRIPTION_DATA_JSON_KEYS.all { it in foundKeys }
    }

    /**
     * Patch the generic SP int reader so that:
     * - read("oas", def) -> 1
     * - read("osv", def) -> 0
     * - everything else is passed through unchanged.
     */
    private fun patchReadIntMethod(mutableMethod: MutableMethod) {
        mutableMethod.replaceBody(
            registerCount = 3, // v0 local + p0 (String) + p1 (int)
            smali = """
                const-string v0, "$KEY_OAS"
                invoke-virtual {p0, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :cond_0
                const/4 v0, 0x1
                return v0

                :cond_0
                const-string v0, "$KEY_OSV"
                invoke-virtual {p0, v0}, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :cond_1
                const/4 v0, 0x0
                return v0

                :cond_1
                return p1
            """
        )
    }

    /**
     * Patch the subscription data factory to build and return a fake lf instance.
     * Fields are identified by their annotation JSON keys, so field names can be
     * obfuscated and will still be set correctly.
     */
    private fun patchGetDataMethod(
        mutableMethod: MutableMethod,
        managerClassName: String,
        dataClassName: String,
        fieldMap: Map<String, Field>,
        cacheField: Field?
    ) {
        val smali = buildString {
            appendLine("new-instance v0, $dataClassName")
            appendLine("invoke-direct {v0}, $dataClassName-><init>()V")

            // st -> status = 1
            setIntField(this, fieldMap["st"], dataClassName, value = 1)
            // al -> isActive = true
            setBooleanField(this, fieldMap["al"], dataClassName, value = true)
            // pu -> isPremium = true
            setBooleanField(this, fieldMap["pu"], dataClassName, value = true)
            // ex -> expireTime = Long.MAX_VALUE
            setLongField(this, fieldMap["ex"], dataClassName, literal = "0x7fffffffffffffffL")
            // ca -> createTime = System.currentTimeMillis()
            setLongFieldFromCall(this, fieldMap["ca"], dataClassName,
                "invoke-static {}, Ljava/lang/System;->currentTimeMillis()J",
                "move-result-wide v2")
            // v -> version = 1
            setIntField(this, fieldMap["v"], dataClassName, value = 1)
            // te -> type = 0
            setIntField(this, fieldMap["te"], dataClassName, value = 0)

            // Keep the original singleton-cache behaviour if the field exists.
            cacheField?.let {
                appendLine("sput-object v0, $managerClassName->${it.name}:${it.type}")
            }
            appendLine("return-object v0")
        }

        mutableMethod.replaceBody(registerCount = 4, smali = smali)
    }

    private fun setIntField(builder: StringBuilder, field: Field?, dataClassName: String, value: Int) {
        if (field == null || field.type != "I") return
        builder.appendLine("const/4 v1, 0x${value.toString(16)}")
        builder.appendLine("iput v1, v0, $dataClassName->${field.name}:${field.type}")
    }

    private fun setBooleanField(builder: StringBuilder, field: Field?, dataClassName: String, value: Boolean) {
        if (field == null || field.type != "Z") return
        builder.appendLine("const/4 v1, 0x${if (value) 1 else 0}")
        builder.appendLine("iput-boolean v1, v0, $dataClassName->${field.name}:${field.type}")
    }

    private fun setLongField(builder: StringBuilder, field: Field?, dataClassName: String, literal: String) {
        if (field == null || field.type != "J") return
        builder.appendLine("const-wide v2, $literal")
        builder.appendLine("iput-wide v2, v0, $dataClassName->${field.name}:${field.type}")
    }

    private fun setLongFieldFromCall(
        builder: StringBuilder,
        field: Field?,
        dataClassName: String,
        invoke: String,
        moveResult: String
    ) {
        if (field == null || field.type != "J") return
        builder.appendLine(invoke)
        builder.appendLine(moveResult)
        builder.appendLine("iput-wide v2, v0, $dataClassName->${field.name}:${field.type}")
    }

    /**
     * Patch a no-arg boolean method to always return [value].
     * Used for the version-check (false) and feature-gate (true) methods.
     */
    private fun patchBooleanMethod(mutableMethod: MutableMethod, value: Boolean) {
        mutableMethod.replaceBody(
            registerCount = 1,
            smali = """
                const/4 v0, 0x${if (value) 1 else 0}
                return v0
            """
        )
    }

    /**
     * Replaces the entire body of a method with new smali instructions.
     */
    private fun MutableMethod.replaceBody(registerCount: Int, smali: String) {
        this.implementation = MutableMethodImplementation(registerCount)
        this.addInstructions(0, smali.trimIndent())
    }
}
