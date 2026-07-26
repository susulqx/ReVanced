@file:Suppress("unused", "UNCHECKED_CAST")

package app.revanced.patches.uniqueone.subscription

import app.revanced.patcher.extensions.InstructionExtensions.addInstructions
import app.revanced.patcher.extensions.InstructionExtensions.removeInstructions
import app.revanced.patcher.patch.PatchException
import app.revanced.patcher.patch.bytecodePatch
import com.android.tools.smali.dexlib2.AccessFlags

// Stable functional strings.
private const val SP_NAME = "app_setting"
private const val KEY_OAS = "oas"
private const val KEY_OSV = "osv"
private val JSON_KEYS = setOf("st", "pu", "ex", "al", "ca", "v", "te")

/**
 * ReVanced bytecode patch that bypasses subscription verification in [app.unique.one].
 */
val subscriptionBypassPatch = bytecodePatch(
    name = "Subscription bypass",
    description = "Bypasses subscription verification in app.unique.one.",
) {
    compatibleWith("app.unique.one")

    execute {
        // 1. Locate the subscription manager class by the "app_setting" string literal.
        val managerClass = classes.firstOrNull { classDef ->
            classDef.methods.any { method ->
                instructionContains(method, SP_NAME)
            }
        } ?: throw PatchException("Cannot locate class with string '$SP_NAME'")

        // 2. Locate methods by signature.
        val methods = managerClass.methods

        val readIntMethod = methods.single { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.returnType == "I" &&
            method.parameterTypes.size == 2 &&
            method.parameterTypes[0] == "Ljava/lang/String;" &&
            method.parameterTypes[1] == "I"
        }

        val getDataMethod = methods.single { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.parameterTypes.isEmpty() &&
            method.returnType.startsWith("L") &&
            method.returnType !in setOf(
                "Landroid/content/SharedPreferences;",
                "Ljava/lang/String;",
                "Ljava/lang/Object;"
            )
        }

        // 3. Discover and verify the subscription data class (lf).
        val dataClassName = getDataMethod.returnType
        val dataClass = classes.firstOrNull { it.name == dataClassName }
            ?: throw PatchException("Cannot locate class $dataClassName")

        val instanceFields = dataClass.fields.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        if (instanceFields.size != 7) {
            throw PatchException("Expected 7 instance fields in $dataClassName, got ${instanceFields.size}")
        }

        // 4. Build field map from annotation "value" elements (JSON keys).
        val fieldByKey = mutableMapOf<String, String>() // key -> fieldName
        for (field in instanceFields) {
            for (annotation in field.annotations) {
                for (element in annotation.elements) {
                    if (element.name == "value") {
                        fieldByKey[element.value.toString()] = field.name
                    }
                }
            }
        }
        val missing = JSON_KEYS.filter { it !in fieldByKey }
        if (missing.isNotEmpty()) {
            throw PatchException("Missing JSON keys in $dataClassName: $missing")
        }

        // 5. Find the static cache field (type matches data class).
        val cacheFieldName = managerClass.fields.firstOrNull { field ->
            AccessFlags.STATIC.isSet(field.accessFlags) && field.type == dataClassName
        }?.let { "${it.name}:${it.type}" }

        // 6. Patch methods on the mutable class.
        val mutableManager = managerClass.mutableClass

        // (String,int)->int reader: return 1 for "oas", 0 for "osv", passthrough otherwise.
        val mutableReadInt = mutableManager.methods.first { it.name == readIntMethod.name }
        mutableReadInt.removeInstructions(0, mutableReadInt.implementation!!.instructions.size)
        mutableReadInt.addInstructions(0, """
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

        // ()->lf factory: build and return a fully populated fake lf.
        val mutableGetData = mutableManager.methods.first { it.name == getDataMethod.name }
        mutableGetData.removeInstructions(0, mutableGetData.implementation!!.instructions.size)
        mutableGetData.addInstructions(0, buildString {
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
            cacheFieldName?.let { appendLine("sput-object v0, ${managerClass.name}->$it") }
            appendLine("return-object v0")
        }.toString())

        // ()->boolean methods: distinguish by contained string literal.
        val booleanMethods = methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.returnType == "Z" &&
            method.parameterTypes.isEmpty()
        }

        val versionCheck = booleanMethods.single { instructionContains(it, KEY_OSV) }
        val featureGate = booleanMethods.single {
            instructionContains(it, KEY_OAS) && it.name != versionCheck.name
        }

        fun patchBoolean(name: String, value: Int) {
            val m = mutableManager.methods.first { it.name == name }
            m.removeInstructions(0, m.implementation!!.instructions.size)
            m.addInstructions(0, "const/4 v0, 0x${value.toString(16)}\n    return v0")
        }
        patchBoolean(versionCheck.name, 0)   // false -> path1
        patchBoolean(featureGate.name, 1)    // true  -> feature gates open
    }
}

/** Checks whether any instruction in [method] contains the string [value]. */
private fun instructionContains(method: Any, value: String): Boolean {
    // Use reflection to access implementation/instructions safely
    // without hardcoding dexlib2 types that may have module access restrictions.
    return try {
        val impl = method.javaClass.getMethod("getImplementation").invoke(method) ?: return false
        val instructions = impl.javaClass.getMethod("getInstructions").invoke(impl) as? Iterable<*> ?: return false
        instructions.any { it.toString().contains("\"$value\"") }
    } catch (_: Exception) {
        false
    }
}
